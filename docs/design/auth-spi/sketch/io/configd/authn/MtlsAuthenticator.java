package io.configd.authn;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Built-in default #1: mTLS client-certificate identity (built-reality.md §1.2). Zero dependency — the TLS
 * stack has already verified the chain ({@code setNeedClientAuth(true)}); this just extracts the identity and
 * maps it to roles.
 *
 * <p>Design artifact (auth-SPI). NOT production code.
 *
 * <p><b>This authenticator does NO chain validation</b> — it trusts the transport's verification and reads
 * identity off the {@link Credential.CertChain}, which (normatively) is the verified peer chain. It MUST never
 * be the verification point and MUST never be fed an unverified/self-asserted cert (Credential.CertChain doc).
 * To preserve the built <em>intrinsic</em> gate with no regression, production wiring SHOULD extract via the
 * verified {@code SSLSession.getPeerPrincipal()} (which itself throws if the peer was not verified —
 * {@code FanOutServer.java:284-287}) rather than reading a raw cert off an unguarded list.
 *
 * <p>The identity extractor is injectable so a SPIFFE deployment can read the SAN URI instead of the Subject
 * DN (prior-art.md §4.2) — same {@link Principal} out. {@link #SUBJECT_DN} is the built behavior
 * ({@code getPeerPrincipal().getName()}).
 */
public final class MtlsAuthenticator implements Authenticator {

    /** Production extractor: the verified Subject DN of the leaf cert — the built {@code getPeerPrincipal().getName()}. */
    public static final Function<Credential.CertChain, String> SUBJECT_DN = c ->
            c.chain().isEmpty() ? null : c.chain().get(0).getSubjectX500Principal().getName();

    private final Function<Credential.CertChain, String> extractor;
    private final Map<String, Set<String>> dnToRoles;   // DN → Configd roles (optional; empty = identity only)

    public MtlsAuthenticator(Function<Credential.CertChain, String> extractor, Map<String, Set<String>> dnToRoles) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.dnToRoles = Map.copyOf(dnToRoles);
    }

    /** Production wiring: extract the Subject DN, no DN→role map (roles come from the namespace policy by id). */
    public MtlsAuthenticator() {
        this(SUBJECT_DN, Map.of());
    }

    @Override
    public String type() {
        return "mtls";
    }

    @Override
    public boolean canAttempt(Credential credential) {
        return credential instanceof Credential.CertChain;
    }

    @Override
    public AuthResult authenticate(Credential credential) {
        Credential.CertChain cc = (Credential.CertChain) credential;
        String id = extractor.apply(cc);
        if (id == null || id.isBlank()) {
            // mTLS REQUIRED but no usable identity → fail closed (RA-2), do NOT produce an anonymous principal.
            return new AuthResult.Rejected(RejectReason.INVALID_CREDENTIAL, "no usable certificate identity");
        }
        Set<String> roles = dnToRoles.getOrDefault(id, Set.of());
        return new AuthResult.Authenticated(new Principal(id, roles, Map.of(), "mtls"));
    }
}
