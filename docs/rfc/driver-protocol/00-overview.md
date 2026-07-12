# Configd Driver Protocol RFC — §00: Overview, Architecture & Conformance

**Status: DRAFT (2026-06-30). Docs-only; normative where marked.** The **entry point** of the Configd
driver-protocol RFC and its **doc map**. It gives a driver-writer the architecture they need first — the **two
planes**, the **section map**, the **version model**, the **conformance profiles** (what a minimal vs. full
driver implements), and the **load-bearing deployment-security note** — then points at the per-surface
sections for the byte- and clause-level detail.

**The RFC is now stand-alone implementable.** With §00–§07 plus the golden fixtures (§06), a driver-writer can
build a **conforming driver in any language from the doc alone** — no need to read the server source. The
golden vectors (`EdgeFrameGoldenBytes`) are the cross-language wire test vectors; each section's **driver
checklist** is the behavioral conformance list. Every clause is grounded in the deployed implementation; where
this RFC and a prior claim disagreed during authoring, **the code won**.

Clauses are referenced as **`OV<n>-<m>`** (the overview clause prefix; parallel to §1 `A`, §2 `W`, §3 `AU`,
§4 `D`, §5 `R`, §6 `F`, §7 `E`).

---

## 1. Conventions

The keywords **MUST**, **MUST NOT**, **SHOULD**, **MAY**, **REQUIRED**, **OPTIONAL** (etc.) are as in
RFC 2119 / RFC 8174 (each section restates §1.1). This section is mostly **architectural orientation**; its
normative clauses are **chiefly** the **conformance profiles** (OV5), the **deployment-security note** (OV6),
and the **fail-closed-on-unknown** rule (OV7) — though OV2/OV3/OV4 also carry RFC-2119 requirements.

---

## 2. The two planes (read this first)

Configd is a **scope-sharded, Raft-replicated** configuration store. A driver talks to it over **two distinct
planes**, with different transports, auth, and capabilities:

| | **HTTP control plane** | **Binary edge plane** |
|---|---|---|
| Operations | `get` / `put` / `delete` (unary) | `watch` / fan-out / streaming read — **NO writes** |
| Authentication | **bearer / Basic** (`Authorization:`, over TLS) | **mTLS** (client cert) **and/or** a token/basic **`AUTH` frame** (§06 §6A) |
| Transport | HTTP over TLS, the `/v1/` path | length-framed binary over **TLSv1.3** (mTLS; `wantClientAuth` on a token edge) |
| Scope | scope-typed but **GLOBAL-only** in the deployed topology | scope-typed (per-shard) |
| Cursor | write `seq` (body) / read version (`X-Config-Version` header) | the **per-shard cursor vector** |
| Spec | **§04** (data) · **§05** (routing) · **§07** (errors) | **§06** (framing) · **§02** (watches) · **§07** (errors) |
| Auth spec | **§03** (bearer / Basic) | **§03** (mTLS / token `AUTH` frame) |
| Address/authz | **§01** (paths, capabilities) | **§01** (paths, watch-authz) — the **watch (`0x02`) surface only**; the legacy fan-out / streaming-read (`0x01`) is **NOT** per-key authorized (OV6-1) |

**OV2-1 (writes are HTTP-only).** All mutations (`put`/`delete`) happen on the **HTTP** plane. The binary edge
plane is **read/watch/fan-out only** — it carries **no** writes. A driver that needs to write **MUST** use the
HTTP API.

**OV2-2 (one plane or both).** A driver **MAY** implement just one plane: a config-CRUD driver needs only the
HTTP plane; a change-observing (watch) driver needs only the edge plane; a full driver implements both. The
**cursor model is shared** — a driver implementing both **SHOULD** use **one** cursor-vector type across the
unary `seq`/version, `list`, and watch surfaces (§1 A9-1).

**OV2-3 (no writes/auth cross-over).** The HTTP bearer credential and the edge mTLS certificate may map to the
**same principal** (§03 AU6-1), but the planes are not interchangeable: you cannot watch over HTTP, and you
cannot write over the edge.

