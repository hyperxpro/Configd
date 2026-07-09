# Configd Driver-Protocol — Normative-Requirement Catalog

**Purpose.** This is the master enumeration that drives (a) what a Configd reference client must
implement and (b) every case a protocol-conformance suite must exercise. It catalogs **every
normative clause** across the driver-protocol RFC §00–§07 (`docs/rfc/driver-protocol/`), reconciles
the byte- and behavioural claims against the deployed code, and lists every gap / ambiguity /
drift the client build is expected to expose.

**Method.** Every RFC section was read in full. Every byte-level and behavioural claim was
cross-checked against the source of truth:

- Edge wire: `configd-distribution-service/.../wire/{EdgeFrame,EdgeFrameCodec,ErrorCode,FrameType,WatchCursor}.java`
  + golden vectors `.../wire/EdgeFrameGoldenBytes.java`.
- Edge server behaviour: `configd-server/.../fanout/{ByteToEdgeFrameDecoder,EdgeFrameToByteEncoder,EdgeAuthGateHandler}.java`.
- HTTP control plane: `configd-server/.../AdminApiHandler.java` (the transport-agnostic decision core).

**Where RFC and code disagree, the code is truth and the drift is REPORTED (§7 below).**

Legend for the master table:
- **Plane**: HTTP (control) / edge (binary) / both / meta (architectural).
- **Holder**: who the clause binds — **C**=client/driver, **S**=server, **both**.
- **Testable**: can a conformance suite check it against a *live server + client* pair? `yes` /
  `codec` (byte-vector only, no live trigger at v1) / `no` (deployment/operator obligation, or
  unreachable at v1 static-N) — with the mechanism.

---

## 1. Master normative-clause table

### §00 Overview / Architecture / Conformance (prefix `OV`)

| Clause | Plane | Holder | Requirement (one line) | Testable |
|---|---|---|---|---|
| OV2-1 | both | C | Writes are HTTP-only; the edge carries no writes — a writer MUST use HTTP | yes (attempt write over edge ⇒ impossible; no write frame exists) |
| OV2-2 | both | C | A driver MAY implement one plane or both | no (implementation-shape statement) |
| OV2-3 | both | C | Planes are not interchangeable (no watch over HTTP, no write over edge); same principal MAY map both | yes (negative: no HTTP watch route, no edge write frame) |
| OV3-1 | meta | C | SHOULD read §00→§01→§03 then the plane sections | no (guidance) |
| OV4-1 | both | C | Two independent version mechanisms; neither is a negotiation | yes (HTTP `/v1/` fixed; edge first-frame pin) |
| OV4-2 | both | C | MUST NOT expect to negotiate/downgrade/discover a version; MUST fail closed on unknown | yes (unknown `/vN/`⇒404; unknown edge version⇒BAD_WIRE_VERSION) |
| OV5-1..5-3 | meta | C | Conformance profiles: Minimal-CRUD / Watch / Full | no (defines suite scope) |
| OV5-4 | both | C | v1 MUST be vector-native cursor AND leader-following **even at N=1** | yes (assert vector cursor on wire; assert 503-retry loop) |
| OV5-5 | both | C | SHOULD test encoder/decoder vs goldens + behaviour vs checklists | codec (this is the suite itself) |
| OV6-1 | edge | S/operator | Legacy `SUBSCRIBE` fan-out has **no per-key ACL** — operator MUST segregate it at the network boundary | no (deployment requirement; mTLS does not scope cert→frame-type) |
| OV6-2 | both | C | Edge requires mTLS; HTTP requires bearer/TLS; `_acl/`,`_system/` need ADMIN; MUST NOT assume auth off | yes |
| OV7-1 | both | C | MUST verify the server (HTTPS endpoint id); MUST NOT downgrade to plaintext in prod | partial (client-side; test client refuses plaintext/no-SAN) |
| OV7-2 | both | C | MUST NOT over-rely on: NodeId map trust, strong-read=freshness-not-confidentiality, replay-guard=passive-only | no (trust-model statement) |
| OV7-3 | both | C | **Fail closed on ANYTHING unknown** (status/header/scope/version/type/code/capability/mechanism) | yes (feed unknowns; expect clean mapped rejection) |
| OV7-4(1) | edge | C | The first-frame version pin IS the negotiation; later mismatched business version ⇒ BAD_WIRE_VERSION | yes |
| OV7-4(2) | both | C | Fail closed on unknown, never a weaker interpretation | yes |
| OV7-4(3) | edge | C | Auth surface is additive (0x04 + code 13); mTLS-only client byte-identical to pre-auth | codec (byte-identity vs pre-auth golden) |
| OV8-1 | meta | — | RFC is implementable now; drivers deferrable | no |
| OV8-2 | both | C | MUST fail closed on named v1 omissions (list, topology-discovery, multi-shard watch=v2 wire, If-Match, batch, JSON body) | yes (each unsupported feature ⇒ fail-closed, not synth) |

### §01 Paths & Access Control (prefix `A`)

