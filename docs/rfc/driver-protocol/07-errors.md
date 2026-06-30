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
[`ErrorCode.java`](../../../configd-distribution-service/src/main/java/io/configd/distribution/wire/ErrorCode.java)
(:16–67) and the fan-out close paths. Where this section and a prior RFC claim disagree, **the code wins**.
This section is **normative**; it **composes with**:

- [`04-data-plane.md`](04-data-plane.md) — produces the HTTP codes; §07 is the consolidated reaction table.
- [`05-routing.md`](05-routing.md) — the `503`/`504`/`X-Leader-Hint`/transport-timeout retry semantics.
- [`02-watches.md`](02-watches.md) / [`06-wire-framing.md`](06-wire-framing.md) — the streaming frames
  (`ERROR_CLOSE` `0x09`, `WATCH_CANCELED` `0x0F`) that carry an `ErrorCode` byte (§06 F6-9), and the
  flow-control / cap behaviors (§06 F10).
- [`01-paths-and-access.md`](01-paths-and-access.md) (§7 authz) and [`03-authentication.md`](03-authentication.md)
  (§5 authn) — the `401`/`403` rows, consolidated here (E5).
- `00-overview.md` (**planned; not yet written — this arc**).

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
reaction:

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

> *(The deployed control plane maps authentication only to `401`/`403`; a `503`-class authentication outcome is
> a **forward** behavior of a pluggable external authenticator, §03 AU5-2 — listed in the `401` row for
> completeness, not currently emitted by the in-core auth.)*

---

## 3. Binary edge streaming `ErrorCode`s (the 11)

**E3-1.** The closed `ErrorCode` taxonomy (`ErrorCode.java` :16–67). The numeric `code()` (1..11) is the `u8`
byte on the wire; a driver **MUST** branch on the **numeric code** (never the message, E6) **together with the
carrier frame** (E3-3), because several codes are scope-overloaded:

| Code | Name | When | Scope (carrier) | Driver reaction |
|---|---|---|---|---|
| **1** | `BAD_WIRE_VERSION` | version byte ∉ {0x01,0x02}, or wrong **pinned** version (§06 F4) | connection-fatal (`ERROR_CLOSE`) | a wire-version bug — fix the version pin; don't reconnect with the same mistake |
| **2** | `FRAME_TOO_LARGE` | declared length > 2 MiB (§06 F2) | connection-fatal (`ERROR_CLOSE`) | a producer bug — a conforming driver should never elicit it |
| **3** | `FRAME_CORRUPT` | CRC mismatch, malformed payload, unknown type, **trailing bytes**, bad cursor (§06 F3/F11) | connection-fatal (`ERROR_CLOSE`) | corruption / framing mistake — reconnect once; if persistent, a codec bug |
| **4** | `AUTH_FAIL` | mTLS identity rejected at the **connection** boundary (the **401-class**) | **TLS-layer — NOT framed** (server-side metric/close-reason; the driver sees a **TLS handshake failure / reset**, not an `ERROR_CLOSE(4)`) | **(re)authenticate** — check/fix the client cert; do not hot-loop |
| **5** | `BAD_SUBSCRIBE` | (a) malformed subscription spec/cursor; **or** (b) a per-connection **resource cap** (§06 F10-2) | reject frame | **(a)** permanent — fix the `SUBSCRIBE`/cursor; **(b)** live-watch cap (1024) ⇒ `WATCH_CANCEL` a slot then retry; `watch_id` budget (16384) ⇒ **reconnect** (a fresh connection resets it, §06 F10-1a) |
| **6** | `GAP_UNRECOVERABLE` | the replay source no longer has the requested range (cursor too old / history truncated) | **carrier-dependent** — per-watch (`WATCH_CANCELED`, siblings survive) on the `0x02` plane; connection-fatal (`ERROR_CLOSE`) on the legacy plane (§02 W6-4) | **re-bootstrap from a snapshot** (`with_initial_snapshot`, §02) — the affected watch, or the whole connection on the legacy plane; do **not** keep retrying the same cursor |
| **7** | `DEMOTED_TO_CATCHUP` | session overflow / ack-lag: streaming → catch-up (snapshot) mode | **NON-FATAL notice** (rides `ERROR_CLOSE` `0x09` but does **not** close — E3-2) | **drain your socket promptly and ack (`CURSOR_ACK`) more promptly** (§06 F10-3); ingest the ensuing snapshot; the stream is **not** closed |
| **8** | `QUARANTINED` | subscriber quarantined **or** (escalated) UNHEALTHY — shares this code | session ended; recoverable after cooldown | back off with **your own bounded backoff** (the cooldown is in the diagnostic message only — E6 — **not** a machine field); the cooldown is **identity-stateful (cert DN)** so an early reconnect is **refused** with another `QUARANTINED` (§06 F10-4); then reconnect + re-bootstrap |
| **9** | `SERVER_SHUTDOWN` | orderly **or** transport-level server-side close; **also** the per-watch `WATCH_CANCEL` acknowledgment reason | **carrier-dependent** — `ERROR_CLOSE` ⇒ connection-fatal; `WATCH_CANCELED` acking your `WATCH_CANCEL` ⇒ per-watch (connection + siblings survive) | `ERROR_CLOSE`: **reconnect** (§05); `WATCH_CANCELED`: **expected ack — do NOT reconnect** |
| **10** | `PROTOCOL_VIOLATION` | an unexpected frame for the current session state | connection-fatal (`ERROR_CLOSE`) | a driver **state-machine bug** — fix the frame sequence; reconnect |
| **11** | `NOT_AUTHORIZED` | authenticated but lacks `READ ∧ WATCH` over the watch target (over-broad target, non-root `full_chain_verify`/`FULL`, intersecting `DENY`) — the **403-class** | **per-watch** (`WATCH_CANCELED`; connection survives) | **forbidden** for that target — **do not** retry the same target; request a **narrower** one (§01 §6, §02 W7-5). At **subscription** zero data frames precede it (§02 W7-5); it MAY **also** arrive **mid-stream** as a bounded **revocation** after data has flowed (§02 W7-7) — same reaction (drop the watch) |

