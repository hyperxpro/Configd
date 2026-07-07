package io.configd.common.auth;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The functional default {@link RevocationChecker}: a CRL-file lookup using only the JDK's
 * {@link CertificateFactory}/{@link X509CRL} (no new dependency). It reports the leaf certificate's
 * revocation status against a Certificate Revocation List on disk (RFC 5280), named by
 * {@code configd.auth.revocation.crlFile}. A live OCSP responder stays a pluggable
 * {@link RevocationChecker} the operator supplies; this ships so revocation can actually REJECT a
 * revoked cert end-to-end out of the box.
 *
 * <h2>Status mapping (fail-safe - never throws)</h2>
 * <ul>
 *   <li>the leaf's serial is on a fresh CRL {@code ->} {@link RevocationStatus#REVOKED};</li>
 *   <li>the leaf is NOT on a fresh CRL {@code ->} {@link RevocationStatus#GOOD};</li>
 *   <li>the CRL is missing, unparseable, or STALE (past its {@code nextUpdate}) {@code ->}
 *       {@link RevocationStatus#UNKNOWN} - the responder-down analogue, so the {@link RevocationPolicy}
 *       mode decides (lax fails open + alarms; strict fails closed). A checker MUST NOT throw for a
 *       routine load/parse failure, so every failure degrades to {@code UNKNOWN}.</li>
 * </ul>
 *
 * <h2>Freshness</h2>
 * The CRL is parsed and cached, and re-parsed only when the file's last-modified time changes - so a
 * rotated CRL is picked up without a re-parse on every connection, and the (cold) admission path does not
 * re-read/parse a multi-KiB CRL per connect. A CRL past its own {@code nextUpdate} is treated as stale
 * ({@code UNKNOWN}) exactly as a browser would decline to trust an expired CRL.
 */
public final class CrlFileRevocationChecker implements RevocationChecker {

    private static final Logger LOG = Logger.getLogger(CrlFileRevocationChecker.class.getName());

    private final Path crlFile;
    /** Cached parse, refreshed on an mtime change; volatile for the multi-event-loop-thread read. */
    private volatile Cached cached;

    private record Cached(long mtimeMillis, X509CRL crl) {
    }

    public CrlFileRevocationChecker(Path crlFile) {
        this.crlFile = Objects.requireNonNull(crlFile, "crlFile");
    }

    @Override
    public RevocationStatus check(X509Certificate leaf, List<X509Certificate> chain) {
        return checkAt(leaf, System.currentTimeMillis());
    }

    /** {@link #check} with an injectable clock, so the stale-{@code nextUpdate} branch is deterministically testable. */
    RevocationStatus checkAt(X509Certificate leaf, long nowMillis) {
        X509CRL crl = load();
        if (crl == null) {
            return RevocationStatus.UNKNOWN; // missing / unparseable -> responder-down analogue
        }
        Date nextUpdate = crl.getNextUpdate();
        if (nextUpdate != null && nowMillis > nextUpdate.getTime()) {
            LOG.log(Level.WARNING, () -> "CRL " + crlFile + " is STALE (nextUpdate " + nextUpdate
                    + " has passed) -> UNKNOWN");
            return RevocationStatus.UNKNOWN; // stale CRL -> let the mode decide (lax open / strict closed)
        }
        return crl.isRevoked(leaf) ? RevocationStatus.REVOKED : RevocationStatus.GOOD;
    }

    /** Loads (mtime-cached) the CRL, or {@code null} on any missing/parse failure (never throws). */
    private X509CRL load() {
        try {
            long mtime = Files.getLastModifiedTime(crlFile).toMillis();
            Cached c = cached;
            if (c != null && c.mtimeMillis() == mtime) {
                return c.crl();
            }
            byte[] bytes = Files.readAllBytes(crlFile);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(new ByteArrayInputStream(bytes));
            cached = new Cached(mtime, crl);
            return crl;
        } catch (Exception e) {
            // Missing file, unreadable, or malformed CRL: degrade to UNKNOWN (the mode decides). Do NOT
            // cache the failure - a transiently-missing CRL should be retried on the next connection.
            LOG.log(Level.FINE, e, () -> "CRL " + crlFile + " unavailable/unparseable -> UNKNOWN");
            return null;
        }
    }
}
