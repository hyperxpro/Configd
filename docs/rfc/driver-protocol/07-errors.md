# Configd Driver Protocol RFC — §07: Unified Error & Status Taxonomy

**Status: DRAFT (2026-06-30). Docs-only; normative.** Seventh section of the Configd driver-protocol RFC.
This section is the **single source of truth** for every error and status a driver can observe, across **both
planes**: the **HTTP control-plane status codes** (§04) and the **binary edge streaming `ErrorCode`s** (§02 /
§06). For **each** it states **when** it occurs and the **REQUIRED driver reaction** (retry? don't? re-read?
follow a hint? re-authenticate? re-bootstrap?). The scattered taxonomies in §1 §7 (authorization), §3 §5
(authentication), and §2 §7 (watch errors) **point here**; this section consolidates them.

Every code is **validated against the deployed implementation**: the HTTP codes against
[`AdminApiHandler.java`](../../../configd-server/src/main/java/io/configd/server/AdminApiHandler.java), the
streaming codes against
[`ErrorCode.java`](../../../configd-wire/src/main/java/io/configd/distribution/wire/ErrorCode.java)
(:14–116) and the fan-out close paths. Where this section and a prior RFC claim disagree, **the code wins**.
This section is **normative**; it **composes with**:

- [`04-data-plane.md`](04-data-plane.md) — produces the HTTP codes; §07 is the consolidated reaction table.
- [`05-routing.md`](05-routing.md) — the `503`/`504`/`X-Leader-Hint`/transport-timeout retry semantics.
- [`02-watches.md`](02-watches.md) / [`06-wire-framing.md`](06-wire-framing.md) — the streaming frames
  (`ERROR_CLOSE` `0x09`, `WATCH_CANCELED` `0x0F`) that carry an `ErrorCode` byte (§06 F6-9), and the
  flow-control / cap behaviors (§06 F10).
- [`01-paths-and-access.md`](01-paths-and-access.md) (§7 authz) and [`03-authentication.md`](03-authentication.md)
  (§5 authn) — the `401`/`403` rows, consolidated here (E5).
- [`00-overview.md`](00-overview.md).

Clauses are referenced as **`E<n>-<m>`** (the error-section clause prefix; parallel to §1 `A`, §2 `W`, §3 `AU`,
§4 `D`, §5 `R`, §6 `F`), so the composed RFC has no clashing identifiers.

---

## 1. Conventions, scope, versioning

### 1.1 Requirement keywords

The keywords **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**,
**MAY**, and **OPTIONAL** are to be interpreted as in RFC 2119 / RFC 8174.

### 1.2 Scope and versioning

Configd has **two error surfaces**: the **HTTP** control plane (a numeric status + headers + a plaintext body,
under the `/v1/` version) and the **binary edge** plane (a numeric `ErrorCode` byte in a terminal frame + a
diagnostic message, under the first-frame-pinned wire version). Versioning is inherited from §04 D1 (`/v1/`
path) and §06 F4 (first-frame pin). **The machine-readable signal is the status code / `ErrorCode` byte — and,
for the edge codes, the carrier frame (E3-3) — never the body/message text** (E6). The "driver reaction" column
is **normative**.

---

## 2. HTTP control-plane status codes

