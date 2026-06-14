# Session 7 — Transport Security: negative-test deliverable + findings

> **Prime directive (charter §2.1):** a control is VERIFIED only by a passing test that performs the
> attack and asserts it is rejected — never by reading config. Everything claimed "rejected" below is
> backed by a named passing test; everything not testable locally is named as a finding for the S7.5
> manifest, never claimed by assertion.

This note records (1) the mTLS negative tests added this session and what each proves, (2) a real
finding surfaced while building the expired-cert test (self-signed-leaf-as-trust-anchor does not
enforce certificate expiry), and (3) the edge `/metrics` plaintext/no-auth exposure (charter §5
AS-5, handoff §1.1) with a recommendation.

---

## 1. mTLS negative tests added (the deliverable)

mTLS itself was already implemented well (TLSv1.3-only, `setNeedClientAuth(true)` on both planes,
`"HTTPS"` endpoint identification on both clients). These tests close the *attack-coverage* gaps —
each performs the attack and asserts rejection, tolerant of TLS-1.3 timing (rejection may surface at
first I/O, mirroring the existing `assertConnectionRejected` / "never subscribed" discipline).

| Attack | Control plane (Raft) | Data plane (edge fan-out) |
| --- | --- | --- |
| **Plaintext connection** | `RaftTransportMtlsAttackTest.plaintextFrameIsNeverDecodedAsAPeerMessage` | `FanOutServerMtlsAttackTest.plaintextSubscribeIsNeverAcknowledged` |
| **Expired client cert** | `RaftTransportMtlsAttackTest.expiredClientCertificateIsRejected` | `FanOutServerMtlsAttackTest.expiredClientCertificateIsRejected` |
| **Wrong SAN / identity** | *already covered:* `TcpRaftTransportTest.find0051_clientHandshakeRejectsCertWithWrongHostname` | `EdgeTransportSanMismatchTest.serverCertWithWrongSanIsRejectedByTheClient` (new) |
| **TLS version downgrade (TLSv1.2)** | `RaftTransportMtlsAttackTest.tlsV12OnlyClientIsRejectedByTheTlsV13OnlyServer` | `FanOutServerMtlsAttackTest.tlsV12OnlyClientIsRejectedByTheTlsV13OnlyServer` |

What each proves (observed server-side rejection reasons, captured in surefire logs):

- **Plaintext** — a plain `Socket` writing a syntactically-valid Raft wire frame / edge `SUBSCRIBE`
  to the TLS-only port is treated as a malformed TLS record (`Unsupported or unrecognized SSL
  message`); the Raft inbound handler never fires, and the edge `SUBSCRIBE` never receives a
  `SUBSCRIBE_OK` (the server returns a TLS alert, which does not decode to an edge frame).
- **Expired client cert** — rejected with `PKIX path validation failed: ... validity check failed`.
  See §2: this is only a meaningful test because the expired client is a **CA-signed end-entity**.
- **Wrong SAN** — the client's `"HTTPS"` endpoint identification (F-0051) refuses a cert (even one
  signed by a trusted anchor) whose SAN does not cover the connect host
  (`No subject alternative names matching ... found`). The Raft half already existed (server SAN =
  `localhost`, client targets `127.0.0.2`); the new edge half is the data-plane analogue (server SAN
  = `other-host.invalid`, edge connects to `127.0.0.1`, server cert trusted).
- **Version downgrade** — a client offering ONLY TLSv1.2 against the TLSv1.3-only server fails with
  `The client supported protocol versions [TLSv1.2] are not accepted by server preferences
  [TLSv1.3]`. This is the charter §5 cipher/version-policy assertion: nothing downgrades below
  TLSv1.3.

All keytool fixtures are hoisted into once-per-class `@BeforeAll` (RR-094 discipline); every socket
op is bounded; no test can hang.

### Gate-7 mTLS step

Add these three new classes to gate-7's mTLS step (the existing `FanOutServerMtlsTest`,
`EdgeTransportMtlsTest`, `TcpRaftTransportTest`, `TlsManagerTest` remain):

- `io.configd.transport.RaftTransportMtlsAttackTest` (configd-transport)
- `io.configd.server.fanout.FanOutServerMtlsAttackTest` (configd-server)
- `io.configd.edge.node.EdgeTransportSanMismatchTest` (configd-edge-node)

---

## 2. Finding F-S7-TLS-1 — self-signed leaf as trust anchor does not enforce certificate expiry

**Severity:** Low-Medium. **Disposition:** documented finding + S7.5 manifest item (compensating
control already in place). **Not** a code fix this session.

**What.** Configd's production trust model (`deploy/compose/setup-secrets.sh`) and every existing
test fixture import each peer's **self-signed leaf certificate directly as a trust anchor**
(EC secp256r1, cross-imported; there is no CA hierarchy). Under RFC 5280 §6.1, certificate path
validation treats a trust anchor as an *input* and does **not** check the anchor's own validity
period. Consequently, when a peer presents a certificate that *is* the trust anchor (exact match),
JSSE's `PKIXValidator` never evaluates its `notAfter` — **an expired self-signed leaf is accepted.**

**Evidence (empirical, not theoretical).** The first draft of the expired-client test used the
leaf-as-anchor model (expired self-signed leaf imported into the server trust store). It **failed**:
the server accepted the expired client and delivered a frame (`inboundCount == 1`). Re-running with a
**CA-signed end-entity** (CA in the trust store, leaf signed with `-startdate -2d -validity 1`) the
server correctly rejected it (`validity check failed`). The shipped tests therefore use the CA model,
which proves our TLS stack does not *disable* expiry validation — but the production leaf-as-anchor
configuration does not benefit from it.

