package io.configd.common.auth;

import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;

/**
 * Derives a {@link Principal} from an already-verified mTLS peer certificate - the mechanism the edge
 * fan-out plane already uses (turn a verified client cert into an identity), factored behind the SPI so
 * BOTH the control-plane and the edge produce the SAME {@code Principal} type for the same certificate.
 *
 * <p>It does NOT verify the certificate chain: the {@link Credential.ClientCertificate} it receives MUST be
 * the peer chain the TLS stack already verified under client-cert-required. The identity is the leaf
 * certificate's Subject DN (RFC 2253), matching the edge's {@code getPeerPrincipal().getName()}.
 * A certificate with no usable subject is {@link DenyReason#INVALID_CREDENTIAL} (fail closed). It has no
 * remote backend, so it never returns {@link AuthResult.Unavailable}.
 *
 * <p>Every verified certificate is granted the same configured default roles ({@code configd.auth.mtls.roles},
 * empty by default - identity only, as the edge does today); there is no fine-grained DN-to-role mapping.
 */
public final class MtlsAuthenticator implements Authenticator {

    private final Set<String> defaultRoles;

    public MtlsAuthenticator(Set<String> defaultRoles) {
        this.defaultRoles = Set.copyOf(defaultRoles);
    }

    @Override
    public String type() {
        return "mtls";
    }

    @Override
    public boolean canAttempt(Credential credential) {
        return credential instanceof Credential.ClientCertificate;
    }

    @Override
    public AuthResult authenticate(Credential credential) {
        Credential.ClientCertificate cc = (Credential.ClientCertificate) credential;
        X509Certificate leaf = cc.leaf();
        if (leaf == null) {
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL, "no client certificate presented");
        }
        String dn = leaf.getSubjectX500Principal().getName();
        if (dn == null || dn.isBlank()) {
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL, "client certificate has no subject");
        }
        return new AuthResult.Authenticated(new Principal(dn, defaultRoles, Map.of(), "mtls"));
    }
}