---

## 3. The section map (table of contents)

| § | Title | Prefix | What a driver gets |
|---|---|---|---|
| **§00** | Overview, Architecture & Conformance | `OV` | this map; the two planes; conformance profiles; the deployment-security note |
| **[§01](01-paths-and-access.md)** | Paths and Access Control | `A` | the `(scope, path)` address, path grammar, the capability/authorization model, the watch-authz contract |
| **[§02](02-watches.md)** | The Watch Protocol | `W` | the watch model, the per-shard cursor vector, the `0x0A`–`0x12` watch frames, delivery/ordering guarantees |
| **[§03](03-authentication.md)** | Authentication | `AU` | how to present a credential (mTLS / bearer / Basic; the token/basic edge `AUTH` frame + expiry/refresh); the `401` boundary; the pluggable-but-stable contract |
| **[§04](04-data-plane.md)** | The Core Data Plane | `D` | `GET`/`PUT`/`DELETE`; the two cursors; consistency / strong-read; `?scope=`; `Content-Type` reality |
| **[§05](05-routing.md)** | Routing, Leader-Following & Topology | `R` | `X-Leader-Hint`; the `NodeId→address` map; leader-following (REQUIRED at N=1); retry/idempotency |
| **[§06](06-wire-framing.md)** | Wire Framing & Transport | `F` | the EdgeFrame envelope + the `0x01`–`0x09` payloads + the `0x04` auth frames (§6A) + nested blobs, byte-for-byte; first-frame pin + auth pin-exemption; TLS; caps |
| **[§07](07-errors.md)** | Unified Error & Status Taxonomy | `E` | every HTTP status + every streaming `ErrorCode`, each with the required driver reaction (single source) |

**OV3-1 (recommended read order).** A driver-writer **SHOULD** read **§00 → §01 → §03** (the shared model and
auth), then the plane(s) they implement (**§04 + §05** for HTTP, **§02 + §06** for the edge), with **§07** open
throughout. The clause-prefix scheme (one letter per section) keeps cross-references unambiguous in the
composed document.

---

## 4. The version model (first-frame pin / `/v1/` path — NOT negotiation)

**OV4-1.** There are **two independent** version mechanisms, and **neither is a negotiation handshake**:

- **HTTP control plane:** versioned **solely by the `/v1/` path prefix** — **no** `Accept`/`Content-Type`
  version negotiation, **no** version header, **no** capabilities exchange (§04 D1). A future revision is a new
  path prefix (`/v2/…`).
- **Binary edge plane:** versioned by a **1-byte version stamp on every frame**. The **business** versions are
  `0x01` (built) / `0x02` (watch) / `0x03` (filtered fan-out), **pinned by the first business frame** of the
  connection (§06 F4); the dedicated **auth-phase `0x04`** version (the `AUTH`/`REFRESH_AUTH` frames, §06 §6A) is
  **version-pin-exempt** and additive. There is **no** hello/capabilities frame and **no** downgrade. A future
  revision bumps the version byte; the auth-phase `0x04` frames are the worked example of an **additive**
  version-byte extension — an mTLS-only client that never sends `0x04` is byte-identical to a client from
  before authentication was added.

**OV4-2 (correcting "negotiation").** Earlier drafts described the version as "negotiated at connection setup."
That is **aspirational and inaccurate** — there is **no** negotiation round-trip on either plane. The HTTP
version is a fixed path; the edge version is **first-frame-wins + pin + fail-closed**. A driver **MUST NOT**
expect to negotiate, downgrade, or discover a version; it **MUST** fail closed on an unrecognized version
(unknown HTTP path ⇒ no `/v1/` match; unknown edge version ⇒ `BAD_WIRE_VERSION`).

---

## 5. Conformance profiles

A driver need not implement everything. Two profiles, plus the full driver:

**OV5-1 (the Minimal CRUD driver — HTTP plane).** Implements:

- **§04** — `GET`/`PUT`/`DELETE`, the body-`seq`/header-version cursors, `?scope=`, the strong-read/`X-Fail-
  Closed` path, the `Content-Type` reality;
