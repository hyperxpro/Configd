import io.configd.authn.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Behavioural smoke test for the auth-SPI design sketch: demonstrates the {@link Principal} seam (redacted,
 * carries roles, no credential field), the {@link Credential} redaction, the two built-in authenticators, the
 * chain RESOLUTION (type dispatch; INVALID stops; NOT_THIS continues; UNAVAILABLE fails closed — never falls
 * through), and the fail-loud selection. No real crypto/JWT is exercised. Not a unit test — a design-validation
 * probe (mirrors the KMS-SPI {@code SketchSmokeTest}).
 */
public class SketchSmokeTest {
    static int checks = 0;
    static void check(String what, boolean ok) {
        checks++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        // --- Principal: carries roles, redacts attribute VALUES, immutable, validates id ---
        Principal p = new Principal("CN=svc,O=acme", Set.of("tenant-1-rw"), Map.of("email", "svc@acme.example"), "oidc");
        check("Principal.toString shows id/roles/provenance/attr-keys",
                p.toString().contains("CN=svc,O=acme") && p.toString().contains("tenant-1-rw")
                        && p.toString().contains("via=oidc") && p.toString().contains("email"));
        check("Principal.toString redacts attribute VALUES", !p.toString().contains("svc@acme.example"));
        boolean immutable = false;
        try { p.roles().add("x"); } catch (UnsupportedOperationException e) { immutable = true; }
        check("Principal.roles is immutable", immutable);
        boolean blankRejected = false;
        try { new Principal(" ", Set.of(), Map.of(), "mtls"); } catch (IllegalArgumentException e) { blankRejected = true; }
        check("Principal rejects a blank id", blankRejected);

        // --- Credential: secret-bearing shapes redact (RA-3) ---
        check("BearerToken redacts the token",
                !new Credential.BearerToken("supersecret-jwt").toString().contains("supersecret"));
        check("Password redacts the secret",
                !new Credential.Password("u", "hunter2".toCharArray()).toString().contains("hunter2"));
        check("Headers redacts values, shows keys",
                new Credential.Headers(Map.of("Authorization", "sigv4")).toString().contains("Authorization")
                        && !new Credential.Headers(Map.of("Authorization", "sigv4")).toString().contains("sigv4"));
        check("CertChain.toString shows count, not certificate contents",
                new Credential.CertChain(List.of()).toString().equals("CertChain[0 cert(s)]"));

        // --- MtlsAuthenticator: cert identity → Principal; no identity → fail closed ---
        MtlsAuthenticator mtls = new MtlsAuthenticator(cc -> "CN=svc,O=acme", Map.of("CN=svc,O=acme", Set.of("svc-role")));
        AuthResult mtlsOk = mtls.authenticate(new Credential.CertChain(List.of()));
        check("mtls maps cert identity → Principal(via=mtls, mapped roles)",
                mtlsOk instanceof AuthResult.Authenticated a
                        && a.principal().id().equals("CN=svc,O=acme")
                        && a.principal().roles().equals(Set.of("svc-role"))
                        && a.principal().authenticator().equals("mtls"));
        AuthResult mtlsNone = new MtlsAuthenticator().authenticate(new Credential.CertChain(List.of()));
        check("mtls with no usable identity → Rejected(INVALID_CREDENTIAL) (fail closed)",
                mtlsNone instanceof AuthResult.Rejected r && r.reason() == RejectReason.INVALID_CREDENTIAL);

        // --- BearerTokenAuthenticator: constant-time compare → built ("root",{admin}) ---
        BearerTokenAuthenticator bearer = new BearerTokenAuthenticator("s3cr3t", "root", Set.of("admin"));
        check("bearer correct token → Principal(root,{admin})",
                bearer.authenticate(new Credential.BearerToken("s3cr3t")) instanceof AuthResult.Authenticated a
                        && a.principal().id().equals("root") && a.principal().roles().equals(Set.of("admin")));
        check("bearer wrong token → Rejected(INVALID_CREDENTIAL)",
                bearer.authenticate(new Credential.BearerToken("nope")) instanceof AuthResult.Rejected r
                        && r.reason() == RejectReason.INVALID_CREDENTIAL);

        // --- The chain resolution (authenticator-spi.md §5.1) ---
        // fake JWT format "iss|sub|g1,g2"; "iss|JWKSDOWN" → unavailable; "<1 field>" → not-this-issuer dispatch
        OidcAuthenticator.TokenVerifier fakeVerifier = new OidcAuthenticator.TokenVerifier() {
            public String peekIssuer(String jwt) { return jwt.contains("|") ? jwt.split("\\|", -1)[0] : null; }
            public OidcAuthenticator.Claims verify(String jwt, String aud)
                    throws AuthnUnavailableException, OidcAuthenticator.InvalidJwtException {
                String[] f = jwt.split("\\|", -1);
                if (f.length >= 2 && f[1].equals("JWKSDOWN")) throw new AuthnUnavailableException("jwks unreachable");
                if (f.length < 3) throw new OidcAuthenticator.InvalidJwtException("malformed");
                return new OidcAuthenticator.Claims(f[0], f[1], List.of(f[2].split(",")));
            }
        };
        OidcAuthenticator oidc = new OidcAuthenticator("issuer-A", "configd-aud", fakeVerifier, Map.of("g1", "tenant-1-rw"));
        MtlsAuthenticator mtlsStub = new MtlsAuthenticator(cc -> "CN=edge", Map.of());
        AuthenticatorChain chain = new AuthenticatorChain(List.of(mtlsStub, oidc, bearer));   // mtls, oidc, bearer

        // (a) a cert resolves via mtls
        check("chain: CertChain → Authenticated via mtls",
                chain.resolve(new Credential.CertChain(List.of())) instanceof AuthenticatorChain.Resolution.Authenticated a
                        && a.principal().authenticator().equals("mtls"));
        // (b) a valid OIDC JWT (issuer-A) resolves via oidc, groups → Configd roles, id = iss#sub
        check("chain: valid issuer-A JWT → Authenticated via oidc, group→role mapped",
                chain.resolve(new Credential.BearerToken("issuer-A|user-7|g1")) instanceof AuthenticatorChain.Resolution.Authenticated a
                        && a.principal().authenticator().equals("oidc")
                        && a.principal().id().equals("issuer-A#user-7")
                        && a.principal().roles().equals(Set.of("tenant-1-rw")));
        // (c) the static admin token resolves via bearer (oidc declines: NOT_THIS_AUTHENTICATOR → continue)
        check("chain: static admin token → Authenticated via bearer (oidc declined, chain continued)",
                chain.resolve(new Credential.BearerToken("s3cr3t")) instanceof AuthenticatorChain.Resolution.Authenticated a
                        && a.principal().authenticator().equals("bearer"));
        // (d) a JWT for another issuer that ALSO isn't the admin token → 401 (NOT_THIS → bearer INVALID → STOP)
        check("chain: foreign JWT, not the admin token → Unauthenticated (no weaker fall-through to anonymous)",
                chain.resolve(new Credential.BearerToken("issuer-B|user-9|g1")) instanceof AuthenticatorChain.Resolution.Unauthenticated);
        // (e) RA-1: oidc owns issuer-A but JWKS is down → fail closed; MUST NOT fall through to bearer
        AuthenticatorChain.Resolution down = chain.resolve(new Credential.BearerToken("issuer-A|JWKSDOWN"));
        check("chain: oidc unavailable → Unavailable (RA-1 fail-closed, NOT a fall-through to bearer)",
                down instanceof AuthenticatorChain.Resolution.Unavailable u && u.detail().contains("oidc"));
        // (e2) RA-1 backstop: an authenticator faulting with an UNCHECKED exception → Unavailable (fail closed),
        // MUST NOT fall through to the bearer authenticator that would have accepted "s3cr3t".
        Authenticator faulty = new Authenticator() {
            public String type() { return "faulty"; }
            public boolean canAttempt(Credential c) { return c instanceof Credential.BearerToken; }
            public AuthResult authenticate(Credential c) { throw new IllegalStateException("pool exhausted"); }
        };
        AuthenticatorChain faultyChain = new AuthenticatorChain(List.of(faulty, bearer));
        check("chain: an authenticator faulting (unchecked) in authenticate → Unavailable (RA-1, no fall-through)",
                faultyChain.resolve(new Credential.BearerToken("s3cr3t")) instanceof AuthenticatorChain.Resolution.Unavailable);
        // (e3) the guard covers canAttempt too: a dispatch that throws → Unavailable, not a propagated exception
        // and not a fall-through to the bearer that would have accepted "s3cr3t".
        Authenticator faultyDispatch = new Authenticator() {
            public String type() { return "faulty-dispatch"; }
            public boolean canAttempt(Credential c) { throw new RuntimeException("dispatch boom"); }
            public AuthResult authenticate(Credential c) { return new AuthResult.Rejected(RejectReason.NO_CREDENTIAL, "n/a"); }
        };
        check("chain: an authenticator whose canAttempt faults → Unavailable (RA-1 backstop guards dispatch too)",
                new AuthenticatorChain(List.of(faultyDispatch, bearer)).resolve(new Credential.BearerToken("s3cr3t"))
                        instanceof AuthenticatorChain.Resolution.Unavailable);
        // (f) RA-4: a credential type no authenticator handles → 401 default-deny
        AuthenticatorChain bearerOnly = new AuthenticatorChain(List.of(bearer));
        check("chain: unsupported credential type → Unauthenticated (RA-4 default-deny)",
                bearerOnly.resolve(new Credential.CertChain(List.of())) instanceof AuthenticatorChain.Resolution.Unauthenticated);

        // --- Selection: default builds [mtls, bearer]; an absent provider FAILS LOUD (RA-7) ---
        AuthnConfig cfg = key -> key.equals("configd.authn.bearer.token") ? Optional.of("s3cr3t") : Optional.empty();
        check("default selection builds the chain [mtls, bearer]",
                Authenticators.chain(cfg).types().equals(List.of("mtls", "bearer")));
        AuthnConfig forceOidc = key -> key.equals(Authenticators.PROVIDERS_KEY) ? Optional.of("mtls,oidc") : Optional.empty();
        boolean failLoud = false; String msg = "";
        try { Authenticators.chain(forceOidc); } catch (IllegalStateException e) { failLoud = true; msg = e.getMessage(); }
        check("naming an absent provider FAILS LOUD", failLoud);
        check("...and refuses to silently skip it (no silent downgrade)", msg.contains("Refusing to silently skip"));

        System.out.println("\nAll " + checks + " design-contract checks passed.");
    }
}
