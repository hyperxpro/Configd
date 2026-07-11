# Pluggable authentication SPI -- design

Design work from 2026-06-28, docs-only, no production code (the [`sketch/`](sketch/) is a compile-checked
design artifact). This directory designs Configd's pluggable authentication SPI and the normative boundary
to its in-core authorization engine, and captures the model as a driver-protocol RFC section.

## The one idea

Authentication is pluggable; authorization is in-core. Adopters run wildly different identity systems
(mTLS, OIDC/JWT, LDAP, Kubernetes tokens, cloud-IAM), so "who is this caller?" must be a pluggable SPI,
but "may this caller do this?" must stay Configd's own engine, because the consistency guarantees
(policy-as-config, INV-WATCH-READ) depend on one in-core engine evaluating the same replicated policy at
both the control plane and the edge. The seam between the two is a typed `Principal`: the authenticator
produces it, and the in-core engine consumes it. The authenticator never authorizes; the authz engine
never parses a credential.

This mirrors HashiCorp Vault (pluggable auth methods to a fixed policy engine), JAAS (`LoginModule` to
`Subject`/`Principal`), the Servlet model (`<auth-method>` to `getUserPrincipal()`/`isUserInRole()`), and
SPIFFE (SVID to SPIFFE-ID), and reuses the KMS-SPI's SPI/ServiceLoader/fail-closed shape.

## Read in this order

1. [`built-reality.md`](built-reality.md) -- a historical snapshot of what was built at design time (mTLS
   plus bearer, identity as a raw `String`, roles carried-but-unused, the control-plane-only authz / edge-
   no-authz asymmetry), from source with file:line. The SPI generalizes this to N=2 authenticators with no
   regression. Superseded by the auth system that shipped later; see the note at the top of that file.
2. [`prior-art.md`](prior-art.md) -- the four precedents, primary-sourced and verbatim-flagged.
3. [`authenticator-spi.md`](authenticator-spi.md) -- the core deliverable: the `Authenticator` interface,
   the `Principal` seam, the `Credential` abstraction, multi-authenticator resolution plus failure
   semantics, the fail-closed contract (RA-1 through RA-7), the control-plane/edge consistency
   requirement, the two in-core defaults, future providers as optional modules plus ServiceLoader
   fail-loud, and one end-to-end OIDC sketch.
4. [`authn-authz-boundary.md`](authn-authz-boundary.md) -- the `Principal`-only seam made normative: role
   mapping at the authenticator, and INV-WATCH-READ preserved across the pluggable-authn boundary.
5. [`../../rfc/driver-protocol/03-authentication.md`](../../../rfc/driver-protocol/03-authentication.md) --
   the normative RFC section 3: how a driver presents a credential, the connection lifecycle, the
   401-vs-403 taxonomy (composing with section 1), and the stable driver contract regardless of which
   authenticator the server runs.
6. [`decision-log.md`](decision-log.md) -- methodology, the DL-A-* decisions, the operator-confirm items
   (OA-1 through OA-6), scope honesty, and the handoff.
7. [`sketch/`](sketch/) -- the standalone JDK-25 sketch (`Authenticator`/`Principal`/`Credential`/
   resolution plus the two defaults plus an OIDC provider behind a `TokenVerifier` seam) with a 20/20
   behavioral smoke test.

## The load-bearing constraints (do not violate when wiring)

- AuthN is the SPI; AuthZ stays in-core, never a plug-in (the whole point: the `Authenticator` interface
  has no `mayAccess` method).
- The `Principal` is the only thing that crosses the boundary; it never carries the credential.
- Fail closed, never downgrade: an unavailable/erroring authenticator rejects; it does not fall through to
  a weaker one; a forged credential is a hard 401.
- One chain governs both planes, so a `Principal` means the same on the control plane and the edge, the
  property INV-WATCH-READ depends on.
- External identity maps to Configd roles at the authenticator; the authz engine sees only Configd roles.

This directory captured a decided design plus a normative RFC section, the contract the auth wiring
conforms to, before the pluggable authentication system was built. The model composes with the in-core
authorization design ([`../namespace-model/`](../namespace-model/)).
