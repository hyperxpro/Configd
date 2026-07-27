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


public final class EdgeCertGate {

    private static final Logger LOG = Logger.getLogger(EdgeCertGate.class.getName());

    
    static final String CERT_EXPIRED_MESSAGE =
            "client certificate notAfter reached; reconnect with a rotated certificate";

    
    public static final EdgeCertGate OFF =
            new EdgeCertGate(RevocationPolicy.OFF, null, CredentialExpiryPolicy.DEFAULTS, false);

    private final RevocationPolicy revocationPolicy;
    
    private final RevocationChecker checker;
    private final CredentialExpiryPolicy expiryPolicy;
    private final boolean enforceCertNotAfter;
    
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

    
    boolean enforcesCertExpiry() {
        return enforceCertNotAfter;
    }

    
    long certCloseDeadlineMillis(List<X509Certificate> chain) {
        if (!enforceCertNotAfter || chain == null || chain.isEmpty()) {
            return AuthState.NO_EXPIRY;
        }
        return expiryPolicy.serverCloseDeadlineMillis(chain.get(0).getNotAfter().getTime());
    }
}
