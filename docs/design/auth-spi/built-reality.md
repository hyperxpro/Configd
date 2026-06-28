# Built Authentication & Authorization — ground-truth from source

> **Session:** Auth-SPI design, 2026-06-28. **Status:** design + recommendation only — no production code,
> no provider built, no wiring. Branch `design-auth-spi`, worktree off `main` (`c35d755`).
>
> This document records **what is built today** — the authentication mechanisms, the identity type, and the
> authorization enforcement points — read from source, with `file:line`. The SPI design
> ([`authenticator-spi.md`](authenticator-spi.md)) **generalises this built reality**; the boundary design
> ([`authn-authz-boundary.md`](authn-authz-boundary.md)) keeps the in-core authz engine. Everything here is a
> source fact, not an aspiration. Where the SPI changes something, the change is reconciled against the line
> quoted here.

---

## 0. The one-paragraph summary

Configd already authenticates by **two** mechanisms side by side — **bearer token** on the control-plane
admin HTTP API, and **mTLS client-certificate Subject DN** on the edge fan-out (watch) subscribe path. That
is the **N = 2 case the SPI generalises.** Identity is a **raw `String`** everywhere (a token-derived name,
or a cert DN, or the literal sentinel `"plaintext"`); there is **no `Principal` type**. The bearer path
already carries **roles** (`AuthResult.Authenticated(principal, roles)`) but **nothing consults them** — the
ACL keys on the principal *id*. Authorization (`AclService`, longest-match per-prefix) is enforced **only on
the control-plane API**; the **edge has authenticated identity but no authorization** (the cert DN is used
for slow-consumer admission, not access control). The asymmetry — identity on both planes, authz on one —
is exactly what the namespace access-control design closes and what the `Principal` seam must carry
consistently to both planes.

---

## 1. Authentication — the built N = 2

### 1.1 Bearer token (control-plane admin HTTP API)

The interceptor type, in `configd-control-plane-api`:

- **`AuthInterceptor`** — `configd-control-plane-api/src/main/java/io/configd/api/AuthInterceptor.java`.
  - The result is a **sealed interface** (`AuthInterceptor.java:18-23`):
    ```java
    public sealed interface AuthResult {
        record Authenticated(String principal, Set<String> roles) implements AuthResult {}
        record Denied(String reason) implements AuthResult {}
    }
    ```
  - The validation strategy is a **functional interface** `TokenValidator` — `AuthResult validate(String token)`
    (`AuthInterceptor.java:28-31`). This is the **latent seam**: the whole of "how do I verify a credential"
    is already a single pluggable function; today exactly one lambda is installed.
  - `authenticate(String token)` (`AuthInterceptor.java:50-55`) returns `Denied("missing auth token")` on a
    null/blank token, else delegates to the validator.

The wiring, in `configd-server`:

- **`ConfigdServer`** installs the one validator (`ConfigdServer.java:712-727`). When `--auth-token` is set:
  ```java
  authInterceptor = new AuthInterceptor(token -> {
      if (java.security.MessageDigest.isEqual(expectedToken.getBytes(UTF_8), token.getBytes(UTF_8))) {
          return new AuthInterceptor.AuthResult.Authenticated("root", Set.of("admin"));  // :720
      }
      return new AuthInterceptor.AuthResult.Denied("invalid token");
  });
  ```
  Two facts the SPI must preserve: the comparison is **constant-time** (`MessageDigest.isEqual`, the F-V7-01
  fix, `ConfigdServer.java:717`) — *never roll a `String.equals` token check*; and on success the principal
  is the **hardcoded** `"root"` with roles `{"admin"}` (`:720`). There is a **single** static identity today.
- The credential is extracted as `Authorization: Bearer <token>` (`AdminApiHandler.bearerToken`,
  `AdminApiHandler.java:420-426`).