**Why this is not a high-severity hole.** Exploiting it requires the attacker to already hold a valid
private key + leaf that *was* a legitimate, currently-trusted peer identity (i.e. a decommissioned or
rotated-out node). It does not admit an arbitrary attacker. The existing compensating control is
**short-lived certs**: `setup-secrets.sh` issues `-validity 30` and the operational model is
re-running secret generation on rotation. Still, "expired credential is honored" violates the
intuitive guarantee and should be closed before production.

**Recommendation (S7.5 / S8).** Move to a real (even single-level) CA so leaves are validated as
end-entities and `notAfter` is enforced; OR add an explicit leaf-expiry check in a custom
`X509TrustManager` wrapper if the self-signed model is retained. Either is out of scope for an S7
negative-test pass (it changes the deployment cert topology + `setup-secrets.sh` + fixtures). Logged
for the S8 go/no-go.

---

## 3. Finding F-S7-TLS-2 — edge `/metrics` (and read API) served plaintext, no auth, wildcard bind

**Severity:** Low (confidentiality / reconnaissance). **Disposition:** documented finding + concrete
recommendation; a cheap optional guard is noted but **not** applied without lead sign-off (it is a
behavior change). Maps to threat-model **AS-5** (operational telemetry → reconnaissance) and the
handoff §1.1 item.

**What (confirmed by reading the code).** `EdgeHttpServer` is a plaintext JDK `HttpServer`:

- `HttpServer.create(new InetSocketAddress(port), 0)` — the `InetSocketAddress(int)` constructor binds
  the **wildcard** address (all interfaces / `0.0.0.0`), not loopback.
- `GET /metrics` calls `exporter.export()` and returns `200` with the full Prometheus exposition —
  **no authentication, no TLS** (`EdgeHttpServer.handleMetrics`).

**Contrast with the control plane.** The control-plane `/metrics` was hardened under **F-0055**:
`HttpApiServer.MetricsHandler` requires a bearer token when auth is configured and returns `401` +
`WWW-Authenticate: Bearer` otherwise. The edge has no equivalent — the two planes diverge on metrics
exposure.

**Recon risk.** The edge Prometheus surface leaks operational structure to anyone who can reach the
port: staleness state, reconnect counts, subscribed-prefix activity, read/refusal rates, JVM/runtime
internals, build/version labels. None is a secret payload (config values are not in `/metrics`), but
together they are a reconnaissance map of fleet health, topology, and version (aiding targeting of a
known-CVE JVM/version, or timing an attack to a degraded/disconnected window). Note the edge **read
API** `GET /v1/config/{key}` is *also* plaintext + wildcard, but that is the intended client-facing
read surface (clients read config there); the security delta the charter asks about is `/metrics`.

**Recommendation.** In priority order:

1. **Network segmentation (preferred, infra-level, no code):** bind/scrape `/metrics` only from a
   trusted interface. Add a `NetworkPolicy` (Kubernetes) / firewall rule so only the Prometheus
   scraper and the edge's own loopback can reach the edge API port. This matches the AS-5 control
   "scrape auth / network segmentation" and needs no code change. → **S7.5 infra manifest.**
2. **Bearer-token scrape auth on edge `/metrics`**, mirroring F-0055: reuse the control-plane
   `AuthInterceptor` pattern so the two planes are symmetric. This is a code change (new edge config
   for the token + handler guard + a negative test asserting `401` without the token). Cheap and
   low-risk, but it adds edge config surface and a token-distribution requirement, so it should be a
   deliberate, lead-approved S7.5/S8 item — not slipped into a negative-test pass.

**Optional cheap guard (flagged, NOT applied).** If the team wants a minimal hardening in-session, the
lowest-risk option is a **bind-address** change: bind the edge `HttpServer` to a configurable address
(default loopback, or an explicit interface) instead of the wildcard, with a negative test asserting a
connection from a non-loopback address is refused. That is a one-field config + one-line bind change
plus a test. It is still a behavior change (an operator relying on wildcard exposure would need to set
the new address), so per the charter constraint ("do NOT make a large change without flagging it") it
is recorded here for the lead to choose, not applied.

**Decision (this session):** **documented finding + recommendation**, not a code fix. Rationale: the
charter scopes this workstream to negative tests proving existing mTLS controls; adding edge scrape
auth or a bind-address knob is a new control with config/distribution implications that belongs in a
deliberate seam (S7.5/S8), and segmentation (option 1) is the lead-preferred, code-free mitigation.

---

## 4. What was NOT testable locally (named for S7.5, never claimed by assertion)

- **Certificate revocation / CRL / OCSP.** Out of scope this session; the self-signed-leaf model has
  no revocation infrastructure. A revoked-but-unexpired cert is honored. → S7.5 manifest (pairs with
  F-S7-TLS-1; both argue for a real CA + short-lived certs / revocation).
- **Expiry under the production leaf-as-anchor model.** As established in §2, expiry is structurally
  not enforced there; the passing test proves the *stack* enforces expiry for CA-signed end-entities,
  which is the strongest claim defensible locally. The production-config gap is the F-S7-TLS-1
  finding, not a passing test.