| Clause | Plane | Holder | Requirement | Testable |
|---|---|---|---|---|
| A1.3 | both | C | Unknown capability id ⇒ treat as NOT granted (fail closed) | no (client-internal authz model) |
| A2-1 | both | C | Address is `(scope,path)`; `scope` a **typed field**, MUST NOT be a path segment | yes (edge WATCH_CREATE scope byte; HTTP `?scope=`) |
| A2-2 | both | C | `path` is the unit of hierarchy; `scope` non-hierarchical 3-enum | no (model) |
| A2-3 | both | C | MUST allow `scope` default GLOBAL; SHOULD expose REGIONAL/LOCAL | yes (HTTP: absent⇒GLOBAL) |
| A2-4 (INV-PATH) | both | C | Server hashes whole `(scope,path)`; MUST NOT route/shard/order by prefix | no (client MUST-NOT; observable only as no-co-location assumption) |
| A3-1..A3-3 | both | C | Path absolute, `seg-char`-only `[A-Za-z0-9._-]`, UTF-8; reject bytes outside set client-side | yes (client rejects; server does NOT — see D8-2) |
| A3-4 | both | C | Canonical normalization: no empty segs, strip one trailing `/`, no case-fold, reject `.`/`..`/`//` | yes (client-side; server accepts violators D8-2) |
| A3-5 | both | C | path ≤1024 B; SHOULD reject >64 segs / >256 B/seg; value ≤1 MiB | yes (server enforces ≤1024 B key + ≤1 MiB value; seg limits client-only) |
| A3-6 | both | C | Pattern forms: `/a/`≡`/a/**` subtree (REQUIRED), `/a/*` one-level (RECOMMENDED), `/a/b` exact | no (ACL/list/watch-target grammar; v1 has no list wire) |
| A4-1 | both | C | Single-key op = one shard; subtree op = scatter-gather across all shards | no (server-side) |
| A4-2 | both | C | MUST NOT assume cross-shard/global order; only per-key + per-shard | yes (watch: assert no cross-gid order) |
| A4-3..A4-6 | HTTP | C | `list(scope,prefix,mode,cursor,limit)` semantics + per-shard-vector pagination + LIST cap | no (**no `list` wire in v1** — D9-1) |
| A4-7 | HTTP | C | No recursive/subtree delete in v1; delete leaves individually | yes (no recursive-delete wire) |
| A5-1 | both | C | Capability set `{READ,LIST,WRITE,WATCH,ADMIN}` + `DENY` | no (authz model) |
| A5-2 | both | C | LIST ⊥ READ; WATCH requires READ (watch never exposes what read can't) | yes (watch: READ∧WATCH gate) |
| A5-3 | both | C | Rules→policies→roles→principals model | no |
| A5-4 | both | S/both | Evaluate: **union(ALLOW) minus union(DENY), DENY absolute precedence, default-deny** | yes (403 matrix on live server) |
| A6-1 | edge | S | Watch authorized **at subscription, before any snapshot/event/progress frame** as READ∧WATCH over all of T | yes (assert zero data frames before reject) |
| A6-2 | edge | S | Over-broad target ⇒ **reject**, MUST NOT silently narrow | yes |
| A6-3 | edge | S | `full_chain_verify`/`FULL` ⇒ requires **root-scope** READ∧WATCH; else reject | yes |
| A6-4 (INV-WATCH-READ) | edge | S | If READ k denied, watch delivering k MUST be denied | yes |
| A6-5 | edge | S | Unauthorized sub ⇒ terminal 403-class close, **no data frame first**; **negative test mandatory** | yes (this IS a required suite case) |
| A7-1 | both | S | 401 (authn) vs 403 (authz) vs 400 (bad path/limits) taxonomy | yes |
| A7-2 | both | both | 401 MUST NOT echo credential; audit auth failures; **client: 401⇒reauth, 403⇒don't retry unchanged** | yes (401 body has no cred; client behaviour) |
| A8-1 | both | C | Flat key `db.host`⇒`/db.host` degenerate single segment | no (client convention) |
| A8-2 | both | C | HTTP surface GLOBAL-only; edge carries scope typed | yes |
| A9-1 | both | C | **One** cursor-vector type shared by list+watch; **vector even at N=1**; scalar FORBIDDEN | codec (assert vector wire even at N=1) |
| A9-2 | both | C | Same ordering contract for list and watch | no (client presentation) |
| A9-3 | edge | S | Watch-authz contract lives in §1 §6; §2 MUST NOT weaken | yes (== W7) |
| A9-4 | both | C | Fail closed on unrecognized capability identifiers | yes |

### §02 Watches (prefix `W`)

| Clause | Plane | Holder | Requirement | Testable |
|---|---|---|---|---|
| W1-1 | edge | C | **Vector-native + per-key-ordered from first impl, even at N=1**; scalar/global-order FORBIDDEN | codec + yes |
| W1-2 | edge | both | Watch frames require the `0x02` edge version bump; first-frame-pinned, operator-gated | yes |
| W1-3 | edge | both | Per-connection version; existing payloads unchanged; peer negotiating 0x01 MUST fail closed on 0x02 | yes |
| W2-1..W2-4 | edge | C | Watch = persistent, resumable, streaming, `watch_id`-multiplexed; KEY/PREFIX/FULL targets; target path canonical §1; scatter for PREFIX/FULL | yes (target-kind byte; server BAD_SUBSCRIBE on bad target — session layer) |
| W2-5 | edge | C | Watch is **persistent** (fires until cancel/close); MUST NOT assume one-shot | yes |
| W2-6 | edge | both | Resumable with no-miss-in-window, by re-sending cursor on fresh WATCH_CREATE | yes |
| W2-7 | edge | C | Streaming server-push, MUST NOT poll-reread | no (client design) |
| W2-8 | edge | both | `watch_id` unique per connection, **never reused** even after cancel/close | yes (server tags every frame; client must not reuse) |
| W2-9 | edge | C | MUST NOT model as one-shot / long-poll / scalar-global-cursor / globally-ordered | no (client model) |
| W3-1 | edge | C | Cursor is a per-shard vector `{gid→S}` | codec |
| W3-2 | edge | S | `S` deterministic + leader-independent (safe resume across leader change) | no (server property) |
| W3-3 | edge | C | Resume rides `S`; freshness rides `commit_ts`; MUST NOT use ts as cursor | codec (ts is separate field) |
| W3-4 | edge | C | Cursor `0`/omitted = "from now per shard", NOT "replay all"; existing state needs `with_initial_snapshot` | yes |
| W3-5 | edge | C | Cursor wire = `[topologyEpoch u64][count u32](gid u32,S u64)*` ordered by **unsigned** gid; floor 12 B; epoch `1` at v1; `0`⇒FRAME_CORRUPT; epoch-mismatch⇒STALE_TOPOLOGY | codec (STALE_TOPOLOGY not live-triggerable at v1) |
| W3-6 | edge | C | Driver MUST update-one, component-wise **max-merge** (never regress), serialize/deserialize | no (client-internal) |
| W3-7 | edge | both | Resume re-sends full vector; shards resume independently (server obligation) | yes (N>1) / no at N=1 |
| W4-1..W4-5 | edge | both | KEY⇒1 shard/component; PREFIX/FULL⇒scatter; **UNION merge, per-key order only, advance idle cursor on PROGRESS**; aggregating endpoint is v1 | yes (single) / N>1 for multi |
| W5-1 | edge | both | Frame set `0x0A`–`0x12` (see §2 frame catalog) | codec |
| W5-2 | edge | both | Reuses `ERROR_CLOSE`(0x09) connection-level + `NOTIFY`(0x03) only in full_chain_verify | codec |
| W5-3 | edge | both | Watch-scoped `WATCH_SNAPSHOT_*` carry `(watch_id,gid)` (existing SNAPSHOT_* do not) | codec |
| W5-4 | edge | S | WATCH_CREATE creates/resumes; authorize before any CREATED/snapshot/event/progress; watch_id unique; FULL⇒empty path | yes |
| W5-4a | edge | both | flags: bit0 full_chain_verify, bit1 prev_value (MAY be unsupported⇒ignore), bit2 with_initial_snapshot | yes (bit0/bit2) / prev_value not testable positive |
| W5-4b | edge | C | Multi-shard initial snapshot is per-shard, NOT a consistent cut | no (N>1 semantics) |
| W5-5 | edge | both | WATCH_CREATED is FIRST frame; per-shard `(gid,latestSeq,mode∈{TAIL,SNAPSHOT_FIRST})` | yes |
| W5-6 | edge | both | One WATCH_EVENT = one shard-commit, batch-atomic, ascending S per `(watch_id,gid)`; `val_len` is **signed i32** (-1=DELETE) | codec + yes |
| W5-7 | edge | both | WATCH_PROGRESS bookmark advances idle cursors; bounded lower (monotonic) + upper (≤ verified+filtered frontier, no silent gap) | yes (advance) / clamp hard to assert live |
| W5-8 | edge | both | WATCH_CANCEL stops frames; client tolerates in-flight until quiescence | yes |
| W5-9 | edge | both | WATCH_CANCELED terminates one watch; carries code + optional per-shard `oldest` vector | codec |
| W5-9a | edge | S/C | **v1 deferral: server sends `has_oldest=0`**; driver MUST recover by re-list + re-create, not depend on server oldest | yes (assert has_oldest=0) |
| W5-10 | edge | both | Per-shard inline catch-up `WATCH_SNAPSHOT_{BEGIN,CHUNK,END}`, cutover after END, set cursor[gid]=snapshotSeq | yes |
| W5-11 | edge | both | `0x02` frames only on `0x02` connection; server serves `0x01` byte-identically; `0x01`-only peer fails closed on `0x02` | yes |
| W5-12 | edge | C | Keep `SUBSCRIBE`(conn-level scalar) vs `WATCH_CREATE`(per-watch vector) distinct | codec |
| W6-1 | edge | C | Guarantee table: per-key✅ per-shard✅ batch-atomic✅ no-silent-gap✅ at-least-once+dedup✅ bounded-stale✅; cross-shard-order❌ cross-shard-atomicity❌ linearizable❌ scalar-token❌ | yes (dedup: drop iff S≤cursor[gid]) |
| W6-2 / W6-2a | edge | C | MUST NOT present cross-shard order; ORDERED iff same gid ascending S; different gid = CONCURRENT | no (client presentation) |
| W6-3 | edge | both | Too-old ⇒ inline per-shard snapshot resync (others keep streaming), not whole-watch cancel | yes |
| W6-4 | edge | both | Genuinely-unrecoverable ⇒ terminal `WATCH_CANCELED(GAP_UNRECOVERABLE)`; driver re-lists + re-creates | yes (trigger via truncated retention) |
| W6-5 | edge | C | Failover: resume each component, never regress, max-merge; shards fail over independently | yes |
| W6-6 | edge | C | Per-shard read-your-writes only (cursor for gid ≤ commit S); NOT cross-shard | no (client model) |
| W7-1..W7-4 | edge | S | Authorize at subscription/resume as READ∧WATCH over whole T; whole-subtree universal quantifier; interior DENY⇒reject; full_chain_verify⇒root; INV-WATCH-READ | yes |
| W7-5 / W7-5a | edge | S | Unauthorized⇒`WATCH_CANCELED(NOT_AUTHORIZED=11)`, zero data frames first (incl. zero NOTIFY in fcv); 401-class=AUTH_FAIL at connection; **mandatory negative test** | yes |
| W7-6 | edge | C | 403-class NOT_AUTHORIZED ⇒ don't retry same target; 401-class ⇒ reauth | yes |
| W7-7 | edge | S | Bounded revocation: re-authorize live watches on ACL policy-version change; force-close revoked within bounded latency | yes (revoke grant mid-stream; expect NOT_AUTHORIZED) |
| W8-1 | edge | both | Edge-served bounded-stale; read-after-write needs strong-read path, not watch | no (freshness property) |
| W8-2 | edge | both | Lifecycle authn(handshake)→authz(subscription)→stream→resume | yes |
| W8-3 | edge | C | MAY compute staleness frontier from commit_ts/server_now; MUST NOT use as cursor | codec |
| W8-4 | edge | both | Trusted-edge filtered (default) vs full_chain_verify (root, verbatim NOTIFY, client filters); document trust boundary | yes (mode select) |
| W8-5 | edge | — | Leader-served watch is v2, out of scope | no |
| W8-6 | edge | C | **Backpressure is per-CONNECTION in v1** (CURSOR_ACK scalar, no watch_id); siblings share fate; tolerate DEMOTED/QUARANTINED | yes |
| W8-7 | edge | S | `S` side-channel (write volume/timing) acknowledged, not INV-WATCH-READ violation | no |
| W9-1..W9-3 | edge | S | EXISTS/ADDS split; multi-shard v1-delivered by aggregating endpoint; disjoint sharded-edge is v2; legacy SUBSCRIBE primary-shard-only at N>1 | no (server-build note) |
| W10-1 | both | C | Shared cursor + ordering + authz + authn composition | no |
| W10-2..W10-8 | edge | C | Fail closed on named forward extensions (prev_value, fragmentation, leader-served, signed-skip, filtered-watch v2, long-poll gw, per-watch flow-control) | yes (each: don't assume) |

### §03 Authentication (prefix `AU`)

| Clause | Plane | Holder | Requirement | Testable |
|---|---|---|---|---|
| AU2-1 | both | C | Present the credential you have, read the outcome; MUST NOT depend on how server verifies | yes (same driver vs static-bearer and OIDC) |
| AU2-2 | both | C | Support ≥1 of mTLS cert / bearer; **bearer opaque** — MUST NOT parse | no (client-internal) |
| AU2-3 | both | C | Credential is presented not minted; no server-issued replayable session in v1 | yes (no session token issued) |
| AU2-4 | both | C | Four modes (mTLS / Basic / Bearer-OIDC / No-Auth), one shared chain; OIDC rides same Bearer shape | yes |
| AU3-1 | HTTP | C | Bearer in `Authorization: Bearer <token>`; MUST NOT put in URL/log | yes |
| AU3-2 | edge | both | mTLS: `setNeedClientAuth` (mTLS-only) / `setWantClientAuth` (token edge); cert DN authoritative; self-asserted `edgeId` advisory, server overrides | yes |
| AU3-3 | edge | both | Token/basic `AUTH`(0x13)/`REFRESH_AUTH`(0x14) on wire version `0x04`; opaque token; no auth frame on plaintext; stamp 0x04 on exactly auth frames | codec + yes |
| AU3-4 | both | C | Bearer/basic only over TLS; MUST NOT send over plaintext | partial (client-side) |
| AU3-5 | (interior) | — | Raft interior mTLS-only, no token path; **non-driver** | no (out of driver scope) |
| AU4-1 | both | both | Authenticate before any data/subscribe/snapshot frame | yes |
| AU4-2 | both | C | mTLS per-connection; bearer per-request; MUST NOT assume HTTP bearer persists server-side | yes |
| AU4-3 | both | C | Be ready to auth even against auth-disabled deployment; treat 401 as "auth required"; MUST NOT infer "auth off" | yes |
| AU4-4 | edge | C | Certless: send **exactly one** `AUTH` first; reject⇒`AUTH_FAIL`+close (fresh conn); no hot-loop | yes |
| AU4-5 | edge | C | Pre-auth window: frame ceiling + first-frame deadline (10 s); any frame other than AUTH before auth ⇒ `PROTOCOL_VIOLATION` | yes (**but see DRIFT §7.3 — pipelining behind AUTH is buffered**) |
| AU4-6 | edge | C | `REFRESH_AUTH` renews SAME identity, only when authenticated; different id⇒AUTH_FAIL; over-cap/reject⇒CREDENTIAL_EXPIRED; stray AUTH⇒PROTOCOL_VIOLATION | yes |
| AU4-7 | edge | C | No business frame before AUTH accepted / handshake done ⇒ PROTOCOL_VIOLATION | yes |
| AU5-1 | both | S | 401 unauthenticated / 403 forbidden / 401-or-503 authenticator-unavailable | yes (401/403) / 503 see DRIFT §7.4 |
| AU5-2 | both | both | Fail-closed if authenticator unavailable — no silent downgrade; client SHOULD treat 503-class as retryable, 401 as reauth | HTTP: yes / **edge: NO (collapses to AUTH_FAIL — DRIFT §7.4)** |
| AU5-3 | both | both | Never echo/log credential | yes (401 body / AUTH error carries no secret) |
| AU5-4 | both | C | 401⇒reauth (no tight loop of same cred); 403⇒permanently forbidden (no retry unchanged) | yes |
| AU5-5 | both | S | Audit auth failures; not successful reads per-event | no (server-side audit) |
| AU5-6 | edge | both | Credential expiry ⇒ `CREDENTIAL_EXPIRED`(13) at expiry+leeway; static TTL 1h; OIDC exp+leeway; cert notAfter+leeway=reconnect; SHOULD refresh proactively | yes (arm short TTL, observe code 13) |
| AU6-1 | both | C | One principal across both planes | yes |
| AU6-2 | both | C | authn≠authz; a 200/session ≠ authorized; still MAY 403 | yes |
| AU6-3 | both | C | Driver can't read/set roles; MAY shape requests to expected caps | no |
| AU7-1 | both | C | Fail closed on unknown mechanism / WWW-Authenticate scheme / auth capability | yes |
| AU7-2 | both | C | New server authenticators MUST NOT need driver change | yes (swap authenticator, same driver) |
| AU7-3 | both | C | Fail closed on named forward extensions (Configd session token, multi-leg SASL/Kerberos/…, interior token) | yes |
| AU8-1..8-4 | both | C | 401/403 split shared; watch authn precedes authz precedes data; one principal; scope/transport mapping | yes |

### §04 Data Plane (prefix `D`)

| Clause | Plane | Holder | Requirement | Testable |
|---|---|---|---|---|
| D1-1 / D1-2 | HTTP | C | Versioned solely by `/v1/` prefix; no negotiation; a future rev is `/v2/` | yes |
| D2-1 | HTTP | both | Key = percent-decoded path tail after `/v1/config/`, un-normalized, IS the storage key | yes |
| D2-2 | HTTP | S | Exact-match `/health/live`,`/health/ready`,`/metrics`; prefix `/v1/config/`; else 404 | yes (**but see DRIFT §7.1 — 5th route exists**) |
| D2-3 | HTTP | S | GET/PUT/DELETE on config; else 405; fixed endpoints GET-only | yes |
| D2-4 | HTTP | S | Empty key `/v1/config/` ⇒ 400 **pre-auth** (routing time) | yes |
| D2-5 / D2-5a | HTTP | C | **Content-Type is misleading**: read-200=`octet-stream` raw; write/errors=`application/json` but **plaintext**; only `/health/*` real JSON; MUST NOT JSON.parse or render as markup; may echo input | yes |
| D2-6 | HTTP | C | No writes on edge; use HTTP | yes |
| D2-7 | HTTP | C | Success is 200 (never 201/204); 0-length body 200 ≠ 404 (use `found`, not length) | yes |
| D3-1 | HTTP | S | Read 200 = raw value bytes, opaque | yes |
| D3-2 | HTTP | C | Read headers: `X-Config-Version`(dec u64), `X-Consistency`, `X-Strong-Read`(only strong-read); version from **header** | yes |
| D3-2a | HTTP | C | `X-Consistency: linearizable` on non-`secure/` key = requested-mode echo, NOT freshness proof | yes (stale-only deployment) |
| D3-3 | HTTP | S | Absent key ⇒ 404; present-empty ⇒ 200 0-length | yes |
| D3-4 | HTTP | C | `?consistency=linearizable` is a **loose substring** (`query.contains`); under/over-trigger hazards; asymmetric with exact `?scope=` | yes (send `?myconsistency=linearizable`⇒linearizable path) |
| D3-5 / D3-5a | HTTP | S/C | Strong-read class force-linearizable, fail-closed 503+`X-Fail-Closed: strong-read`, never stale; operator-configured (default `secure/`, MAY be disabled); rely on **headers** not name | yes |
| D3-5b | HTTP | C | Strong-read = freshness not confidentiality; at-rest encryption is deployment choice, unobservable | no (trust-model) |
| D3-6 | HTTP | S/C | Ordinary-key linearizable-unavailable ⇒ 503 **without** `X-Fail-Closed` (+hint when known); MAY fall back to stale | yes (where linearizable path wired) |
| D3-7 | HTTP | S | Read needs READ; reserved `_acl/`,`_system/` need ADMIN for every method when auth on; failures audited | yes |
| D3-8 | HTTP | C | GET side-effect-free, freely retryable; app never returns 504 on GET | yes |
| D4-1 | HTTP | S | PUT body = raw value ≤1 MiB; empty body ⇒ 400; body read only after auth+replay pass | yes |
| D4-2 | HTTP | C | Write 200 body = plaintext `Committed: seq=<N>`; **seq in BODY not header**; parse it | yes |
| D4-3 | HTTP | C | put = idempotent LWW upsert; safe re-apply; no CAS/If-Match in v1 | yes |
| D4-4 | HTTP | C | PUT honors `?scope=`, absent⇒GLOBAL | yes |
| D4-5 | HTTP | S | `_acl/` PUT validated as policy pre-commit (same parser as reload); malformed⇒400 `Invalid ACL policy`; incomplete OK; needs ADMIN | yes |
| D4-6 | HTTP | C | Write outcome→status: committed 200 / not-leader 503+hint / lost 503+hint / indeterminate 504 / validation 400 / overloaded 429+Retry-After | yes |
| D4-7 | HTTP | C | 200 = quorum-committed AND applied (durable), not "accepted" | yes |
| D4-8 | HTTP | C | **504 is indeterminate — write MAY commit later**; retry-to-definite (idempotent LWW); negative re-read ≠ proof; no RMW across it | yes (semantic; harder to force) |
| D5-1..D5-5 | HTTP | C | DELETE mirrors write (200 `Committed: seq=`; 503/504/429); no body; `_acl/` deletable+ADMIN but not re-validated; idempotent tombstone; no recursive delete | yes |
| D6-1..D6-5 | HTTP | C | Two cursors: write-seq(body) vs read-version(header); same per-shard applied-mutation seq; per-shard-not-global opaque monotonic u64; gaps normal; RYW needs linearizable/strong-read (no read-at-version param); model as `(gid,S)` scalar-of-vector | yes |
| D7-1..D7-4 | HTTP | S/C | `?scope=` case-insensitive enum, default GLOBAL, unknown⇒400 fail-closed, **exact param match** (unlike consistency); routing-only never echoed/stored; topology GLOBAL-only | yes |
| D8-1 | HTTP | S | Server enforces ONLY: key non-blank + ≤1024 B UTF-8 (length-before-blank) | yes |
| D8-2 | HTTP | C | Server does NOT enforce §1 grammar/normalization — that is **client-side**; server accepts violating legacy keys | yes (send `/a/../b`⇒server accepts) |
| D8-3 | HTTP | S | Value ≤1 MiB else 400 ValidationFailed | yes |
| D8-4 | HTTP | S | Validation post-auth (401/403 before 400), **except** D2-4 empty-key 400 pre-auth | yes |
| D9-1 | HTTP | C | `list` DEFERRED — no endpoint; MUST NOT synthesize/guess | yes |
| D10-1 | HTTP | S | `/health/{live,ready}` GET-only, **real JSON**, 200/503, unauthenticated | yes |
| D10-2 | HTTP | S | `/metrics` GET-only, Prometheus text, bearer-gated (401+WWW-Authenticate) when auth on, exact path | yes |
| D11-1 | HTTP | C | Every write/strong-read/linearizable can 503 — leader-follow+backoff **even at N=1**; 503 **MAY omit `X-Leader-Hint`** (election); handle hintless | yes |
| D11-2 | HTTP | C | Named status codes each carry inline reaction | yes |
| D11-3 | HTTP | C | Optional replay guard (default off): PUT/DELETE MAY carry `X-Configd-Timestamp`+`X-Configd-Nonce`; stale⇒401, replayed nonce⇒409; passive-replay-only, not integrity | yes (enable guard) |
| D11-4 | HTTP | C | Fail closed on named omissions (list, read-at-version, conditional write, batch, JSON body) | yes |

### §05 Routing / Leader-Following (prefix `R`)

| Clause | Plane | Holder | Requirement | Testable |
|---|---|---|---|---|
| R2-1 | HTTP | C | No 3xx/Location; read leader from `X-Leader-Hint` **header** only, never the body | yes |
| R2-2 | HTTP | C | Hint is a **bare numeric NodeId**, not an address; resolve via own out-of-band map (anti-SSRF) | yes |
| R2-3 | HTTP | C | Hint advisory + MAY be absent (election, normal N=1); handle hintless 503 | yes |
| R2-4 | HTTP | C | Hint rides 503 only; **504 carries no hint** and is not a redirect | yes |
| R3-1 | HTTP | C | Map resolves NodeId→HTTP **api-port** endpoint (not the Raft `--peer-addresses`/`--bind-port`) | no (operator config) |
| R3-2 | HTTP | C | No topology/shard-map/membership endpoint; MUST be configured with NodeId→endpoint map | yes (no such route — **but see DRIFT §7.1**) |
| R3-3 | HTTP | C | Unresolvable hint ⇒ degrade to hintless (backoff/rotate), don't fail hard | no (client-internal) |
| R4-1 / R4-2 | HTTP | C | Leader-follow+retry REQUIRED even at N=1; single node 503s (often **hintless**) during election ⇒ backoff+retry same endpoint, bounded budget | yes |
| R4-3 | HTTP | C | Follow-once (`hop<2`) then back off — no redirect ping-pong; client-side (server holds no hop state) | no (client-internal) |
| R4-4 | HTTP | C | Any read can 503 (strong-read class invisible); be prepared, apply R6 | yes |
| R5-1..R5-4 | HTTP | C | **No client-side sharding**; N unknown-and-unneeded; hint per-request shard/scope-resolved; don't cache hint across keys; identical routing N=1/N>1; form neither "one-leader" nor "owns-subset" assumption | yes (N>1) |
| R6-1 | HTTP | C | Outcome→action table (200/503±hint/504/5xx/404/400/401/403/429/409) | yes |
| R6-2 | HTTP | C | Idempotency is what makes retry safe; no non-idempotent op on this plane | yes |
| R6-3 | HTTP | C | Bounded exp-backoff+jitter, honor Retry-After, bound total; budget-exhausted-on-504 ⇒ terminate UNKNOWN, no RMW | no (client-internal) |
| R6-4 | HTTP | C | Replay guard: mint **fresh** timestamp+nonce every attempt; passive-replay-only; without it, mutate only vs guard-disabled | yes |
| R7-1 / R7-2 | HTTP | C | Fail closed on future discovery endpoint / richer hint / 3xx / partitioned topology / read-from-follower | yes |
| R8-1 | HTTP | C/operator | NodeId→address map is a trust boundary; same-trust-domain only; don't add entries from responses | no (operator) |
| R8-2 | HTTP | operator | Bearer tokens replayable cluster-wide; prefer mTLS / scoped tokens | no |
| R8-3 | HTTP | C | A follow MUST NOT relax TLS/mTLS (full validation, SAN) | partial (client-side) |
| R8-4 | HTTP | S | Hint is authz-gated (checkAuth precedes every hint site); no anonymous topology disclosure | yes (401/403 before any hint) |

### §06 Wire Framing (prefix `F`) — driver surface §1–§12; §13 Raft is non-driver

| Clause | Plane | Holder | Requirement | Testable |
|---|---|---|---|---|
| F2-1 | edge | both | Envelope `[L u32 BE][ver u8][type u8][payload][CRC32C u32 BE]`; L=whole frame; HEADER=6; TRAILER=4; min 10; MAX=2 MiB | codec (golden) |
| F2-2 | edge | both | Each layout MUST reproduce the golden vector exactly | codec |
| F2-3 | edge | C | L covers whole frame; one frame per buffer; L must equal actual size | codec |
| F2-4 | edge | C | CRC32C is **integrity not authenticity**; MUST NOT treat valid CRC as authentic; TLS provides auth | no (trust-model) |
| F3-1 | edge | C | Decode order: len≥10 → 10≤L≤2MiB (>2MiB=FRAME_TOO_LARGE, <10=FRAME_CORRUPT) → L==data.len → **CRC before ver/type** → ver∈{01,02,03,04}(+pin) → type + type↔ver legality → payload + trailing-bytes⇒FRAME_CORRUPT | codec |
| F3-2 | edge | C | Bounds-check every length/count (incl. server-set snapshot sizes) **before allocating** | codec |
| F4-1 | edge | both | First **business** frame's version pins the connection; later mismatched business version⇒BAD_WIRE_VERSION; no downgrade/re-pin | yes |
| F4-2 | edge | C | Pick version by first business frame, stamp it on every business frame; new version⇒new connection | yes |
| F4-3 | edge | both | `0x04` auth frame is **pin-exempt** — decoded under 0x04, never sets/checks the business pin; business type stamped 0x04 or auth type stamped 01/02/03 ⇒ FRAME_CORRUPT | codec + yes |
| F5-1 | edge | C | Sequence/timestamp u64 fields constrained `[0,2^63)`; high-bit⇒FRAME_CORRUPT (incl. client-emitted `CURSOR_ACK.seq`, `SUBSCRIBE.resumeCursor`) | codec |
| F5-2 | edge | C | Raw-u64: `SUBSCRIBE_OK.latestSeq`, `HEARTBEAT.latestSeq`; `failoverResumeCursor`∈`[0,2^63)∪{0xFFFF…FF}` (sole legal high-bit = "none"); opaque: `watch_id`(u64),`gid`(u32) | codec |
| F5-3 | edge | C | `topologyEpoch`∈`[1,2^63)`; `0`⇒FRAME_CORRUPT; emit server epoch (v1=1); epoch-advance⇒STALE_TOPOLOGY⇒drop+rehydrate | codec |
| F6-1 | edge | both | `SUBSCRIBE`(0x01) layout incl. `topologyEpoch` **between last prefix and resumeCursor** (NOT version-gated; omitting ⇒ 8 B short ⇒ FRAME_CORRUPT); `MAX_PREFIXES=4096` | codec (golden) |
| F6-1a | edge | both | `0x03` SUBSCRIBE has trailing `acceptsFiltered` byte; full-store MUST set 0 | codec |
| F6-2 / F6-2a | edge | both | `SUBSCRIBE_OK`(0x02) `[latestSeq u64][mode u8]`; `0x03` adds trailing `filtered` byte | codec |
| F6-3 | edge | both | `NOTIFY`(0x03) batch; decoder enforces count≤64 AND ≤256 KiB; `sigLen` signed i32 (-1=unsigned); signing payload composition | codec |
| F6-4..F6-6 | edge | both | `SNAPSHOT_{BEGIN,CHUNK,END}`; chunk ≤1 MiB no inner prefix; reassemble to chunkCount/totalBytes or discard | codec |
| F6-7 | edge | C | `CURSOR_ACK`(0x07) `[seq u64]` client-emitted, mandatory flow-control | codec |
| F6-8 / F6-8a | edge | C | `HEARTBEAT`(0x08) `[latestSeq raw u64][serverNow u64]`; don't advance applied cursor from it; filtered-heartbeat re-typed covered-S | codec |
| F6-9 | edge | C | `ERROR_CLOSE`(0x09) `[code u8 1..13][msgLen u32][msg]`; unknown code⇒FRAME_CORRUPT; msg untrusted (sanitize) | codec |
| F6A-1..F6A-2 | edge | both | `AUTH`(0x13)/`REFRESH_AUTH`(0x14) under 0x04; `[scheme u8(1=bearer,2=basic)]` + length-prefixed fields; cert never framed | codec (golden) |
| F6A-3 | edge | both | Type↔version legality matrix; every out-of-matrix combo ⇒ FRAME_CORRUPT | codec |
| F6A-4 | edge | both | 0x04 pin-exempt + purely additive; mTLS-only client byte-identical | codec |
| F6A-5 | edge | S | Pre-auth frame ceiling (default 16384) before allocation; credential caps (token 8192 B, user 256 B, pass 1024 chars); over-cap ⇒ AUTH_FAIL/CREDENTIAL_EXPIRED | yes (policy bounds) |
| F7-1 | edge | C | Nested `CommandCodec` blob `[type][u16 keyLen][key][i32 valLen≤1MiB][val]` PUT/DELETE/BATCH(count≤10000); `NOTIFY`/`WATCH_EVENT` blob is a BATCH | codec |
| F7-2 | edge | C | ADR-0028 snapshot body; three-form trailer (magic-TLV / raw-8-byte / none); **skip-unknown TLV** ≥0x0002; bound every length | codec |
| F8-1 / F8-2 | edge | C | Cursor codec `[epoch u64][count u32](gid u32,S u64)*`; strictly ascending unsigned gid, no dup; floor 12 B; count=0=from-now; vector even at N=1 | codec |
| F9-1 | edge | C | mTLS REQUIRED (or WANTED on token edge); MUST NOT downgrade to plaintext in prod | partial (client-side) |
| F9-2 | edge | C | **TLSv1.3-only**; suites `TLS_AES_256_GCM_SHA384`/`TLS_AES_128_GCM_SHA256`; no 1.2 | yes (handshake) |
| F9-3 | edge | C | Identity = verified cert Subject DN; `edgeId` advisory | yes |
| F9-4 | edge | C | MUST set endpoint identification `"HTTPS"` (SAN covers host) + supply host name; CA-alone insufficient; no MITM | partial (client-side) |
| F10-1 | edge | C | Lifecycle connect→handshake→auth→first-frame-pins→operate→resume-by-re-create; no session token | yes |
| F10-1a | edge | C | Reconnect keeps only persisted cursor; loses watch_ids/budget/multiplex/unacked; re-CREATE with fresh ids | yes |
| F10-1b | edge | C | **Single shared drain**: only first watch's cursor positions the drain; subsequent watches TAIL-from-now (silent-data-loss footgun) ⇒ **one connection per independently-resumed watch** | yes (re-create N cursored watches on one conn; observe only #1 resumes) |
| F10-1c | edge | C | Stale resume ⇒ full snapshot re-bootstrap (mode=1), not a tail | yes |
| F10-1d | edge | C | Post-handshake pre-SUBSCRIBE first-frame deadline (default 10 s); disarmed after first routed frame | yes |
| F10-1e | edge | C | Token edge: AUTH precedes first business frame; pre-auth ceiling + single attempt + first-frame deadline cover it | yes |
| F10-2 | edge | C | Caps: ≤1024 sessions (silent pre-handshake TCP close⇒retry/backoff, NOT protocol error); ≤1024 live watches/conn (BAD_SUBSCRIBE, WATCH_CANCEL frees); ≤16384 lifetime watch_ids/conn (reconnect); ≤1024 B target | yes |
| F10-2a | edge | operator | Aggregate ceiling = sessions × max-frame; operator sizing note | no |
| F10-3 | edge | C | **Flow-control mandatory**: send CURSOR_ACK periodically + drain promptly; outbound queue 64 / in-flight 256 ⇒ DEMOTED_TO_CATCHUP (non-fatal, handle); continued ⇒ QUARANTINED | yes |
| F10-4 | edge | C | Quarantine is identity-stateful (cert DN) across reconnects; honor cooldown before reconnect | yes |
| F11-1 | edge | C | Frame is fixed-positional fail-closed; unknown type⇒FRAME_CORRUPT, unknown ver⇒BAD_WIRE_VERSION, illegal type-for-ver⇒FRAME_CORRUPT, trailing bytes⇒FRAME_CORRUPT; a future field CANNOT be appended silently | codec |
| F11-2 | edge | C | Only TLV-extensible region = snapshot trailer (skip-unknown); reject-unknown at frame | codec |
| F11-3 | edge | C | Watch driver shares envelope (needs NOTIFY/ERROR_CLOSE bytes + F7-1 blob) | codec |
| F13-1..F13-9 | Raft | (non-driver) | Consensus wire byte-for-byte (HEADER 26, 16 MiB, ver 0x02 strict, MBZ epoch, mTLS+peer-identity) | out of driver scope (documented for completeness; **not independently re-verified here**) |

### §07 Errors (prefix `E`)

| Clause | Plane | Holder | Requirement | Testable |
|---|---|---|---|---|
| E2-1 | HTTP | C | Full HTTP status set + reaction (200/400/401/403/404/405/409/429/503/504/transport) | yes |
| E2-2 | HTTP | C | Disambiguate 503 by header: `X-Fail-Closed: strong-read` / `X-Leader-Hint` present / neither (election-or-unhealthy) | yes |
| E3-1 | edge | C | The 13 streaming `ErrorCode`s + reactions; branch on numeric code | codec + yes (most) |
| E3-2 | edge | C | Catch-up ladder: DEMOTED_TO_CATCHUP(7) is non-fatal (keep streaming, ack/drain) → QUARANTINED(8) ends session | yes |
| E3-3 | edge | C | **Scope = code + carrier frame**; pure code switch insufficient for codes 4,6,7,9,11,12 | yes |
| E4-1 | both | C | 401/403 split identical on both planes (AUTH_FAIL≈401, NOT_AUTHORIZED≈403, CREDENTIAL_EXPIRED≈401-reauth) | yes |
| E5-1 | both | C | §07 is single source of truth; implement error handling once | no (client design) |
| E6-1 | both | C | Machine signal = code (+headers/carrier), **never body/message**; MUST NOT JSON.parse/branch-on/render; sanitize before log | yes |
| E7-1 | both | C | Retry classification buckets (retry / indeterminate-no-RMW / retry-after-modify-cred / recover-by-watch-action / terminal) | yes |

---

## 2. The edge frame catalog (type-byte → name → layout → direction)

**Envelope (F2-1), every frame:** `[Length u32 BE][Version u8][Type u8][Payload][CRC32C u32 BE]`.
Length covers the whole frame; min 10 B; max **2 MiB**; CRC-32C (Castagnoli, **not** IEEE) over
`[0, L-4)`, verified **before** version/type. Version bytes: **`0x01`** built, **`0x02`**
watch-capable, **`0x03`** filtered fan-out (ADR-0045), **`0x04`** auth-phase. Business version pinned
by the **first business frame**; `0x04` is **pin-exempt** and interleaves. (Confirmed against
`EdgeFrameCodec.EDGE_WIRE_VERSION*`, `FrameType`, `ByteToEdgeFrameDecoder`.)

| Type | Name | Dir | Legal ver | Payload (big-endian) |
|---|---|---|---|---|
| `0x01` | SUBSCRIBE | C→S | 01/02/03 | `[fullStore u8][prefixCount u32](len u32,prefix)*[topologyEpoch u64][resumeCursor u64][failoverResumeCursor u64][edgeIdLen u32][edgeId]` (+`[acceptsFiltered u8]` under 0x03) |
| `0x02` | SUBSCRIBE_OK | S→C | 01/02/03 | `[latestSeq u64 raw][mode u8]` (+`[filtered u8]` under 0x03) |
| `0x03` | NOTIFY | S→C | 01/02 | `[count u32]( seq u64, commitTs u64, fromVer u64, toVer u64, batchLen u32, batch, sigLen i32(-1=unsigned), sig?, epoch u64, nonceLen u32, nonce )*` |
| `0x04` | SNAPSHOT_BEGIN | S→C | 01/02 | `[snapshotSeq u64][chunkCount u32][totalBytes u64]` |
| `0x05` | SNAPSHOT_CHUNK | S→C | 01/02 | `[index u32][bytes]` (bytes=remainder, ≤1 MiB, no inner prefix) |
| `0x06` | SNAPSHOT_END | S→C | 01/02 | `[snapshotSeq u64]` |
| `0x07` | CURSOR_ACK | C→S | 01/02 | `[seq u64]` (client-emitted; `[0,2^63)`) |
| `0x08` | HEARTBEAT | S→C | 01/02 | `[latestSeq u64 raw][serverNowMillis u64]` |
| `0x09` | ERROR_CLOSE | S→C (either) | 01/02 | `[code u8 1..13][msgLen u32][msg]` — connection-terminal (except code 7) |
| `0x0A` | WATCH_CREATE | C→S | **02 only** | `[watchId u64][scope u8][targetKind u8][pathLen u32][path][cursor][flags u8]` |
| `0x0B` | WATCH_CANCEL | C→S | 02 only | `[watchId u64]` |
| `0x0C` | WATCH_CREATED | S→C | 02 only | `[watchId u64][shardCount u32](gid u32, latestSeq u64, mode u8)*` |
| `0x0D` | WATCH_EVENT | S→C | 02 only | `[watchId u64][gid u32][S u64][commitTs u64][changeCount u32]( keyLen u32,key,kind u8,valLen i32(-1=DELETE),val )*` |
| `0x0E` | WATCH_PROGRESS | S→C | 02 only | `[watchId u64][cursor][serverNowMillis u64]` |
| `0x0F` | WATCH_CANCELED | S→C | 02 only | `[watchId u64][code u8][hasOldest u8][cursor iff hasOldest=1][msgLen u32][msg]` — per-watch terminal |
| `0x10` | WATCH_SNAPSHOT_BEGIN | S→C | 02 only | `[watchId u64][gid u32][snapshotSeq u64][chunkCount u32][totalBytes u64]` |
| `0x11` | WATCH_SNAPSHOT_CHUNK | S→C | 02 only | `[watchId u64][gid u32][index u32][bytes]` |
| `0x12` | WATCH_SNAPSHOT_END | S→C | 02 only | `[watchId u64][gid u32][snapshotSeq u64]` |
| `0x13` | AUTH | C→S | **04 only** | `[scheme u8(1=BEARER,2=BASIC)]` then bearer `[tokLen u32][tok]` / basic `[userLen u32][user][passLen u32][pass]` |
| `0x14` | REFRESH_AUTH | C→S | 04 only | identical payload to AUTH |

**Caps enforced in the codec** (confirmed): `MAX_EDGE_FRAME_SIZE`=2 MiB, `MAX_SNAPSHOT_CHUNK_BYTES`=1
MiB, `MAX_NOTIFY_BATCH`=64 + `MAX_NOTIFY_BATCH_BYTES`=256 KiB (both enforced on **decode**),
`MAX_PREFIXES`=4096, cursor floor 12 B. Golden hex verified for
`subscribe_full_store.bin`/`auth_bearer.bin`/`auth_basic.bin`/`refresh_auth_bearer.bin`/`watch_create*`
— **all match the RFC byte-for-byte.**

---

## 3. The auth handshake state machine (4 modes)

Confirmed against `EdgeAuthGateHandler` (edge) + `AdminApiHandler.checkAuth`/`metrics` (HTTP).

- **No-Auth** — deployment posture (loud server warning). Edge: no gate handler; HTTP: `chain`/`authInterceptor` null. Driver MUST still be able to present a credential and treat a later 401 as "auth required" (AU4-3).
- **mTLS (edge)** — auth is the TLS handshake. `wantClientAuth` on a token edge, `needClientAuth` mTLS-only. Verified cert DN = authoritative identity; `edgeId` advisory. No `0x04` frame; byte-identical to pre-auth client. On handshake failure ⇒ `AUTH_FAIL` (TLS-layer reset, **not** a framed close). Cert `notAfter` (when enforced) ⇒ `CREDENTIAL_EXPIRED` = reconnect-with-rotated-cert.
- **Basic (edge)** — `AUTH`/`REFRESH_AUTH` scheme=2. PBKDF2 verification is off-loop.
- **Bearer/OIDC (edge)** — `AUTH`/`REFRESH_AUTH` scheme=1, opaque token.

**Edge connection-level lifecycle (certless token/basic path):**
`channelActive/handshake` → **UNAUTHENTICATED** (pre-auth frame ceiling armed, first-frame deadline
armed) → client sends **exactly one `AUTH`** → resolve off-loop:
- Authenticated ⇒ **install** `AUTHENTICATED` state, arm TTL expiry, fire session-start, **replay any
  business frames pipelined behind the AUTH**.
- Denied ⇒ `AUTH_FAIL` framed `ERROR_CLOSE(4)` + close (retry = fresh connection).
- Unavailable (IdP/JWKS down) ⇒ **also `AUTH_FAIL(4)` on the wire** (metric `AUTH_UNAVAILABLE` only).
- Second pre-auth `AUTH` while resolving, or a business frame **before** any AUTH ⇒ `PROTOCOL_VIOLATION`.

**AUTHENTICATED:** business frames pass to the session. `REFRESH_AUTH` re-resolves the **same
identity** (different id ⇒ `AUTH_FAIL`; over-cap/reject ⇒ `CREDENTIAL_EXPIRED`; duplicate while
resolving ⇒ dropped). A stray `AUTH` ⇒ `PROTOCOL_VIOLATION`. TTL/exp/notAfter expiry ⇒
`CREDENTIAL_EXPIRED(13)` framed close. Identity is **fixed at first authentication** (never re-bound
in v1).

**HTTP:** bearer per-request in `Authorization:`; `checkAuth` before the op; 401 (+`WWW-Authenticate:
Bearer`) unauthenticated / 403 forbidden / 503 (external-chain `Unavailable`, on `/metrics` and config
auth paths). Same principal as the edge (AU6-1).

---

## 4. The watch / cursor model

- **Cursor** = `WatchCursor{ topologyEpoch:u64, [ (gid:u32, S:u64) … ] }` ordered by **unsigned**
  gid, epoch `[1,2^63)` (v1=`1`), `S` `[0,2^63)`. Wire floor 12 B. `count=0` = "from now per shard"
  (NOT replay). Vector-native even at N=1. (Confirmed `WatchCursor.java`.)
- **Frames:** `WATCH_CREATE`/`WATCH_CANCEL` (C→S); `WATCH_CREATED` (first frame, per-shard mode
  vector), `WATCH_EVENT` (per shard-commit, `(gid,S)`-tagged, `val_len` signed i32), `WATCH_PROGRESS`
  (bookmark, advances idle cursors), `WATCH_CANCELED` (per-watch terminal), `WATCH_SNAPSHOT_{BEGIN,
  CHUNK,END}` (per-`(watch_id,gid)` inline catch-up).
- **Honest ordering contract (W6-1):** per-key ✅, per-shard ✅ (ascending S, gap-free in window),
  batch-atomic-per-shard-commit ✅, no-silent-gap ✅, at-least-once + `(gid,S)` dedup ✅ (drop iff
  `S ≤ cursor[gid]`), bounded-stale ✅. **Cross-shard/global order ❌, cross-shard atomicity ❌,
  linearizable ❌, scalar token ❌.** Two events ORDERED iff same gid; different gid = CONCURRENT.
- **Catch-up ladder:** in-window resume = TAIL; behind-buffer = inline per-shard `WATCH_SNAPSHOT_*`
  resync (siblings keep streaming, cursor[gid]=snapshotSeq on END); genuinely-unrecoverable =
  `WATCH_CANCELED(GAP_UNRECOVERABLE)` ⇒ re-list current state + re-create (v1: `has_oldest=0`, so the
  driver uses its own cursor). Backpressure ladder = `DEMOTED_TO_CATCHUP(7, non-fatal)` →
  `QUARANTINED(8, identity-cooldown)`.
- **Epoch:** epoch-advance ⇒ `STALE_TOPOLOGY(12)` ⇒ drop cursor, full re-hydrate from scratch (NOT an
  earlier-S resume). **Never fires at v1 static-N.**
- **Resume by re-CREATE** with the saved vector; **one connection per independently-resumed watch**
  (single-shared-drain footgun, F10-1b).

---

## 5. HTTP data-plane contract (§04/§05)

`GET/PUT/DELETE /v1/config/{key}` where `{key}` = percent-decoded, **un-normalized** path tail = the
storage key. Confirmed `AdminApiHandler`:

- **Read 200:** body = raw value (`application/octet-stream`); headers `X-Config-Version` (dec u64,
  from **header**), `X-Consistency`, `X-Strong-Read: true` (only strong-read). 404 = absent; 200
  0-length = present-empty.
- **Write/Delete 200:** body = plaintext `Committed: seq=<N>` — the **seq is in the BODY**, no
  version header. Idempotent LWW; `_acl/` PUT validated pre-commit; empty PUT body ⇒ 400.
- **Consistency:** `?consistency=linearizable` is a **loose substring** (`query.contains`, confirmed
  line 325) — under-trigger (anything else ⇒ stale) and over-trigger (`?myconsistency=linearizable` ⇒
  linearizable). **Strong-read** class (operator default `secure/`, MAY be disabled) is
  force-linearizable and **fails closed 503 + `X-Fail-Closed: strong-read`**, never stale — verify the
  **headers**, not the name. Ordinary-key linearizable-unavailable ⇒ 503 **without** `X-Fail-Closed`.
- **`?scope=`:** case-insensitive enum, **exact** param match (confirmed `queryParam`), default
  GLOBAL, unknown ⇒ 400 fail-closed; routing-only, never echoed/stored.
- **Validation:** server enforces **only** non-blank + ≤1024 B key (length-before-blank) and ≤1 MiB
  value; §1 path grammar is **client-side** (server accepts violating legacy keys). Post-auth, except
  the empty-key `/v1/config/` 400 which is pre-auth.
- **Leader-following (REQUIRED even at N=1):** every write/strong-read/linearizable read can 503;
  read `X-Leader-Hint` (bare numeric NodeId) from the **header** only; the 503 **MAY be hintless**
  (election) ⇒ backoff+retry same endpoint, bounded budget. **504 is indeterminate** (write MAY commit
  later) — retry-to-definite, no RMW, no hint. `429`+`Retry-After`. No `3xx`/Location; no
  topology-discovery endpoint; no client-side sharding.
- **`list` is DEFERRED** (no wire). Optional replay guard (default off): `X-Configd-Timestamp`+
  `X-Configd-Nonce`, stale⇒401, replayed nonce⇒409, fresh per retry, passive-replay-only.
- **Operational:** `/health/{live,ready}` GET-only, **real JSON**, 200/503, unauthenticated;
  `/metrics` GET-only Prometheus text, bearer-gated when auth on.

---

## 6. The error taxonomy (§07)

**HTTP status → reaction:** 200 ok · 400 permanent-fix · 401 (re)authenticate (`WWW-Authenticate:
Bearer`) · 403 permanently-forbidden · 404 definite-absent · 405 fix-method · 409 fresh-nonce · 429
honor-`Retry-After` · 503 disambiguate-by-header+§05 · 504 indeterminate-retry-to-definite-no-RMW ·
transport-timeout-on-mutation = indeterminate.

**Edge `ErrorCode` (1–13, confirmed `ErrorCode.java`), keyed on (code, carrier):**
1 BAD_WIRE_VERSION · 2 FRAME_TOO_LARGE · 3 FRAME_CORRUPT · 4 AUTH_FAIL (mTLS handshake reset **or**
framed `ERROR_CLOSE(4)` token reject) · 5 BAD_SUBSCRIBE (malformed vs resource-cap) · 6
GAP_UNRECOVERABLE (carrier-dependent: `WATCH_CANCELED` per-watch / `ERROR_CLOSE` legacy) · 7
DEMOTED_TO_CATCHUP (**non-fatal**, rides `0x09`) · 8 QUARANTINED (identity cooldown) · 9
SERVER_SHUTDOWN (`ERROR_CLOSE`=reconnect / `WATCH_CANCELED`=expected cancel-ack, don't reconnect) · 10
PROTOCOL_VIOLATION · 11 NOT_AUTHORIZED (403-class, `WATCH_CANCELED` per-watch; MAY arrive mid-stream as
revocation) · 12 STALE_TOPOLOGY (**v2-only, never at v1**) · 13 CREDENTIAL_EXPIRED (framed close,
token/basic edge; reauth on fresh connection). **Branch on the code + carrier, never the message.**

---

## 7. GAPS / AMBIGUITIES / CONTRADICTIONS / DRIFT (highest-value output)

Adversarial cross-check. Each item is a candidate spec-bug the client build / conformance suite will
expose and should force a fix.

**7.1 — DRIFT (code has a route the RFC's routing table denies).** `AdminApiHandler.handle`
(`configd-server/.../AdminApiHandler.java:214-234`) routes a **fifth** HTTP endpoint the driver RFC
never documents: `POST /v1/admin/groups/{groupId}/transfer-leadership?target=<nodeId>` (wired when the
`leadershipAdmin` seam is non-null — and Group B's "leadership auto-balance" now wires it). It is a
real ADMIN-gated, replay-guarded, `X-Leader-Hint`-bearing control op with the full taxonomy
`200/400/401/403/404/405/409/503(+X-Leader-Hint)` (+ replay-guard `401`/`409`); the ADMIN gate is
**stricter** than config (refused outright when auth is off). **This directly contradicts D2-2 / D9-1 /
R3-2 / OV8**, all of which assert the only routes are `{/health/live,/health/ready,/metrics,/v1/config/}`
and "any other path ⇒ 404". A conformance assertion "unknown `/v1/...` path ⇒ 404" **fails** against a
deployment with the seam wired. **Fix:** either document this endpoint in §04/§05 (with its taxonomy)
or explicitly scope it out of the driver surface (it is arguably an operator/admin surface, not a
driver one) — but the RFC currently pretends it does not exist.

**7.2 — DRIFT (pervasive stale line citations).** Every `AdminApiHandler.java` line number cited in
§04/§05/§07 is wrong; the file has grown. Actual sites: `handle` **:214-234** (RFC says :130-145);
`query.contains("consistency=linearizable")` **:325** (:225); `Committed: seq=` **:482** (:385);
`failClosed` **:378-390** (:279-291); `keyValidationReason` **:1028** (:642-652); `parseScope`
**:1010** (:620-631); strong-read block **:329-339** (:219-239); metrics bearer gate **:254-273**
(:163-172). The **substance** all still holds (verified), but the RFC's load-bearing "validated
against the deployed implementation; line citations to that file" claim is now false. A conformance
author following a citation lands in the wrong method. **Fix:** re-anchor citations (or drop line
numbers for method names).

**7.3 — CONTRADICTION (RFC forbids what the code permits: pre-auth pipelining).** AU4-5 states flatly
"**Any frame other than `AUTH` before authentication is a `PROTOCOL_VIOLATION`**." The code
(`EdgeAuthGateHandler.onUnauthenticatedFrame` + `bufferPendingFrame`, :238-299) instead **buffers** a
business frame pipelined *behind* the `AUTH` while the credential resolves off-loop (up to
`MAX_PENDING_PREAUTH_FRAMES=8`) and **replays** it into the session post-auth. Because there is **no
`AUTH-OK` ack**, a driver has no signal to wait on and effectively *must* be allowed to pipeline its
`SUBSCRIBE` right behind the `AUTH`. So the literal RFC rule is both (a) stricter than the code and (b)
un-followable as written. **Fix:** AU4-5/AU4-7 should distinguish "a frame **before** the AUTH ⇒
PROTOCOL_VIOLATION" from "a business frame **pipelined behind** the AUTH ⇒ buffered+replayed (bounded
by 8; overflow ⇒ PROTOCOL_VIOLATION)". This is exactly the kind of behaviour a real client relies on
and the current wording would mis-implement.

**7.4 — GAP/DRIFT (edge collapses "authenticator unavailable" into AUTH_FAIL; asymmetric with HTTP).**
AU5-2 + E2-1 tell a driver to treat a **503-class "authenticator unavailable"** as *retryable* (IdP may
recover). On HTTP this holds — `metrics`/config auth return `503 "authentication temporarily
unavailable"` for `AuthResult.Unavailable` (`AdminApiHandler:258`). But on the **edge**, an
`Unavailable` pre-auth resolves to a wire **`AUTH_FAIL(4)`** (`EdgeAuthGateHandler:271-277`,
`closePreAuthReason(... AUTH_FAIL ... "AUTH_UNAVAILABLE" ...)`) — only a server-side metric
distinguishes; the wire byte is identical to a bad credential. So the edge has **no retryable
auth-unavailable signal**: a driver told `AUTH_FAIL` cannot tell a transient IdP outage from a
permanently-bad credential, and per the RFC's own AUTH_FAIL reaction ("fix the credential") would stop
retrying a recoverable outage. Separately, E2-1's footnote ("a 503-class auth outcome is a *forward*
behaviour… not currently emitted by the in-core auth") is now **stale** — the external-chain HTTP path
*does* emit 503 for `Unavailable`. **Fix:** §07/§03 must state the edge-plane collapse explicitly
(AUTH_FAIL absorbs unavailable; no retryable edge signal) and correct the E2-1 footnote.

**7.5 — AMBIGUITY (codec vs session layer: several "MUST reject" checks are not at the wire layer).**
The RFC attributes several rejections to the wire/codec that the codec does **not** perform — they are
session-layer (and thus only testable against a live session, not the codec/golden vectors):
- **scope / target_kind out-of-range** (W2-4: "an out-of-range `scope` or `target_kind` byte MUST be
  rejected with a 400-class `BAD_SUBSCRIBE`"). The codec accepts any `u8` 0-255 for both
  (`EdgeFrame.WatchCreate` only enforces `FULL ⇒ empty path`); scope∈{3..255}/target_kind∈{3..255}
  decode fine and are rejected (if at all) by the session.
- **WATCH_CREATE path grammar** (W2-4): codec does not validate the §1 path grammar (the
  `watch_create.bin` golden even uses non-absolute `"svc/cfg"`); it is a session-layer `BAD_SUBSCRIBE`.
- **The business version pin (F4)**: `EdgeFrameCodec.decode(byte[])` (no-pin) accepts any of
  01/02/03; the pin is enforced only by `ByteToEdgeFrameDecoder` (transport). Golden/codec tests do
  **not** see the pin.
**Fix / suite note:** these are conformance cases that MUST run against a live server, not the golden
vectors. The RFC should flag which "MUST reject" checks live at the codec vs the session.

**7.6 — AMBIGUITY (unknown `WATCH_CREATE` flag bits: "reject **or** ignore").** W5-4a says a server
"**MUST reject (or ignore, per the bit's rule above)** a flag bit it does not recognize." That is
self-contradictory (reject vs ignore) and unresolved at the wire layer: the codec accepts `flags`
0-255 unconditionally. So the behaviour for bits 3-7 set (or bit1 `prev_value` on a server that does
support it) is **undefined by the spec and unspecified by the codec**. A conformance suite cannot
decide the expected outcome. **Fix:** pick one — recommend "ignore unknown high bits, never populate an
unrequested field" (matches the additive/forward-compat posture) and say so normatively.

**7.7 — NOT-CONFORMANCE-TESTABLE AT v1 (documented, but the suite must know).** Several normative
constructs cannot be triggered against a live v1 static-N server and are only exercisable via codec
byte-vectors (or not at all):
- **`STALE_TOPOLOGY(12)`** — "never fires at v1 static-N (one deploy-time epoch = 1)". No live trigger.
- **`WATCH_CANCELED.oldest` vector (`has_oldest=1`)** — v1 deferral, server always sends
  `has_oldest=0` (W5-9a). The `has_oldest=1` decode path is codec-only.
- **`prev_value` (flag bit1)** — server MAY leave unsupported; there is **no `prev` field** on
  `WATCH_EVENT` in v1. Positive case not testable.
- **Multi-shard watch semantics (W4/W5-4b/W6-5 cross-shard)** — at N=1 the vector is one element and
  the cross-shard caveats are dormant; only an N>1 cluster exercises them.
The suite must cover these with **golden/codec vectors + explicit "unreachable at v1" annotations**,
not live assertions, or it will report false coverage.

**7.8 — GAP (the single-shared-drain footgun is a silent-data-loss trap the RFC buries).** F10-1b: on
one connection only the **first** authorized watch/subscription's cursor positions the shared drain;
every subsequent watch is **started TAIL-from-now and its resume cursor is silently discarded**. A
driver that reconnects and re-`CREATE`s N cursored watches on one connection resumes only #1 and
**drops every event between #2…N's cursor and the live frontier** — with no error. This is a
first-class correctness hazard for a multiplexing client (the whole point of `watch_id` multiplex is
many watches per connection), yet it lives in a single §06 sub-clause and contradicts the §02 framing
of `watch_id` multiplex as the efficient path. **Fix:** promote this to a prominent §02 driver-checklist
MUST ("one connection per independently-resumed watch") and cross-link from W2-8 — the conformance
suite MUST include the negative case (N cursored re-CREATEs on one conn ⇒ only #1 resumes).

**7.9 — MINOR (503-class "authenticator unavailable" not in the streaming `E4-1` table).** E4-1 maps
authn→AUTH_FAIL, session-expiry→CREDENTIAL_EXPIRED, authz→NOT_AUTHORIZED, bad-input→BAD_SUBSCRIBE, but
has **no row** for "authenticator unavailable" on the edge — consistent with 7.4 (it is folded into
AUTH_FAIL), but the table's silence leaves a reader to assume the HTTP 503-retryable behaviour carries
over. Tie to the 7.4 fix.

**7.10 — MINOR (pre-auth first-frame reap uses `PROTOCOL_VIOLATION`, unspecified by §03).** AU4-5/F10-1d
say a connection that authenticates-then-never-subscribes (or connects-then-never-AUTHs) is "reaped",
without naming the close code. The code closes with **`PROTOCOL_VIOLATION`**
(`EdgeAuthGateHandler.armPreAuthDeadline:482-485`, "pre-auth first-frame deadline elapsed"). Harmless,
but a conformance suite asserting a specific reap code needs it spelled out; currently it is
implementation-defined per the RFC.

**7.11 — OBSERVATION (§13 Raft plane not independently re-verified here).** §13 (consensus wire,
non-driver) is internally self-consistent and explicitly out of the driver conformance scope; this
audit did **not** re-verify it byte-for-byte against `FrameCodec.java`/`RaftMessageCodec.java` (it is
not a driver surface). If a second consensus-transport implementation is ever in scope, §13 needs its
own pass. The driver-facing planes (edge §1-§12, HTTP §04) **were** fully cross-checked.

---

*Cross-checked sources: `docs/rfc/driver-protocol/00..07`; `EdgeFrame.java`, `EdgeFrameCodec.java`,
`ErrorCode.java`, `FrameType.java`, `WatchCursor.java`, `EdgeFrameGoldenBytes.java`,
`ByteToEdgeFrameDecoder.java`, `EdgeAuthGateHandler.java`, `AdminApiHandler.java`. Where RFC and code
disagreed, the code was taken as truth and the divergence recorded in §7.*