**E2-1.** The complete set of statuses the HTTP data plane returns (`AdminApiHandler`), with the required
reaction. (The conditional leadership-transfer control route — `POST /v1/admin/groups/…/transfer-leadership`,
§04 D2-2a — reuses these same status **codes** with route-specific meanings: a `200` is an **asynchronous
transfer-initiated**, not "moved"; a `409` is a **precondition** failure; a hintless `503` is a **timeout with
an unknown outcome**, distinct from the `409`. See §04 D2-2a for that route's per-status contract.)

| Status | When (code site) | Distinguishing headers / body | Driver reaction |
|---|---|---|---|
| **200** | read value found; write/delete committed; health OK; metrics | read: value bytes + `X-Config-Version`; write/delete: body `Committed: seq=<N>` | success; for a write, parse `seq` from the **body** (§04 D4-2) |
| **400** | invalid key/scope/value, empty `PUT` body, invalid `_acl/` policy, validation failed (:404, :337, :322, :627) | plaintext reason | **permanent** — fix the request; **do not** retry unchanged |
| **401** | missing/blank/malformed/invalid credential (authn, :443); `/metrics` bearer gate (:170); **or** (replay guard on) stale/missing `X-Configd-Timestamp` (:594); **or** a configured external authenticator unavailable (forward — §03 AU5-2) | **`WWW-Authenticate: Bearer`** | **(re)authenticate**; under the replay guard send a **fresh** timestamp+nonce (§04 D11-3 / §05 R6-4), not re-auth; a **503**-class auth-unavailable outcome is **retryable** (§03 AU5-2) |
| **403** | authenticated but not authorized (ACL/`DENY`; reserved-prefix without `ADMIN`) (:445) | plaintext reason | **permanently forbidden** for this principal — **do not** retry unchanged |
| **404** | key absent; or unknown path/endpoint (:262, :144) | `Not Found` | a **definite** answer (not a routing failure) — do not retry as routing |
| **405** | wrong method on an endpoint (:192, :153, :160) | `Method Not Allowed` | **permanent** — fix the method |
| **409** | replayed nonce (replay guard) (:598) | `Conflict: replayed request (nonce already seen)` | retry with a **fresh** nonce+timestamp (§04 D11-3 / §05 R6-4), not the same |
| **429** | overloaded (per-principal limit / admission shed) (:411) | **`Retry-After: 1`** | a **pre-commit** reject (definitely **not** committed) — back off `Retry-After`, then retry |
| **503** | not-leader / lost / strong-read fail-closed / linearizable-unavailable / unhealthy (:391, :399, :290, :250, :156) | `X-Leader-Hint` (when leader known); **`X-Fail-Closed: strong-read`** on a strong-read fail-close | see E2-2 (sub-causes); follow hint or back off + retry (§05) |
| **504** | write `Indeterminate` — deadline expired, outcome unknown (:402) | plaintext "safe to retry or re-read"; **no** hint | **outcome UNKNOWN** — the write **MAY commit later**; idempotent-LWW retry-to-definite; a negative re-read is **not** proof; **no** read-modify-write across it (§04 D4-8) — see E7 |
| **(transport)** | a connect refusal, dropped connection, or read/connect **timeout** — **no HTTP status** | n/a | a **pre-handshake connection refusal** is a capacity condition ⇒ **retry/backoff** (§06 F10-2, not a protocol error); a **timeout/drop on a mutation** is **indeterminate** like `504` (E7) |

**E2-2 (the `503` sub-causes — a driver MUST distinguish them by header).** A `503` is overloaded; the headers
disambiguate:

- **`X-Fail-Closed: strong-read`** (+ `X-Leader-Hint` when known) — a strong-read key could not be served
  linearizably; the server **refused to serve a stale value** (§04 D3-5). Reaction: follow the hint / retry;
  **never** assume a stale value.
- **`X-Leader-Hint` present, no `X-Fail-Closed`** — not-leader / lost / ordinary-key linearizable-unavailable.
  Reaction: progress toward the hinted leader (§05 R4-3).
- **no header** — leader unknown (election; the normal N = 1 case) **or** node unhealthy. Reaction: back off +
  retry the same endpoint (§05 R4-2).

> *(The in-core authenticators — static bearer, HTTP Basic — map authentication only to `401`/`403`. A
> `503`-class authentication outcome arises when a **pluggable external authenticator** cannot verify because
> its backend is unreachable — e.g. an **OIDC issuer / JWKS outage** — a fail-closed `AuthResult.Unavailable`
> the driver **SHOULD** treat as retryable (§03 AU5-2). This is the HTTP plane; the **edge** plane collapses the
> same condition into `AUTH_FAIL` (see E4-2).)*

---

## 3. Binary edge streaming `ErrorCode`s (the 13)

**E3-1.** The closed `ErrorCode` taxonomy (`ErrorCode.java` :14–116). The numeric `code()` (**1..13**) is the
`u8` byte on the wire; a driver **MUST** branch on the **numeric code** (never the message, E6) **together with
the carrier frame** (E3-3), because several codes are scope-overloaded:

| Code | Name | When | Scope (carrier) | Driver reaction |
|---|---|---|---|---|
| **1** | `BAD_WIRE_VERSION` | version byte ∉ {0x01,0x02,0x03,0x04}, or wrong **pinned business** version (§06 F4; a `0x04` auth frame is pin-exempt — §06 F6A-4) | connection-fatal (`ERROR_CLOSE`) | a wire-version bug — fix the version pin; don't reconnect with the same mistake |
| **2** | `FRAME_TOO_LARGE` | declared length > 2 MiB (§06 F2) | connection-fatal (`ERROR_CLOSE`) | a producer bug — a conforming driver should never elicit it |
| **3** | `FRAME_CORRUPT` | CRC mismatch, malformed payload, unknown type, **trailing bytes**, bad cursor (§06 F3/F11) | connection-fatal (`ERROR_CLOSE`) | corruption / framing mistake — reconnect once; if persistent, a codec bug |
| **4** | `AUTH_FAIL` | the **401-class** authentication reject at the **connection** boundary: an mTLS identity rejected, **or** (token/basic edge, §06 §6A) a presented **`AUTH`-frame credential rejected** / an edge **client cert rejected or revoked** / a credential over the size caps / a `REFRESH_AUTH` that resolves to a **different identity** | **carrier-dependent** — an **mTLS handshake rejection** is **TLS-layer, NOT framed** (the driver sees a **handshake failure / reset**, not an `ERROR_CLOSE(4)`); a **token/basic `AUTH`-frame reject** is a **framed `ERROR_CLOSE(4)`** on the established TLS connection (`EdgeAuthGateHandler.closePreAuth` / `FanOutServer.admitPreAuth`) | **(re)authenticate** — fix the client cert **or** present a valid token/basic credential; a rejected pre-auth `AUTH` closes the connection, so retry costs a **fresh connection** (do not hot-loop) |
| **5** | `BAD_SUBSCRIBE` | (a) malformed subscription spec/cursor; **or** (b) a per-connection **resource cap** (§06 F10-2) | reject frame | **(a)** permanent — fix the `SUBSCRIBE`/cursor; **(b)** live-watch cap (1024) ⇒ `WATCH_CANCEL` a slot then retry; `watch_id` budget (16384) ⇒ **reconnect** (a fresh connection resets it, §06 F10-1a) |
| **6** | `GAP_UNRECOVERABLE` | the replay source no longer has the requested range (cursor too old / history truncated) | **carrier-dependent** — per-watch (`WATCH_CANCELED`, siblings survive) on the `0x02` plane; connection-fatal (`ERROR_CLOSE`) on the legacy plane (§02 W6-4) | **re-bootstrap from a snapshot** (`with_initial_snapshot`, §02) — the affected watch, or the whole connection on the legacy plane; do **not** keep retrying the same cursor |
| **7** | `DEMOTED_TO_CATCHUP` | session overflow / ack-lag: streaming → catch-up (snapshot) mode | **NON-FATAL notice** (rides `ERROR_CLOSE` `0x09` but does **not** close — E3-2) | **drain your socket promptly and ack (`CURSOR_ACK`) more promptly** (§06 F10-3); ingest the ensuing snapshot; the stream is **not** closed |
| **8** | `QUARANTINED` | subscriber quarantined **or** (escalated) UNHEALTHY — shares this code | session ended; recoverable after cooldown | back off with **your own bounded backoff** (the cooldown is in the diagnostic message only — E6 — **not** a machine field); the cooldown is **identity-stateful (cert DN)** so an early reconnect is **refused** with another `QUARANTINED` (§06 F10-4); then reconnect + re-bootstrap |
| **9** | `SERVER_SHUTDOWN` | orderly **or** transport-level server-side close; **also** the per-watch `WATCH_CANCEL` acknowledgment reason | **carrier-dependent** — `ERROR_CLOSE` ⇒ connection-fatal; `WATCH_CANCELED` acking your `WATCH_CANCEL` ⇒ per-watch (connection + siblings survive) | `ERROR_CLOSE`: **reconnect** (§05); `WATCH_CANCELED`: **expected ack — do NOT reconnect** |
| **10** | `PROTOCOL_VIOLATION` | an unexpected frame for the current session state | connection-fatal (`ERROR_CLOSE`) | a driver **state-machine bug** — fix the frame sequence; reconnect |
| **11** | `NOT_AUTHORIZED` | authenticated but lacks `READ ∧ WATCH` over the watch target (over-broad target, non-root `full_chain_verify`/`FULL`, intersecting `DENY`) — the **403-class** | **per-watch** (`WATCH_CANCELED`; connection survives) | **forbidden** for that target — **do not** retry the same target; request a **narrower** one (§01 §6, §02 W7-5). At **subscription** zero data frames precede it (§02 W7-5); it MAY **also** arrive **mid-stream** as a bounded **revocation** after data has flowed (§02 W7-7) — same reaction (drop the watch) |
| **12** | `STALE_TOPOLOGY` | the resume token's bound `topologyEpoch` (§06 F5-3 / F8) does **not** match the server's current `ShardMap.epoch()` — the whole topology generation the cursor/`SUBSCRIBE` belongs to is **superseded**. Distinct from `GAP_UNRECOVERABLE` (6): that is "data fell off retention, resume from an earlier position"; `STALE_TOPOLOGY` is "the cursor generation is invalid, drop it entirely" (the etcd `ErrCompacted` model). **At v1 static-N (one deploy-time epoch = `1`) it NEVER fires** — a v2-only code | **carrier-dependent** — per-watch (`WATCH_CANCELED`) for a watch; connection-fatal (`ERROR_CLOSE`) for a legacy `SUBSCRIBE` (§06 F5-3) | **drop the cursor entirely and fully re-hydrate from scratch** (a fresh `WATCH_CREATE`/`SUBSCRIBE` with a **from-now** or `with_initial_snapshot` bootstrap) — **do NOT** re-send the stale cursor, and do **not** merely resume from an earlier `S` (that is the `GAP_UNRECOVERABLE` reaction) |
| **13** | `CREDENTIAL_EXPIRED` | (token/basic edge, §06 §6A) the connection's **authenticated credential has aged out**: a static token's server-side TTL elapsed with no `REFRESH_AUTH`; an **OIDC/JWT token's `exp`** was reached (the server closes at `exp + leeway`); an edge **client cert's `notAfter`** was reached under enforcement (a **reconnect** signal — a cert cannot refresh in-band); **or** a `REFRESH_AUTH` presented an **unacceptable / over-cap** fresh credential. Distinct from `AUTH_FAIL` (4): the credential **was** valid and the **session** expired, vs. "never valid" | connection-fatal (framed `ERROR_CLOSE`) | **re-authenticate on a fresh connection** (a token client presents a fresh credential in a new `AUTH`; a cert client **reconnects with its rotated certificate**). It is **not** a codec bug and **not** a permanent forbid — proactively **`REFRESH_AUTH` before expiry** (§03 AU5-6) to avoid it |

**E3-2 (the catch-up ladder — a driver MUST handle, not just close).** `DEMOTED_TO_CATCHUP` (7) is **not** a
close — it rides an `ERROR_CLOSE` (`0x09`) frame but leaves the session **open** (catch-up mode). A slow
consumer **MUST** handle it: ingest the ensuing snapshot, **drain its socket and `CURSOR_ACK` promptly** (§06
F10-3), then resume streaming. Ignoring it escalates to `QUARANTINED` (8), which **does** end the session
(re-bootstrap after the identity cooldown, §06 F10-4). A driver that treats every `ErrorCode` as a fatal close
will needlessly drop a recoverable stream (7) and hot-loop a quarantine (8).

**E3-3 (scope = code + carrier frame — refines "branch on the code").** The numeric code names the **reason**;
the **carrier** names the **scope**. A driver **MUST** combine them:

- **`ERROR_CLOSE` (`0x09`)** is **connection-scope** and terminal for every code **except**
  `DEMOTED_TO_CATCHUP` (7), the sole non-fatal code that rides it (E3-2). It also carries `CREDENTIAL_EXPIRED`
  (13) and, on a **token/basic** edge, `AUTH_FAIL` (4) — both connection-fatal framed closes (§06 §6A).
- **`WATCH_CANCELED` (`0x0F`)** is **per-watch-scope** (the connection and sibling watches survive); it carries
  `NOT_AUTHORIZED` (11), `GAP_UNRECOVERABLE` (6) on the `0x02` plane, `STALE_TOPOLOGY` (12) for a watch, and
  `SERVER_SHUTDOWN` (9) as a `WATCH_CANCEL` ack.
- **`STALE_TOPOLOGY` (12)** is **carrier-dependent** like `GAP_UNRECOVERABLE`: per-watch (`WATCH_CANCELED`) for
  a watch, connection-fatal (`ERROR_CLOSE`) for a legacy `SUBSCRIBE`. It is a **v2-only** code (never emitted at
  v1 static-N).
- **`AUTH_FAIL` (4)** is **carrier-dependent**: an **mTLS handshake rejection** is **not framed** (a TLS-layer
  failure/reset — E3-1 row 4), whereas a **token/basic `AUTH`-frame reject** is a **framed `ERROR_CLOSE(4)`** on
  the established connection (§06 §6A). Both mean "(re)authenticate".
- **`CREDENTIAL_EXPIRED` (13)** is **always a framed `ERROR_CLOSE`** and connection-fatal — an aged-out session,
  distinct from `AUTH_FAIL` (E3-1 row 13). Only ever seen on a token/basic edge (an mTLS-only edge with no
  cert-`notAfter` enforcement never emits it).

So a pure code-byte switch is insufficient for codes **4, 6, 7, 9, 11, 12**; a driver **MUST** key its reaction
on **(code, carrier frame)**.

---

## 4. The 401-vs-403 split is the same on both planes

**E4-1.** Authentication vs authorization is one logical split, surfaced on both planes:

| Logical failure | HTTP | Streaming (edge) |
|---|---|---|
| **Authentication** (no/invalid credential) | **401** + `WWW-Authenticate: Bearer` | **`AUTH_FAIL`** (4) — an **mTLS handshake rejection** (not framed), **or** a **framed `ERROR_CLOSE(4)`** for a rejected token/basic `AUTH` frame (E3-1 / §06 §6A) |
| **Session expired** (credential aged out — re-authenticate) | **401** (re-present the credential) | **`CREDENTIAL_EXPIRED`** (13) — a framed `ERROR_CLOSE` on a token/basic edge; re-auth on a fresh connection (cert ⇒ reconnect rotated) (E3-1 / §03 AU5-6) |
| **Authorization** (authenticated, not permitted) | **403** | **`NOT_AUTHORIZED`** (11) — a `WATCH_CANCELED` per-watch reject |
| malformed request / bad input | **400** | **`BAD_SUBSCRIBE`** (5, malformed) / `FRAME_CORRUPT` (3) |

A driver **MUST** map both planes to the same logical reactions: a `401`/`AUTH_FAIL` ⇒ **(re)authenticate**; a
`403`/`NOT_AUTHORIZED` ⇒ **permanently forbidden, do not retry unchanged**. (This consolidates §01 §7, §03 §5,
and §02 §7 — those sections point here, E5.)

**E4-2 (the edge has no retryable auth code — `AUTH_FAIL` absorbs "authenticator unavailable").** The two planes
are **asymmetric** on the *authenticator-unavailable* condition (a configured external authenticator — e.g. OIDC
— cannot verify because its backend is unreachable, a fail-closed `AuthResult.Unavailable`). On the **HTTP**
plane it is a **`503`/`401`-class**, retryable outcome (E2-1, §03 AU5-2). On the **edge** plane the streaming
taxonomy is **frozen at 13 codes** — there is **no** retryable auth `ErrorCode` — so the server surfaces it on
the wire as **`AUTH_FAIL` (4)** (`EdgeAuthGateHandler`: an `Unavailable` closes `AUTH_FAIL`, metered server-side
as a distinct `AUTH_UNAVAILABLE` series; the diagnostic `message` may read "temporarily unavailable", but the
**machine signal is `AUTH_FAIL`** — branch on the code, not the message, E6). **Consequence for a driver:** an
edge `AUTH_FAIL` is **not** provably permanent — it **may** be a transient issuer outage wearing the same code.
A driver **MUST NOT** hot-loop it, but it **SHOULD NOT** treat it as terminal either: recover with a **bounded
reconnect-with-backoff**, re-presenting the **same** (valid) credential on a **fresh** connection — this both
respects "a rejected pre-auth `AUTH` costs a fresh connection" (E3-1 code 4) and rides out a transient
authenticator outage. A genuinely invalid credential is exhausted by the bounded attempt ceiling (it never
succeeds); a transient outage recovers when the issuer does. (Adding a distinct retryable edge auth code is a
named forward extension — it would be a new `ErrorCode` value, a wire-taxonomy change; v1 deliberately keeps the
13-code set frozen and absorbs the condition into `AUTH_FAIL`.)

---

## 5. This section is the single source of truth (the fold-in)

**E5-1.** The error taxonomies previously stated in **§01 §7** (authorization `401`/`403`), **§03 §5**
(authentication `401`, plus the **forward** `503`-class "authenticator unavailable" of a pluggable external
authenticator — §03 AU5-2, noted in E2-1 though the deployed in-core auth emits only `401`/`403`), and **§02
§7** (watch streaming rejects) are **consolidated here**. Those sections retain their domain-specific framing
but **reference §07** as the cross-section, single-source table. A driver implementing error handling
**SHOULD** implement it once, from §07, and reuse it across the unary and streaming surfaces.

---

## 6. The body/message is diagnostic — the code (and carrier) are authoritative

**E6-1.** The **machine-readable** signal is the **HTTP status code** (+ headers) or the **`ErrorCode` byte +
its carrier frame** (E3-3) — **never** the response body or the `ERROR_CLOSE`/`WATCH_CANCELED` message. As §04
D2-5a establishes: HTTP error bodies are **plaintext under a misleading `application/json`** (only `/health/*`
is real JSON), **may echo attacker-influenced input** (your key/value), and are **not** escaped — a driver
**MUST NOT** `JSON.parse` them, machine-branch on them, or render them as markup. The streaming
`ERROR_CLOSE`/`WATCH_CANCELED` `message` is likewise **untrusted, human-diagnostic only** (§06 F6-9: it may
carry control/escape bytes — sanitize before logging). A driver **MUST** branch on the code (+ carrier); it
**MAY** log a sanitized body/message.

---

## 7. Retry classification (at a glance)

**E7-1.** Every observable outcome falls into one of these classes:

- **Retry (transient / backoff):** `503` (follow hint or back off — §05), `429` (after `Retry-After`),
  `DEMOTED_TO_CATCHUP` (7, handle catch-up — a mode the driver continues in, §06 F10-3), `QUARANTINED` (8,
  after the identity cooldown), `SERVER_SHUTDOWN` (9, in `ERROR_CLOSE` ⇒ reconnect), a one-shot reconnect on
  `FRAME_CORRUPT` (3), and a **pre-handshake connection refusal** (capacity ⇒ retry/backoff, §06 F10-2).
- **Indeterminate (idempotent-LWW retry-to-definite; NO read-modify-write across it — §04 D4-8):** **`504`**,
  **a transport timeout / dropped connection on a mutation**, and **any other `5xx` (other than `503`/`504`,
  which have their own buckets) on a mutation** (§05 R6-1).
  The write **MAY** have committed; a negative re-read is **not** proof.
- **Retry only after modifying the request / re-authenticating:** **`401`** (until the credential changes;
  `AUTH_FAIL` (4) likewise needs a fixed cert **or** a valid token/basic credential), **`CREDENTIAL_EXPIRED`
  (13)** (the session aged out — re-authenticate on a **fresh** connection: a token client presents a fresh
  credential, a cert client reconnects with its rotated cert; proactively `REFRESH_AUTH` before expiry to avoid
  it — §03 AU5-6), and **`409`** (resend with a **fresh** timestamp+nonce — §04 D11-3 / §05 R6-4).
- **Recover by a watch/connection action (streaming):** `BAD_SUBSCRIBE` (5) **resource-cap** ⇒ `WATCH_CANCEL`
  (live-watch cap) or **reconnect** (`watch_id` budget); `GAP_UNRECOVERABLE` (6) ⇒ **re-bootstrap from
  snapshot** (the watch, or the connection on the legacy plane); `STALE_TOPOLOGY` (12) ⇒ **drop the cursor and
  fully re-hydrate from scratch** (never re-send the stale cursor — a v2-only code, carrier-dependent scope).
- **Terminal — do NOT retry unchanged:** `400`, `403`, `404`, `405`, `BAD_SUBSCRIBE` (5, **malformed** spec),
  `NOT_AUTHORIZED` (11, narrow the target), `PROTOCOL_VIOLATION` (10, fix the bug), `BAD_WIRE_VERSION` (1),
  `FRAME_TOO_LARGE` (2).

---

## 8. Summary of normative requirements (driver checklist)

- [ ] Branch on the **status code**, or the **`ErrorCode` byte + its carrier frame** (E3-3) — **never** the
      body/message (plaintext, may echo input, not escaped; sanitize before logging) (E6).
- [ ] HTTP: `400`/`403`/`404`/`405` don't retry; `401` ⇒ (re)authenticate; `409` ⇒ fresh nonce; `429` ⇒ honor
      `Retry-After`; `503` ⇒ disambiguate by `X-Fail-Closed`/`X-Leader-Hint` and follow §05; **`504` / a
      mutation timeout / other mutation `5xx` ⇒ indeterminate, retry-to-definite, no RMW** (E2, E7).
- [ ] Treat a **pre-handshake connection refusal** as a capacity condition (retry/backoff), **not** a protocol
      error (§06 F10-2).
- [ ] Streaming: handle all **13** `ErrorCode`s with **scope = (code, carrier)** — the **catch-up ladder**
      `DEMOTED_TO_CATCHUP` (7, non-fatal on `0x09` — keep streaming, ack/drain promptly) → `QUARANTINED` (8,
      own backoff + identity cooldown); `GAP_UNRECOVERABLE` (6, re-bootstrap from snapshot, carrier-dependent
      scope); `STALE_TOPOLOGY` (12, v2-only — drop the cursor and fully re-hydrate, carrier-dependent scope);
      `BAD_SUBSCRIBE` (5) resource-cap ⇒ `WATCH_CANCEL`/reconnect; `SERVER_SHUTDOWN` (9) in
      `WATCH_CANCELED` = expected cancel-ack, don't reconnect; `AUTH_FAIL` (4) = an mTLS handshake failure
      **or** a framed token/basic `AUTH` reject; **`CREDENTIAL_EXPIRED` (13) = a framed session-aged-out close
      on a token/basic edge ⇒ re-authenticate on a fresh connection** (§06 §6A / §03 AU5-6).
- [ ] Treat the **401/403 split identically on both planes** — `AUTH_FAIL`(4)/`401` ⇒ re-auth;
      `NOT_AUTHORIZED`(11)/`403` ⇒ forbidden, narrow the target (which MAY also arrive **mid-stream** as a
      revocation — §02 W7-7) (E4).
- [ ] Implement error handling **once** (§07) and reuse it across the unary and streaming surfaces (E5).