**E3-2 (the catch-up ladder — a driver MUST handle, not just close).** `DEMOTED_TO_CATCHUP` (7) is **not** a
close — it rides an `ERROR_CLOSE` (`0x09`) frame but leaves the session **open** (catch-up mode). A slow
consumer **MUST** handle it: ingest the ensuing snapshot, **drain its socket and `CURSOR_ACK` promptly** (§06
F10-3), then resume streaming. Ignoring it escalates to `QUARANTINED` (8), which **does** end the session
(re-bootstrap after the identity cooldown, §06 F10-4). A driver that treats every `ErrorCode` as a fatal close
will needlessly drop a recoverable stream (7) and hot-loop a quarantine (8).

**E3-3 (scope = code + carrier frame — refines "branch on the code").** The numeric code names the **reason**;
the **carrier** names the **scope**. A driver **MUST** combine them:

- **`ERROR_CLOSE` (`0x09`)** is **connection-scope** and terminal for every code **except**
  `DEMOTED_TO_CATCHUP` (7), the sole non-fatal code that rides it (E3-2).
- **`WATCH_CANCELED` (`0x0F`)** is **per-watch-scope** (the connection and sibling watches survive); it carries
  `NOT_AUTHORIZED` (11), `GAP_UNRECOVERABLE` (6) on the `0x02` plane, and `SERVER_SHUTDOWN` (9) as a
  `WATCH_CANCEL` ack.
- **`AUTH_FAIL` (4)** is **not framed at all** — it is the TLS-layer handshake rejection (E3-1 row 4).

So a pure code-byte switch is insufficient for codes **4, 6, 7, 9, 11**; a driver **MUST** key its reaction on
**(code, carrier frame)**.

---

## 4. The 401-vs-403 split is the same on both planes

**E4-1.** Authentication vs authorization is one logical split, surfaced on both planes:

| Logical failure | HTTP | Streaming (edge) |
|---|---|---|
| **Authentication** (no/invalid credential) | **401** + `WWW-Authenticate: Bearer` | **`AUTH_FAIL`** (4) — a **TLS handshake rejection**, not a wire frame (E3-1) |
| **Authorization** (authenticated, not permitted) | **403** | **`NOT_AUTHORIZED`** (11) — a `WATCH_CANCELED` per-watch reject |
| malformed request / bad input | **400** | **`BAD_SUBSCRIBE`** (5, malformed) / `FRAME_CORRUPT` (3) |

A driver **MUST** map both planes to the same logical reactions: a `401`/`AUTH_FAIL` ⇒ **(re)authenticate**; a
`403`/`NOT_AUTHORIZED` ⇒ **permanently forbidden, do not retry unchanged**. (This consolidates §01 §7, §03 §5,
and §02 §7 — those sections point here, E5.)

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
- **Retry only after modifying the request:** **`401`** (until the credential changes; `AUTH_FAIL` (4) likewise
  needs a fixed cert) and **`409`** (resend with a **fresh** timestamp+nonce — §04 D11-3 / §05 R6-4).
- **Recover by a watch/connection action (streaming):** `BAD_SUBSCRIBE` (5) **resource-cap** ⇒ `WATCH_CANCEL`
  (live-watch cap) or **reconnect** (`watch_id` budget); `GAP_UNRECOVERABLE` (6) ⇒ **re-bootstrap from
  snapshot** (the watch, or the connection on the legacy plane).
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
- [ ] Streaming: handle all **11** `ErrorCode`s with **scope = (code, carrier)** — the **catch-up ladder**
      `DEMOTED_TO_CATCHUP` (7, non-fatal on `0x09` — keep streaming, ack/drain promptly) → `QUARANTINED` (8,
      own backoff + identity cooldown); `GAP_UNRECOVERABLE` (6, re-bootstrap from snapshot, carrier-dependent
      scope); `BAD_SUBSCRIBE` (5) resource-cap ⇒ `WATCH_CANCEL`/reconnect; `SERVER_SHUTDOWN` (9) in
      `WATCH_CANCELED` = expected cancel-ack, don't reconnect; `AUTH_FAIL` (4) = a TLS handshake failure, not a
      frame (E3).
- [ ] Treat the **401/403 split identically on both planes** — `AUTH_FAIL`(4)/`401` ⇒ re-auth;
      `NOT_AUTHORIZED`(11)/`403` ⇒ forbidden, narrow the target (which MAY also arrive **mid-stream** as a
      revocation — §02 W7-7) (E4).
- [ ] Implement error handling **once** (§07) and reuse it across the unary and streaming surfaces (E5).