- **§05** — `X-Leader-Hint` follow + bounded backoff-retry (**REQUIRED even at N = 1**), the `NodeId→api-port`
  map;
- **§07** — the HTTP status reactions;
- **§03** — bearer authentication; **§01** — path validation + the authorization model (client-side awareness).

It does **not** need the edge plane. A conforming Minimal driver passes the §04/§05/§07 driver checklists.

**OV5-2 (the Watch driver — edge plane).** Implements:

- **§06** — the EdgeFrame envelope + the `0x01`–`0x09` payloads byte-for-byte (matching the golden fixtures),
  the first-frame pin, the TLS profile, the mandatory `CURSOR_ACK` flow-control, the caps;
- **§02** — the watch frames (`0x0A`–`0x12`), the per-shard cursor vector, the delivery/ordering guarantees;
- **§07** — the streaming `ErrorCode` reactions, **including the catch-up ladder** (`DEMOTED_TO_CATCHUP` →
  `QUARANTINED`) and `GAP_UNRECOVERABLE` re-bootstrap;
- **§03** — mTLS; **§01** — the cursor vector + the watch-authorization contract.

A conforming Watch driver reproduces the golden wire bytes and passes the §02/§06/§07 checklists.

**OV5-3 (the Full driver).** Implements both planes and shares **one** cursor-vector type (§1 A9-1).

**OV5-4 (the two forward-compat MUSTs — any profile).** Regardless of profile, a v1 (N = 1) driver **MUST**:

1. be **vector-native** for the cursor — treat the resume/list cursor as a `(gid, S)` vector **even at N = 1**
   (a scalar cursor silently breaks when the cluster shards — §1 A9-1 / §2 W1-1); even the per-key unary
   `seq`/`X-Config-Version` is **per-shard**, not a global scalar comparable across shards (§04 D6-3); and
2. implement **leader-following** — `X-Leader-Hint` follow + retry, **even at N = 1** (a single node `503`s,
   often hintless, during its election — §05 R4).

These are what keep a v1 driver **forward-compatible to a sharded (N > 1) cluster with no code change**.

**OV5-5 (conformance evidence).** The **golden fixtures** (`EdgeFrameGoldenBytes`, §06) are the byte-level
cross-language test vectors for the edge wire; the per-section **driver checklists** (§01–§07) are the
behavioral conformance lists. A driver claiming conformance **SHOULD** test its encoder/decoder against the
goldens and its behavior against the checklists. The **`configd-conformance` suite** (shipped, CI-wired) is the
executable form of these checklists — it exercises a driver against both planes of a real cluster and the
golden vectors; a new-language driver can be validated the same way.

---

## 6. Deployment-security note (load-bearing — a v1 deployment REQUIREMENT)

**OV6-1 (legacy `SUBSCRIBE` has no per-key ACL — segregate watch clients).** The watch-authorization contract
(§01 §6, §02 §7) gates the **`0x02` watch surface** (`WATCH_CREATE`, the multiplex/filter veneer) per key, so
a watch client receives only what it is authorized to read. **But the co-resident legacy `0x01` `SUBSCRIBE`
fan-out surface (§06 F6-1) has NO per-key ACL** — a client that connects and sends a legacy `SUBSCRIBE`
(especially `fullStore`) receives the **whole** change stream, **bypassing the per-key watch authorization**.

Therefore, this is a **v1 deployment REQUIREMENT, not a footnote:** an operator **MUST segregate** the legacy
full-stream fan-out surface from the per-key **watch** surface **at the network/deployment boundary** — a
distinct edge listener (a separate instance, or a fronting network policy) reachable **only** by trusted
full-stream consumers — **or** a watch client can bypass watch authz by speaking legacy `SUBSCRIBE`. The mTLS
layer authenticates *who* connects but **does not scope a certificate to a frame type/surface**: the server
applies **identical** mTLS trust to both surfaces on one `--edge-port`, so **certificate scoping is NOT a
server-enforced control** — distinct certificate populations help **only** insofar as a network policy uses
them to route to a segregated endpoint. The exposure is broad: legacy `SUBSCRIBE` (especially `fullStore`)
streams the **whole keyspace to ANY accepted mTLS identity, regardless of its ACL grants**. (Hardening the
legacy `SUBSCRIBE` surface with the same per-key gate is a named post-v1 item.)