- When `--auth-token` is **not** set, `authInterceptor` stays `null`, a four-line `WARNING` banner is printed
  (`ConfigdServer.java:705-711`), and the gate is **open** (auth disabled). The SPI keeps this "auth disabled"
  escape exactly as is; it is distinct from "auth enabled, no credential → 401" (§3.2).

### 1.2 mTLS client-certificate Subject DN (edge fan-out / watch subscribe path)

The edge transport demands a client certificate (`setNeedClientAuth(true)`) and takes the verified
**Subject DN** as the identity. Two transports, identical extraction:

- **JDK `FanOutServer`** — `configd-server/src/main/java/io/configd/server/fanout/FanOutServer.java`.
  `resolveEdgeIdentity(Socket)` (`FanOutServer.java:281-293`):
  ```java
  if (socket instanceof SSLSocket ssl) {
      return ssl.getSession().getPeerPrincipal().getName();   // :287  Subject DN
  }
  return "plaintext";                                          // :292  sentinel (no TLS)
  ```
  `createServerSocket()` sets `setNeedClientAuth(true)` (`FanOutServer.java:306,314`) — mTLS REQUIRED.
- **Netty `NettyFanOutServer`** — `configd-server/src/main/java/io/configd/server/fanout/NettyFanOutServer.java`.
  `resolveCertIdentity(ctx)` (`NettyFanOutServer.java:311-321`): `ssl.engine().getSession().getPeerPrincipal().getName()`,
  returning `null` (fail-closed) if there is no verifiable peer certificate (`:319`).

The identity is **bound** — and the security decision recorded — in `configd-distribution-service`:

- **`FanOutConnectionDriver.bindIdentity`** —
  `configd-distribution-service/src/main/java/io/configd/distribution/fanout/FanOutConnectionDriver.java:159-165`:
  ```java
  private EdgeFrame.Subscribe bindIdentity(EdgeFrame.Subscribe wire) {
      if ("plaintext".equals(edgeIdentity)) {     // no mTLS → wire-supplied edgeId used as-is
          return wire;
      }
      // over mTLS the verified cert principal is AUTHORITATIVE; the wire edgeId is advisory
      return new EdgeFrame.Subscribe(wire.fullStore(), wire.prefixes(), wire.resumeCursor(),
              wire.failoverResumeCursor(), edgeIdentity);
  }
  ```
  The class Javadoc names this *"the review-condition security decision: over mTLS the verified client-cert
  principal is authoritative and the wire `edgeId` is advisory"* (`FanOutConnectionDriver.java:25-29`). The
  `edgeIdentity` is a constructor-injected `String` (`FanOutConnectionDriver.java:55,92`).

### 1.3 What this means for the SPI (the N = 2 generalisation)

| | Control-plane API | Edge subscribe path |
|---|---|---|
| Credential | bearer token (`Authorization: Bearer`) | mTLS client cert |
| Verifier | a `TokenValidator` lambda (constant-time compare) | the TLS stack + `getPeerPrincipal()` |
| Identity produced | `"root"` (hardcoded) | cert Subject DN, or `"plaintext"` |
| Roles | `{"admin"}` (carried, **unused** — §2.3) | **none** |
| Identity type | `String` | `String` (with a magic `"plaintext"` sentinel) |

Two mechanisms, two identity shapes, one raw type. The SPI's job is to make "produce a verified identity"
a **named interface** (`Authenticator`) and the identity a **typed `Principal`** that means the same thing on
both planes — so the namespace authz engine can be fed the same principal at both enforcement points.

---

## 2. Authorization — the built engine and its enforcement points

### 2.1 `AclService` — per-prefix, longest-match, keyed on the principal *id*

