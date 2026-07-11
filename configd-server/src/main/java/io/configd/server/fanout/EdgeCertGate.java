package io.configd.server.fanout;

import io.configd.common.auth.CredentialExpiryPolicy;
import io.configd.common.auth.RevocationChecker;
import io.configd.common.auth.RevocationPolicy;
import io.configd.common.auth.RevocationStatus;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The edge client-certificate validity gate: the two checks that apply to a verified edge client
 * certificate, independent of whether token auth is also configured. Threaded into both fan-out transports
 * and applied at connection admission for every edge cert connection (the mTLS-only path and the
 * token-edge cert path):
 *
 * <ol>
 *   <li><b>Online revocation</b> ({@link #admit}) - applies the {@link RevocationPolicy} off/lax/strict
 *       posture to the chain. Default OFF is byte-identical to before (no lookup).</li>
 *   <li><b>Mid-connection {@code notAfter} enforcement</b> ({@link #certCloseDeadlineMillis}) - when
 *       enabled, the connection is armed to close at {@code notAfter + leeway} (a cert cannot refresh
 *       in-band, so the close is a reconnect signal). Default off returns {@link AuthState#NO_EXPIRY}:
 *       the handshake already validated {@code notAfter} once, at connect.</li>
 * </ol>
 *
 * <p><b>Interior exemption (structural).</b> This gate is only ever constructed for the edge fan-out
 * plane; the Raft inter-node transport never builds one, so the cluster interior consults no revocation
 * responder and arms no client-expiry tick. That is the structural enforcement of
 * {@link RevocationPolicy#exemptInterNode}: a down responder under {@code strict} can never brick
 * consensus, because the interior has no gate in its path. Inter-node cert expiry is handled by
 * {@code TlsManager.reload()} rotation, not by this gate.
 */
public final class EdgeCertGate {

    private static final Logger LOG = Logger.getLogger(EdgeCertGate.class.getName());

    /**
     * The {@code CREDENTIAL_EXPIRED} close reason for a cert whose {@code notAfter} was reached
     * mid-connection. A cert cannot refresh in-band, so the reason directs the client to reconnect with
     * its rotated certificate rather than to re-authenticate an existing session.
     */
    static final String CERT_EXPIRED_MESSAGE =
            "client certificate notAfter reached; reconnect with a rotated certificate";

    /**
     * The byte-identical OFF gate: revocation OFF (no lookup) and cert-{@code notAfter} enforcement off
     * (no active expiry). This is what both transports use when neither is configured.
     */
    public static final EdgeCertGate OFF =
            new EdgeCertGate(RevocationPolicy.OFF, null, CredentialExpiryPolicy.DEFAULTS, false);

    private final RevocationPolicy revocationPolicy;
    /** Nullable: even under {@code lax}/{@code strict}, an unconfigured checker yields UNKNOWN (mode decides). */
    private final RevocationChecker checker;
    private final CredentialExpiryPolicy expiryPolicy;
    private final boolean enforceCertNotAfter;
    /**
     * Nullable observability hook fired once per fail-open ADMIT (LAX + responder unreachable), so a
     * degraded-revocation posture is alertable, not just logged. Null on {@link #OFF} and wherever no
     * metric sink is threaded in (revocation cannot fail-open when it is OFF, so a null hook is inert).
     */
    private final Runnable onFailOpenAdmit;

    public EdgeCertGate(RevocationPolicy revocationPolicy, RevocationChecker checker,
                        CredentialExpiryPolicy expiryPolicy, boolean enforceCertNotAfter) {
        this(revocationPolicy, checker, expiryPolicy, enforceCertNotAfter, null);
    }

    public EdgeCertGate(RevocationPolicy revocationPolicy, RevocationChecker checker,
                        CredentialExpiryPolicy expiryPolicy, boolean enforceCertNotAfter,
                        Runnable onFailOpenAdmit) {
        this.revocationPolicy = Objects.requireNonNull(revocationPolicy, "revocationPolicy");
        this.checker = checker;
        this.expiryPolicy = Objects.requireNonNull(expiryPolicy, "expiryPolicy");
        this.enforceCertNotAfter = enforceCertNotAfter;
        this.onFailOpenAdmit = onFailOpenAdmit;
    }

    /**
     * Revocation admission decision for a verified edge client-cert chain.
     *
     * @return {@code true} to ADMIT, {@code false} to REJECT (a definite revoked, or an unreachable
     *         responder under {@code strict}). Always {@code true} when the posture is OFF (byte-identical)
     *         or the chain is empty.
     */
    boolean admit(List<X509Certificate> chain) {
        if (!revocationPolicy.enabled() || chain == null || chain.isEmpty()) {
            return true;
        }
        X509Certificate leaf = chain.get(0);
        RevocationStatus status;
        try {
            status = (checker != null) ? checker.check(leaf, chain) : RevocationStatus.UNKNOWN;
        } catch (RuntimeException e) {
            // A checker must not throw for a routine unreachable; a defensive backstop treats a throw as
            // UNKNOWN so a buggy checker never becomes a harder dependency than the configured mode.
            LOG.log(Level.WARNING, "edge revocation checker threw; treating as UNKNOWN", e);
            status = RevocationStatus.UNKNOWN;
        }
        boolean admit = revocationPolicy.admits(status);
        if (revocationPolicy.shouldAlarm(status)) {
            // The responder-down alarm: lax pairs it with a fail-open ADMIT, strict with a fail-closed
            // REJECT. A loud WARNING is the operator signal; the fail-open ADMIT also increments a
            // dedicated counter (via onFailOpenAdmit) so a degraded-revocation posture is alertable, not
            // just log-visible.
            LOG.log(Level.WARNING, () -> "edge revocation responder UNREACHABLE for client cert '"
                    + leaf.getSubjectX500Principal().getName() + "' under mode " + revocationPolicy.mode()
                    + " -> " + (admit ? "ADMIT (fail-open)" : "REJECT (fail-closed)"));
            if (admit && onFailOpenAdmit != null) {
                onFailOpenAdmit.run();
            }
        } else if (!admit) {
            LOG.log(Level.FINE, () -> "edge client cert REVOKED (mode " + revocationPolicy.mode() + "): "
                    + leaf.getSubjectX500Principal().getName());
        }
        return admit;
    }

    /** Whether mid-connection cert-{@code notAfter} enforcement is on (an expiry may need arming). */
    boolean enforcesCertExpiry() {
        return enforceCertNotAfter;
    }

    /**
     * The wall-clock close deadline for an edge cert connection when {@code notAfter} enforcement is on:
     * {@code notAfter + leeway} for the leaf. Returns {@link AuthState#NO_EXPIRY} (no active expiry) when
     * enforcement is off or the chain is empty.
     */
    long certCloseDeadlineMillis(List<X509Certificate> chain) {
        if (!enforceCertNotAfter || chain == null || chain.isEmpty()) {
            return AuthState.NO_EXPIRY;
        }
        return expiryPolicy.serverCloseDeadlineMillis(chain.get(0).getNotAfter().getTime());
    }
}