**OV6-2 (the deployment-security model, briefly).** Beyond OV6-1: the edge requires **mTLS** (root-only default
grant; single-scope at N = 1); the HTTP plane requires a **bearer token over TLS**; the reserved `_acl/` /
`_system/` prefixes require `ADMIN` (§04 D3-7). A driver **MUST NOT** assume auth is off even against an
auth-disabled deployment (§03 AU4-3).

---

## 7. What a driver can and cannot rely on (the trust model, briefly)

**OV7-1 (channel security).** Transport security is **mTLS** on the edge and **bearer-over-HTTPS** on the
control plane (§03, §06 F9). A driver **MUST** verify the server (`HTTPS` endpoint identification — §06 F9-4)
and **MUST NOT** downgrade to plaintext in production.

**OV7-2 (the boundaries).** The `NodeId→address` map a driver follows redirects through is a **trust boundary**
(same-trust-domain nodes only — §05 §8); **strong-read** is a **freshness** guarantee, **not** confidentiality
(at-rest confidentiality is a **server-side deployment choice** the driver cannot observe: **at-rest encryption
is available** — opt-in AES-256-GCM, **off by default** — so a driver **MUST NOT** rely on values being
encrypted at rest on the strength of the protocol — §04 D3-5b); the optional **replay guard** is
**passive-replay-only**, not request integrity (§04 D11-3); the **edge hydration snapshot** is
**transport-authenticated (mTLS), not cryptographically signed** — only the incremental delta chain carries
per-delta Ed25519 tamper-evidence, so a driver trusts the snapshot base state on the strength of the
authenticated transport and **MUST NOT** accept a hydration snapshot over an unauthenticated transport (§06
F6-6a). A driver **MUST NOT** over-rely on any of these.

**OV7-3 (fail closed on unknown — normative, all surfaces).** A driver **MUST** fail closed on anything it
does not recognize: an unknown HTTP status / header / `?scope=` value; an unknown edge wire version, frame
type, `ErrorCode`, capability identifier, or trailing frame byte; an unknown authentication mechanism. It
**MUST NOT** treat an unknown as a forward-compatible extension (the wire is fixed-positional and fail-closed —
§06 F11; the one TLV-extensible exception is the nested snapshot trailer, §06 F7-2).

**OV7-4 (the upgrade contract, driver-facing part — normative).** The client-visible format-compatibility
contract has three rules a conforming SDK relies on:

1. **The edge first-frame version pin IS the negotiation.** A driver **proposes** its wire version by stamping
   its **first business frame**; the server **pins** the connection to it (§06 F4). There is **no**
   hello/capabilities exchange — the *client* chooses. A later business frame stamped with a **different**
   version is **`BAD_WIRE_VERSION`**, as is a version the server does not support. The auth-phase `0x04` frames
   are **pin-exempt** and interleave without affecting the business pin (§06 F6A-4).
2. **Fail closed on unknown — never a weaker interpretation.** A conforming SDK **MUST** reject an unknown wire
   **version**, frame **type**, illegal-type-for-version, trailing frame byte, or unknown **`ErrorCode`** as a
   clean, **mapped** rejection (`BAD_WIRE_VERSION` / `FRAME_CORRUPT`, or a driver-side hard error for an unknown
   error-code byte) — **never** a silent misparse and **never** a downgrade to a weaker reading (OV7-3, §06 F11).
3. **The auth surface is additive.** The `AUTH`/`REFRESH_AUTH` frames and `ErrorCode 13 CREDENTIAL_EXPIRED` were
   added on the new `0x04` wire version and a new code value **without touching** any `0x01`/`0x02`/`0x03` byte,
   so an **mTLS-only client that never sends a `0x04` frame is byte-identical to a client from before
   authentication was added**, and an
   older driver that fails closed on the unknown `0x04` / code-13 keeps working.