- **`AclService`** — `configd-control-plane-api/src/main/java/io/configd/api/AclService.java`.
  - Permission set is `enum Permission { READ, WRITE, ADMIN }` (`AclService.java:23`) — **no `LIST`, no
    `WATCH`, no `DENY`** (the namespace design adds those).
  - Storage is `prefix → (principal → Set<Permission>)` (`AclService.java:27`), keyed on the **`String`
    principal id**.
  - `isAllowed(String principal, String key, Permission)` (`AclService.java:79-102`) consults **only the
    single longest-matching prefix** via `floorKey` + walk-back (`:87-99`) — *"Only the longest matching
    prefix is consulted — shorter prefixes are not considered"* (`AclService.java:71-72`). This is the
    longest-match-only behavior the namespace design supersedes with union + deny
    ([`../namespace-model/access-control.md`](../namespace-model/access-control.md) §4.2; DL-N-08).

### 2.2 Enforcement point — control-plane only

`AdminApiHandler.checkAuth` is the **one** authz enforcement point —
`configd-server/src/main/java/io/configd/server/AdminApiHandler.java:396-418`:

```java
private AuthCheck checkAuth(AdminRequest req, String key, AclService.Permission permission) {
    if (authInterceptor == null) return AuthCheck.ok("-");                       // :397-399 auth disabled → open
    AuthResult authResult = authInterceptor.authenticate(bearerToken(req));      // :401 AUTHENTICATE
    if (authResult instanceof AuthResult.Denied denied)
        return AuthCheck.unauthenticated("authentication required: " + denied.reason());  // :402-404 → 401
    String principal = (authResult instanceof AuthResult.Authenticated authed) ? authed.principal() : "-";
    if (aclService != null && authResult instanceof AuthResult.Authenticated authed) {    // :410 AUTHORIZE
        if (!aclService.isAllowed(authed.principal(), key, permission))
            return AuthCheck.forbidden(authed.principal(), "Access denied: ... '" + key + "'");  // :411-413 → 403
    }
    return AuthCheck.ok(principal);
}
```

Called at every mutating/reading control-plane operation: `handleGet → checkAuth(READ)` (`:193`), `handlePut
→ checkAuth(WRITE)` (`:277`), `handleDelete → checkAuth(WRITE)` (`:302`). The `/metrics` endpoint runs an
**authentication-only** gate (401 on `Denied`, no ACL — `AdminApiHandler.java:161-167`).

### 2.3 The roles are carried but **unused** — confirmed at the call site

`checkAuth` reads `authed.principal()` (`AdminApiHandler.java:407,411`) and **never** `authed.roles()`. The
`roles` set has flowed through `AuthResult.Authenticated` since it was added, but `AclService.isAllowed` keys
on the principal *id* (`AclService.java:79`), not roles. So the **roles → policies** model the namespace
design recommends (DL-N-08 / O-6) is wiring *latent machinery that already exists* — the auth token already
carries roles; only the consumer is missing. The SPI's `Principal.roles()` lands exactly where
`Authenticated.roles()` already is.

### 2.4 The edge has identity but **no authorization** — the asymmetry

On the edge subscribe path the bound cert identity is used **only** for **C4 slow-consumer admission** —
`FanOutConnectionDriver.java:131-132`:
```java
SlowConsumerGovernor.Admission admission = governor.admit(bound.edgeId(), clock.currentTimeMillis());
```
`governor.admit(...)` is quarantine/health policy (a reconnect-storm cannot dodge it by re-dialing), **not**
access control. There is **no `AclService` consultation on the edge** — a verified edge may subscribe to any
prefix or the full store. This is the gap the namespace watch-authz contract closes
([`../namespace-model/access-control.md`](../namespace-model/access-control.md) §6, RFC §6): the WATCH
capability must be enforced **at subscription** using the **same replicated policy** as the control plane.
That enforcement needs a **`Principal`** at the edge — which today is only a raw DN `String`. **The SPI is
the missing piece that turns the edge's cert DN into the same `Principal` the authz engine evaluates.**

---

## 3. The 401-vs-403 taxonomy (built — the SPI must not blur it)

`AdminApiHandler` already separates **authentication failure** from **authorization failure**, and the SPI
keeps the split exactly:

