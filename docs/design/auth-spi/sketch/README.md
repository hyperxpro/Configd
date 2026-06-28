# Auth-SPI sketch — compile-checked design artifact

> **Design artifact (auth-SPI). NOT production code.** A standalone JDK-25 sketch of the load-bearing types so
> the design is concrete and type-level mistakes are caught. Nothing here is wired into the build; no
> authenticator is implemented against a real identity system. It is the normative reference for the signatures
> quoted in [`../authenticator-spi.md`](../authenticator-spi.md).

## What it demonstrates

- **The `Principal` seam** ([`io/configd/authn/Principal.java`](io/configd/authn/Principal.java)) — immutable,
  carries `id + Configd roles + attributes + provenance`, redacts attribute **values**, and has **no field
  that can hold a credential** (RA-3 is structural).
- **The `Credential` abstraction** ([`Credential.java`](io/configd/authn/Credential.java)) — sealed over
  `CertChain / BearerToken / Password / Headers`; the secret-bearing shapes redact in `toString`.
- **The `Authenticator` SPI + typed outcomes** (`Authenticator`, `AuthResult`, `RejectReason`,
  `AuthnUnavailableException`) — including the **checked** unavailable-exception that forces the fail-closed
  decision (RA-1).
- **The two built-in defaults** — `MtlsAuthenticator` (cert identity → `Principal`, injectable extractor for
  SPIFFE) and `BearerTokenAuthenticator` (constant-time compare → the built `("root", {admin})`).
- **One non-trivial provider** — `OidcAuthenticator`, with the JWT/JWKS work behind a local `TokenVerifier`
  seam so it compiles without a JWT dependency while demonstrating the fail-closed control flow and the
  claim → Configd-role mapping.
- **The chain resolution** ([`AuthenticatorChain.java`](io/configd/authn/AuthenticatorChain.java)) —
  credential-type dispatch + first-definitive + fail-closed: `INVALID_CREDENTIAL` stops (401),
  `NOT_THIS_AUTHENTICATOR` continues, `AuthnUnavailableException` stops fail-closed (**never** falls through to
  a weaker authenticator), an unsupported credential type is default-deny.
- **Fail-loud selection** ([`Authenticators.java`](io/configd/authn/Authenticators.java)) — naming an absent
  provider module is a startup error, the verbatim `NettyTransport.select()` / `KmsProviders.select()` posture.

## Build & run

```sh
cd docs/design/auth-spi/sketch
javac -d /tmp/authn-out $(find . -name '*.java')
java -ea -cp /tmp/authn-out SketchSmokeTest
```

Expected: `All 20 design-contract checks passed.` (verified on Corretto/OpenJDK 25).

## What is stubbed (honest scope)

- **No real crypto / JWT / TLS.** `MtlsAuthenticator`'s extractor and `OidcAuthenticator`'s `TokenVerifier` are
  seams; the smoke test injects fakes. The production `TokenVerifier` wraps a vetted JWT library (Nimbus
  JOSE+JWT); the production mTLS extractor reads the verified `SSLSession` (`getPeerPrincipal()` /
  the SAN URI). No primitive is rolled (RA-6).
- **No `ServiceLoader` entries, no provider modules.** The sketch is a single source tree, not a set of Maven
  modules; discovery is exercised by the *absence* of a factory (the fail-loud path).
- **No wiring.** Nothing references `ConfigdServer`, `AdminApiHandler`, or `FanOutConnectionDriver`; the
  recommended wiring is described in [`../authenticator-spi.md`](../authenticator-spi.md) §9.