The **whole-system** format contract — including the **internal** Raft (node↔node) and at-rest formats a driver
never sees — is `docs/architecture/upgrade-capability.md` (contract clauses C0–C9). The
**driver-facing** part is rules 1–3 above, plus §06 F4 / F6A / F11 / §13 and §07.

---

## 8. Status and what is deferred (named, not spec'd)

**OV8-1 (the RFC is implementable now, and a reference driver ships).** §00–§07 are spec-grade and validated
against the deployed server. A conforming **Java** reference client (`configd-client` + `-core`/`-http`/`-edge`)
and a `configd-conformance` suite (CI-wired, both planes) now ship as the worked example; a driver-writer can
build a conforming driver in any language from the doc alone. Additional-language drivers remain **on-demand**
(post-v1) and are **not** on the v1 critical path.

**OV8-2 (named v1 omissions — a driver MUST fail closed on these).** Not in v1, do **not** assume: a **`list`
/ enumeration endpoint** (the semantic model is §1 §4.2; no wire — §04 D9); a **topology / shard-map discovery
endpoint** (the `NodeId→address` map is operator config — §05 R3-2); a **globally-ordered / cross-shard watch**
(watches are per-key/per-shard ordered — no global order, §02 W6-2) and the **disjoint sharded-edge topology**
(edges serving shard subsets, driver-side merge — §02 W9-3); **conditional writes / `If-Match`** and **batch
multi-key writes** (§04 D11-4); a **structured-JSON** response body (§04 D2-5). Each is a **named forward
extension**; a driver that fails closed on the unrecognized (OV7-3) keeps working when they arrive.

> **Now BUILT (no longer omitted):** **multi-shard (N > 1) watches** are v1-delivered by a server-side
> aggregating endpoint (one `FanOutSessionCore` per covered shard, a per-shard `(gid, S)` cursor vector — §02
> W4/W9-3). Ordering stays per-shard, never global. A driver **MUST** be vector-native (OV5-4) to consume them.

> **Now BUILT (no longer omitted):** the **token-bearing edge `AUTH`/`REFRESH_AUTH` frame** is v1 (§03 AU3-3, §06
> §6A) — a certificate-less edge client authenticates by presenting a bearer/basic credential. It is **additive**
> (an mTLS-only client is byte-identical), so an older driver that never sends a `0x04` frame is unaffected.

---

## 9. Conformance checklist (meta)

A conforming driver satisfies the driver checklist of every section in its profile (OV5):

- [ ] **§01** — address/path validation; the union-ALLOW-minus-DENY authz model; the cursor-vector-shared rule.
- [ ] **§03** — present a credential and read the outcome; `401` ⇒ (re)auth, `403` ⇒ forbidden; fail closed on
      unknown mechanisms.
- [ ] **§04** — `GET`/`PUT`/`DELETE` wire contract; body-`seq` vs header-version; plaintext bodies; strong-read
      via the observed header; `list` deferred.
- [ ] **§05** — `X-Leader-Hint` (numeric `NodeId`) follow + bounded backoff; the `NodeId→api-port` map;
      leader-following + vector cursor **at N = 1**; no client sharding.
- [ ] **§06** — the envelope + payloads byte-for-byte vs the goldens; first-frame pin; `u64 < 2^63`; TLS
      profile; mandatory `CURSOR_ACK`; caps; fail-closed forward-compat.
- [ ] **§02** — vector-native, per-key-ordered watches; the watch frames; resume by re-`CREATE` with a cursor.
- [ ] **§07** — every HTTP status and streaming `ErrorCode` reaction; the catch-up ladder; branch on the code,
      never the body.
- [ ] **§00** — segregate watch clients from legacy-`SUBSCRIBE` full-stream clients (OV6-1); fail closed on the
      unknown (OV7-3); be vector-native and leader-following **even at N = 1** (OV5-4).