- `enum AuthDecision { OK, UNAUTHENTICATED, FORBIDDEN }` (`AdminApiHandler.java:361`).
- `authDenial` (`AdminApiHandler.java:379-389`): `UNAUTHENTICATED → 401` + `WWW-Authenticate: Bearer`
  (RFC 7235 §3.1); `FORBIDDEN → 403`.
- A `401` **never echoes the credential** (`AdminApiHandler.java:160,403` — *"Never echo the token"*).
- Auth **failures** are audited (`AdminApiHandler.java:197-198,279-280,304-305`); successful **reads** are
  not audited per-event (a DoS concern, `:195-196`).

`UNAUTHENTICATED` is the **authenticator's** verdict (no/invalid credential); `FORBIDDEN` is the **authz
engine's** verdict (authenticated, capability not granted). This is precisely the seam: the `Authenticator`
owns the 401 side; the in-core authz engine owns the 403 side. RFC [`§3`](../../rfc/driver-protocol/03-authentication.md)
restates this and composes it with RFC §1 §7.

---

## 4. Module layout and the selection precedent

| Module | Auth-relevant contents |
|---|---|
| **`configd-control-plane-api`** | `AuthInterceptor` (+ `AuthResult`/`TokenValidator`), `AclService` — the auth/authz **types** |
| **`configd-server`** | `AdminApiHandler` (control-plane enforcement), `ConfigdServer` (wiring), `fanout/FanOutServer` + `fanout/NettyFanOutServer` (edge cert extraction) |
| **`configd-distribution-service`** | `fanout/FanOutConnectionDriver` (edge identity binding, C4 admission) |
| **`configd-netty`** | `NettyTransport` — the **`select()` fail-loud precedent** the SPI mirrors |

- **No existing `ServiceLoader` usage** in the codebase (confirmed in the KMS-SPI research,
  [`../../research/kms-spi/decision-log.md`](../../research/kms-spi/decision-log.md) D-KMS-6). The SPI adds the
  same small, idiomatic-adjacent `ServiceLoader` pattern the KMS-SPI introduces — and adds it **once**, shared.
- **The selection precedent** the SPI reproduces verbatim: `NettyTransport.select()`
  (`configd-netty/src/main/java/io/configd/netty/NettyTransport.java:82`) selects a pluggable tier by a
  system-property name and, when a forced tier is unavailable, **fails loud rather than downgrading**
  (`NettyTransport.java:128-131`):
  > *"Refusing to silently downgrade — unset … to auto-select, or pick an available tier."*

  The KMS-SPI already mirrored this for provider selection (`KmsProviders.select`,
  [`../../research/kms-spi/kms-provider-spi.md`](../../research/kms-spi/kms-provider-spi.md) §8). The auth SPI
  mirrors it a third time for **authenticator** selection: naming an authenticator whose module is absent is a
  **startup error**, never a silent fall-through to a weaker mechanism (`authenticator-spi.md` §6).

---

## 5. What is *not* built (so the design states it honestly)

- **No `Principal` type.** Identity is a raw `String` (a name, a DN, or `"plaintext"`).
- **No second authenticator beyond the two.** OIDC/JWT, LDAP, K8s token-review, cloud-IAM — none exist.
- **No roles consumer.** Roles are carried (`Authenticated.roles`) and dropped (§2.3).
- **No edge authorization.** The cert DN gates C4 admission, not access (§2.4).
- **No `LIST`/`WATCH`/`DENY` capabilities, no union/deny evaluation.** Those are the namespace design (not yet
  wired); `AclService` is `{READ, WRITE, ADMIN}` longest-match.
- **No `ServiceLoader`, no pluggable-authn module layering.** This SPI designs it (no code).

These are the facts the SPI design reconciles with. The next document
([`authenticator-spi.md`](authenticator-spi.md)) generalises §1's N = 2 into the `Authenticator` interface and
the `Principal` seam; [`authn-authz-boundary.md`](authn-authz-boundary.md) keeps §2's engine in-core and makes
the seam normative.
