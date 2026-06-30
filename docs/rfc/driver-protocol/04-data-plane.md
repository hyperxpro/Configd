# Configd Driver Protocol RFC — §04: The Core Data Plane (Read / Write / Delete)

**Status: DRAFT (2026-06-30). Docs-only; normative.** Fourth section of the Configd driver-protocol RFC.
This section specifies the **core data plane** — the unary `get` / `put` / `delete` operations on the
**control-plane HTTP API** (`/v1/config/{key}`): the request/response wire contract, the **two distinct
cursors** a driver must not confuse (the write `seq` in the body vs. the read version in a header), the
**consistency model** (stale / linearizable / strong-read fail-closed), the `?scope=` contract, the
**path/key-validation rules the server actually enforces** (a superset of §1's grammar, not the whole
grammar), and the **`Content-Type` reality** (plaintext bodies under a misleading `application/json`). It is
written so a driver in **any** language (Rust / Go / Python / Java) performs reads and writes **identically**.

Every clause here is **validated against the deployed implementation**
([`AdminApiHandler.java`](../../../configd-server/src/main/java/io/configd/server/AdminApiHandler.java),
the transport-agnostic decision core both the JDK and Netty HTTP adapters delegate to; line citations are to
that file unless noted). Where this section and a prior RFC claim disagree, **the code wins**. This section is
**normative**; it **composes with**:

- [`01-paths-and-access.md`](01-paths-and-access.md) — the address model (`(scope, path)`), the path grammar,
  the capability/authorization model. §04 references it for `scope`, key validation, and the `READ`/`WRITE`/
  `ADMIN` gating; it does **not** redefine them.
- [`03-authentication.md`](03-authentication.md) — how a unary request is authenticated (bearer; AU3-1).
- `05-routing.md` (**planned; not yet written — this arc**) — what a driver does with a `503` +
  `X-Leader-Hint`; leader-following is **REQUIRED even at N = 1**. Every write and every linearizable read in
  this section can return `503`. **Until §05 lands, the essential rule is self-contained here** (D4-6's
  driver-reaction column + D11-1): on `503` **with** a hint, retry the hinted node; on `503` **without** a
  hint or a `504`/5xx, back off and retry (safe because writes are idempotent LWW, D4-3); do **not** retry
  `400`/`401`/`403`, and honor `Retry-After` on `429`.
- `06-wire-framing.md` (**planned; not yet written — this arc**) — the binary edge plane's framing (not the
  HTTP data plane); referenced only to delimit scope (D2-6).
- `07-errors.md` (**planned; not yet written — this arc**) — the single error/status taxonomy. §04 names the
  status codes it returns **and** gives the required driver reaction inline (D4-6, D3-8, §11); §07 will be the
  consolidated cross-section table. A v1 driver is **not** blocked on §07: every code §04 returns carries its
  reaction here.
- `00-overview.md` (**planned; not yet written — this arc**) — the two-plane architecture + doc map.

Clauses in this section are referenced as **`D<n>-<m>`** (the data-plane clause prefix, parallel to §1's
`A<n>-<m>`, §2's `W<n>-<m>`, §3's `AU<n>-<m>`), so the composed RFC has no clashing identifiers.

---

## 1. Conventions, scope, versioning

### 1.1 Requirement keywords

The keywords **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**,
**MAY**, and **OPTIONAL** are to be interpreted as in RFC 2119 / RFC 8174.

### 1.2 Scope of this section

This section specifies the **unary data operations** on the **control-plane HTTP API**: read (`GET`), write
(`PUT`), and delete (`DELETE`) of a single configuration entry, plus the operational `/health/*` and
`/metrics` endpoints that share the surface ([§10](#10-the-operational-endpoints-health-metrics)). It does
**not** specify the **binary edge plane** (watch / fan-out / streaming reads — that is `02-watches.md` and
the planned `06-wire-framing.md`), which carries **no writes** (D2-6). The HTTP data plane is `scope`-carrying
but **GLOBAL-only in the deployed topology** (D7-4).

### 1.3 Versioning — the `/v1/` path prefix is the only HTTP version signal

**D1-1.** The HTTP API is versioned **solely by the `/v1/` path prefix** (`/v1/config/…`). There is **no**
`Accept`/`Content-Type` version negotiation, **no** version header, and **no** capabilities/hello exchange on
the HTTP surface (`handle`, :130–145, routes on the literal path only). A driver **MUST** address the data
plane under `/v1/` and **MUST NOT** expect to negotiate an HTTP API version. (The *binary edge* version is a
separate, frame-pinned mechanism — planned §06; do not conflate them.)

**D1-2.** A future HTTP API revision will be a new path prefix (`/v2/…`), not a renegotiation of `/v1/`. A
driver **MUST NOT** assume forward-compatible behavior changes within `/v1/`.

---

## 2. The control-plane HTTP surface

**D2-1 (base path).** A configuration entry is addressed at **`/v1/config/{key}`**, where `{key}` is the
**entire** remainder of the request path after the literal prefix `/v1/config/` (`config`, :182–194). `{key}`
is the **percent-decoded** path (`URI.getPath()`, :131) and **is** the storage key — it is **not** normalized,
**not** re-encoded, and **not** lower-cased (the class-level C6/RR-020 note; this is load-bearing for
strong-read classification, D3-5).

**D2-2 (routing).** Routing is **exact-match** for the three fixed endpoints (`/health/live`,
`/health/ready`, `/metrics`) and **prefix-match** for `/v1/config/` (:132–143). Any other path returns
**`404` `Not Found`** (:144). A suffix variant of a fixed endpoint (e.g. `/metricsZ`) does **not** match and
returns `404`.

**D2-3 (methods).** On `/v1/config/{key}`: **`GET`** reads, **`PUT`** writes, **`DELETE`** deletes; any other
method returns **`405` `Method Not Allowed`** (:188–193). The fixed endpoints are **`GET`-only** (`405`
otherwise). A driver **MUST NOT** use `POST`/`PATCH`/`HEAD` on the data plane.

**D2-4 (empty key).** A request to exactly `/v1/config/` (no key) returns **`400` `Missing config key in
path`** (:184–186). **This `400` is emitted at routing time, before authentication** (the pre-auth exception
to D8-4).

**D2-5 (`Content-Type` reality — load-bearing driver trap).** A driver **MUST NOT** assume a response body is
JSON from its `Content-Type`. The deployed surface is:

| Response | `Content-Type` | Body is actually |
|---|---|---|
| Read `200` (value found) | `application/octet-stream` (:265) | **raw value bytes** (opaque) |
| `/health/live`, `/health/ready` (incl. their `503`) | `application/json` (:679) | **real structured JSON** (D10-1) |
| `/metrics` | `text/plain; version=0.0.4; charset=utf-8` (:174) | Prometheus text exposition |
| **Every non-health `4xx`/`5xx`** + write `200` + `404` | `application/json` (:677–681) | a **PLAINTEXT human string** (e.g. `Committed: seq=42`, `Not Found`, `Unauthorized: …`) |

**D2-5a.** The `application/json` `Content-Type` on write-success and error responses is **misleading**: the
body is a plaintext string, **not** JSON (`json(int, String)`, :683–685, wraps a raw string under
`jsonHeaders()`). A driver **MUST** parse those bodies as **plaintext** (or ignore them and use the status
code + headers), and **MUST NOT** `JSON.parse` them. Only `/health/*` bodies are valid JSON (D10-1). Error
bodies are **opaque diagnostic text** and **MAY echo attacker-influenced input verbatim** — they can contain
the request key (e.g. `Access denied: insufficient permissions for key '<key>'`, :505; the fail-closed body,
:286) or value/policy text (`Invalid ACL policy: <detail>`, :337), and are **not** HTML/JSON-escaped on this
surface (`escapeJson` applies only to `/health` bodies, :699). A driver **MUST NOT** render an error body as
markup (a reflected-input hazard); treat it as a logging/diagnostic string only.

**D2-6 (no writes on the edge plane).** Writes exist **only** on this HTTP plane. The binary edge plane
(§02/planned §06) is read/watch/fan-out only. A driver that needs to `put`/`delete` **MUST** use the HTTP API.

**D2-7 (no `201`/`204`).** A successful write or delete is **`200`** with a body, never `201`/`204`; a
read of a 0-length value is **`200`** with a 0-length `application/octet-stream` body (the `found` flag, not
the length, distinguishes present-but-empty from absent — D3-3). A driver **MUST NOT** treat a 0-length body
as "not found".

---

## 3. Read — `GET /v1/config/{key}`

A `GET` honors `?scope=` (D7) like every data operation; absent ⇒ `GLOBAL`.

**D3-1 (success).** A successful read returns **`200`** with the **raw value bytes** as the body and
`Content-Type: application/octet-stream` (:264–271). The value is **opaque** — Configd stores and returns
bytes verbatim; it does not interpret, transcode, or validate value content.

**D3-2 (response headers).** A `200` read carries:

| Header | Value | Meaning |
|---|---|---|
| `X-Config-Version` | a decimal `uint64` (:266) | the **key's version** — a value drawn from the per-shard applied-mutation sequence (the seq of the key's last write; **not** a per-key counter — D6) |
| `X-Consistency` | `linearizable` \| `stale` (:267) | how this response was **requested**, not a per-read proof (D3-2a) |
| `X-Strong-Read` | `true` (present **only** for a strong-read key, :268–270) | this key was force-served linearizably (D3-5) |

A driver **MUST** read `X-Config-Version` from the **header** on a read (contrast the write cursor, which is
in the **body** — D4-2, D6-1).

**D3-2a (`X-Consistency` is a requested-mode echo for ordinary keys).** For an **ordinary** (non-strong-read)
key, `X-Consistency: linearizable` reports that the linearizable mode was **requested** (and a linearizable
path was attempted), **not** that the value is provably linearized. In a **stale-only deployment** (no
linearizable read path wired) an ordinary-key `?consistency=linearizable` request is served from the local
stale store yet still labeled `X-Consistency: linearizable` (the `linearizable` flag is set from the request,
:226, while the value comes from the stale branch, :258). A driver **MUST NOT** treat `X-Consistency:
linearizable` on a **non-`secure/`** key as proof of freshness. The **only** header that certifies a
leader-confirmed-fresh read is **`X-Strong-Read: true`** on a strong-read key (D3-5).

**D3-3 (not found).** A key absent from the store returns **`404` `Not Found`** (:261–263). A present key with
an empty value returns `200` with a 0-length body (D2-7) — distinguishable from `404` by the status code.

**D3-4 (consistency selection — the loose-substring trap, both directions).** The default read is **stale**
(bounded-staleness, served locally). A driver opts into a **linearizable** read with the query parameter
**`?consistency=linearizable`**. **The server matches this with a loose substring test** —
`query.contains("consistency=linearizable")` (:225) — **not** an exact parameter parse. A driver **MUST**
account for both failure directions:

- **Under-trigger:** any value other than the exact literal `consistency=linearizable` — including
  `?consistency=stale` — is **not** recognized and yields the **stale** path (there is no explicit "stale"
  keyword; stale is simply the absence of the match). Emit exactly `consistency=linearizable`.
- **Over-trigger:** the test matches the literal substring **anywhere** in the raw query string — inside
  another parameter's name or value (e.g. `?myconsistency=linearizable`, or `?tag=consistency=linearizable`)
  **inadvertently selects the linearizable path**. A driver that lets callers compose query strings **MUST
  NOT** allow the literal `consistency=linearizable` to appear anywhere except as the standalone intended
  parameter (a silent, costlier linearizable read otherwise).
- This is **asymmetric** with `?scope=`, which is parsed by an **exact** parameter match (D7-3). A driver
  **MUST NOT** model the two parameters with one parser; `scope` is exact, `consistency` is a substring.

**D3-5 (strong-read keys — force-linearizable, fail-closed; a deployment-configured class).** A key in the
**strong-read class** is **always** served by the linearizable path, **regardless of `?consistency=`** (the
requested consistency is **ignored** for these keys, :219–239). Such a read returns `X-Strong-Read: true` +
`X-Consistency: linearizable` on success, and if the linearizable read **cannot be confirmed** (this node is
not the leader / the ReadIndex is unconfirmed, or no linearizable path is wired) **fails closed** with
**`503`** + **`X-Fail-Closed: strong-read`** and (when a leader is known) `X-Leader-Hint` (`failClosed`,
:279–291). **A stale value is NEVER served for a strong-read key.**

**D3-5a (the strong-read class is server-side config — rely on the header, not the name).** The strong-read
class is an **operator-configured prefix set**, default **`secure/`** (`StrongReadPolicy` ←
`config.strongReadPrefixes()`; default `StrongReadKeyClass.DEFAULT_PREFIX`). **A deployment MAY reconfigure or
entirely disable it** (an empty prefix set disables strong-read enforcement — `StrongReadPolicy` javadoc; a
`secure/`-named key in a disabled deployment falls to the **stale** path with **no** `X-Strong-Read` and **no**
fail-close). Therefore a driver relying on fail-closed freshness for a kill-switch / credential-revocation key
**MUST** confirm the response actually carries **`X-Strong-Read: true`** and **`X-Consistency: linearizable`**
(D3-2) — it **MUST NOT** infer the guarantee from the `secure/` name alone.

**D3-5b (what strong-read guarantees — freshness, not confidentiality).** A strong-read `200` guarantees the
value reflects a **linearization point confirmed at the current leader** (ADR-0030 INV-1): it is not a
bounded-stale local copy. The guarantee is **freshness, not confidentiality** — strong-read / `secure/` values
are stored **plaintext at rest** (integrity-checked only; at-rest encryption is a v2 item). A driver **MUST
NOT** treat `secure/` as encrypted and **MUST NOT** store secrets expecting confidentiality. The contract is:
*for a classified strong-read key you either get a leader-confirmed-fresh value, or a `503` — never a stale
one.*

**D3-6 (linearizable on an ordinary key that can't be served — only where a linearizable path is wired).** In
a deployment with a linearizable read path wired, if a driver requests `?consistency=linearizable` on an
**ordinary** (non-strong-read) key and this node cannot serve it linearizably, the server returns **`503` `Not
Leader - cannot serve linearizable read`** + `X-Leader-Hint` (when a leader is known) (:240–251). This is
**distinct** from a strong-read fail-close: **`X-Fail-Closed` is absent**, because a stale read of an ordinary
key is contract-permitted. A driver **MAY** follow the hint (planned §05) and retry, **or** fall back to a
stale read of the same key — both are valid; the server has refused only the *linearizable* read, not the key.
(In a **stale-only** deployment this `503` does not occur — the request is served stale and mislabeled per
D3-2a.)

**D3-7 (authorization).** A read requires the **`READ`** capability on the key (`checkAuth(…, READ)`, :197).
**When authentication is configured**, a key under a reserved prefix (`_acl/`, `_system/`) requires
**`ADMIN`** for **every** method including `GET` (:477–525, closing policy disclosure). *(When auth is
**disabled** — the loudly-warned non-production mode — the gate is open and a reserved `GET` is permitted;
only a reserved **WRITE** is refused. So `_acl/` disclosure is ADMIN-protected only when auth is on, mirroring
D10-2.)* Authorization failures are `401`/`403` (§07; see §1 §5–§7 and §3). A read failure (`401`/`403`) is
audited; a successful read is **not** audited per-event (a DoS concern).

**D3-8 (read is side-effect-free and safe to retry).** A `GET` never mutates. A driver **MAY** retry any read
freely (subject to planned-§05 backoff). A `503` or a transport timeout on a read means "couldn't serve now"
— re-read. (The application never returns `504` on a `GET`; `504` is a write-only outcome, D4-6/D4-8.)

---

## 4. Write — `PUT /v1/config/{key}`

**D4-1 (request body = the value).** The `PUT` body is the **raw value bytes** to store (≤ **1 MiB** =
1 048 576 bytes — D8-3). An **empty body** is rejected: **`400` `Request body must not be empty`** (:319–323).
A driver that needs to "set empty" cannot do so via `PUT` (use the value's own sentinel). The body is read
**only after** auth + replay have passed (:319), so an unauthenticated body is never drained.

**D4-2 (success — the cursor is in the BODY, the driver trap).** A committed write returns **`200`** with the
**plaintext body `Committed: seq=<N>`** (`writeResult` → `json(200, "Committed: seq=" + c.seq())`, :385). **The
applied-mutation cursor `seq` is in the response BODY, not a header.** A driver **MUST** parse `<N>` out of the
body (e.g. match `^Committed: seq=(\d+)$`) to learn the write's `seq`. There is **no** `X-Config-Version` (or
any other) header on a write response — contrast the read, where the version is a header (D3-2). This
body-vs-header asymmetry is the single most common data-plane driver bug; see §6 for the cursor model.

**D4-3 (idempotent last-writer-wins).** A `put` is an idempotent **LWW** upsert: re-applying the identical
`PUT` after an indeterminate outcome (`504`/timeout, D4-6/D4-8) is **safe** — it overwrites with the same
value and yields a (possibly new) `seq`. This idempotency is what makes the planned-§05 retry contract safe.
A driver **MUST NOT** assume a `put` is a compare-and-set; there is **no** conditional-write / `If-Match` in v1
(D11-4). *(If the optional replay guard is **enabled** — D11-3 — a retried mutation **MUST** carry a **fresh**
`X-Configd-Timestamp` + `X-Configd-Nonce`; re-sending the original stamp/nonce is rejected `409`, not
re-committed.)*

**D4-4 (scope).** A `PUT` honors `?scope=` (D7). Absent ⇒ `GLOBAL`.

**D4-5 (`_acl/` write-time validation).** A `PUT` whose key starts with `_acl/` is **validated as a policy at
write time** (`AclConfigPolicyLoader.validateAclWrite`, :332–339) using the **identical** parser the reload
path uses; a malformed policy or a reserved-name violation returns **`400` `Invalid ACL policy: <detail>`**
**pre-commit** (the store is unchanged). A well-formed-but-incomplete policy (e.g. a binding to a not-yet-
defined role) is intentionally **not** an error. `_acl/` (and `_system/`) writes require **`ADMIN`** (D3-7).
A driver writing policy **MUST** be prepared for a `400` that is a *policy-shape* rejection, distinct from a
key/value-limit `400`.

**D4-6 (write outcomes → status).** A write maps to exactly one status (`writeResult`, :383–414):

| Outcome | Status | Body / headers | Driver reaction |
|---|---|---|---|
| committed | `200` | `Committed: seq=<N>` | done; record `seq` (D6) |
| not leader | `503` | `Not Leader (leader=<id>)` + `X-Leader-Hint: <id>` *(hint present only when a leader is known)* | follow hint if present, else back off; retry (idempotent) |
| lost leadership pre-commit | `503` | `Lost leadership before commit …` + `X-Leader-Hint` *(when known)* | safe to retry — **definitely did not commit** |
| outcome unknown at deadline | `504` | `Commit unconfirmed … safe to retry or re-read` | **indeterminate — see D4-8** |
| validation failed | `400` | `Validation failed: <reason>` | permanent — fix the request |
| overloaded | `429` | `Overloaded` + `Retry-After: 1` | back off `Retry-After`, retry |

**D4-7 (`200` means committed-and-applied).** A `200` is returned **only** after the entry is
**quorum-committed AND applied** (RR-004 / ADR-0033; `Committed` is the only path to `200`, :384–385). It is
**not** an "accepted/enqueued" ack. A driver may treat `200` as durable.

**D4-8 (the `504` Indeterminate contract — a write that MAY commit later).** A `504` is the
`WriteResult.Indeterminate` outcome (:402): the write deadline expired with the outcome **unknown**. Per the
implementation's own contract (`ConfigWriteService.WriteResult.Indeterminate`), **the write MAY still commit
at an arbitrary later time** — the timeout path cancels the local callback but leaves any appended log entry
in place (`ConfigdServer` buildProposer; an isolated leader may apply it later). Consequences a driver **MUST**
respect:

- A `504` is **not** a failure. It is distinct from `Lost` (`503`, *definitely did not commit*) and
  `NotLeader` (`503`, pre-append). **Lost / NotLeader = safe to retry because it did not commit;
  Indeterminate = safe to retry because the write is idempotent LWW — but the original may still land.**
- **A re-read that does not show the write is NOT proof it failed.** A `504` write can commit *after* the
  re-read. The only *definite* resolutions are: a subsequent `200` for the retried write, a `Lost` `503`, or
  observing the value via a **linearizable**/strong-read (D3-5) read — and even then a late commit can follow.
- The safe driver action is **idempotent-LWW retry until a definite outcome**, **not** "re-read to decide."
- Because v1 has **no** conditional write (D11-4), a driver **MUST NOT** build a read-modify-write across an
  unresolved `504` (a stale "the write failed" decision can be overwritten by the late ghost commit, losing a
  concurrent writer's update).

---

## 5. Delete — `DELETE /v1/config/{key}`

**D5-1 (success).** A delete returns **`200`** with the **same** plaintext body shape as a write —
`Committed: seq=<N>` (`handleDelete` → `writeResult`, :373–376) — carrying its own applied-mutation `seq`.
The outcome→status mapping is **identical** to a write (D4-6/D4-8: `503`+hint / `504` / `429` all apply,
including the `504` ghost-commit semantics).

**D5-2 (no request body).** `DELETE` takes **no** body; any body sent is ignored (`handleDelete` never reads
`req.body()`). A driver **MUST NOT** depend on a delete body.

**D5-3 (asymmetry vs. `PUT` — documented).** Unlike `PUT`, `DELETE` does **not** run the empty-body check
(n/a) and does **not** run `_acl/` policy validation (`handleDelete`, :347–377, has no `validateAclWrite`
call). Consequently a driver **can** `DELETE` an `_acl/` policy key — **but it still requires `ADMIN`** (the
reserved-prefix gate, D3-7, applies to **all** methods). So: deleting policy is ADMIN-gated but **not**
re-validated; writing policy is ADMIN-gated **and** shape-validated (D4-5).

**D5-4 (idempotent; no existence pre-check).** `DELETE` does **not** pre-check existence; it commits a delete
mutation and returns `200 seq` even for an already-absent key (LWW tombstone semantics). A driver **MUST NOT**
infer "the key existed" from a delete `200`. Deleting is idempotent and safe to retry (D4-3).

**D5-5 (no recursive/subtree delete).** v1 defines **no** recursive or subtree delete; a subtree spans all
shards and Configd offers no cross-shard atomicity. A driver **MUST** delete leaves individually (§1 A4-7).

---

## 6. The two cursors — `seq` (write, body) vs. version (read, header)

The single most important data-plane model for a driver.

**D6-1 (two surfaces, two places).** There are two cursor surfaces:

- the **write cursor** `seq` — returned in the **body** of a `PUT`/`DELETE` `200` as `Committed: seq=<N>`
  (D4-2);
- the **read version** — returned in the **header** `X-Config-Version` of a `GET` `200` (D3-2).

A driver **MUST** read each from its stated place: `seq` from the write **body**, version from the read
**header**.

**D6-2 (they are the same per-shard sequence).** Per the implementation, both are the **same monotonic
per-shard applied-mutation sequence**. When a write applies, the store assigns the key's version to **be** the
applied-mutation sequence of that write (`VersionedConfigStore.put(key, value, sequence)` stores
`version = sequence`; `get` returns that version). Therefore a `PUT` that returns `seq=N` means an immediate
**linearizable** `GET` of that key (or one served by the leader) returns **`X-Config-Version: N`**, until the
key is next written. (A default **stale** `GET` MAY lag and return a version `< N` or `404` — D6-4.) The names
differ (`seq` vs. version) but the number space is **one**.

**D6-3 (per-shard, not per-key, not global).** The sequence is monotonic **per shard** (per Raft group), and is
**strictly increasing across all writes that shard applies** — not per-key, and **not** ordered across shards.
At **N = 1** there is one shard, so it is a single global sequence. A driver **MUST NOT** compare `seq`/version
values **across shards** for ordering (at N > 1 two keys on different shards have incomparable sequences — §1
A4-2, planned-§05 no-client-sharding). A driver **MUST** treat the value as an **opaque monotonic `uint64`**
within a shard and **MUST NOT** assume it increments by 1 between two writes to the same key (it advances by
the shard's total mutation count, so gaps are normal).

**D6-4 (read-your-writes — what the cursor does and does not buy you).** A driver **MAY** remember the `seq`
from a write and detect read-your-writes by comparing it to a later `GET`'s `X-Config-Version`
(`version ≥ seq` ⇒ the write is visible). **However**, the HTTP read API exposes **no** "read at version ≥ N"
parameter — the store supports a min-version read internally (`get(key, minVersion)`) but it is **not wired to
HTTP**. So a driver **cannot** request a bounded-staleness "wait for my write" read. To **guarantee**
read-your-writes, a driver **MUST** either read with `?consistency=linearizable` (D3-4, in a deployment with a
linearizable path) — which serves from the leader's linearization point — or use a strong-read (`secure/`) key
(D3-5), and/or follow the leader hint (planned §05). A plain stale read **MAY** lag and return a version
`< seq`.

**D6-5 (the cursor relationship is the same one §1/§2 use — a modeling reuse, not an on-wire vector here).**
This `(shard, seq)` sequence is the scalar component of the **per-shard cursor vector** that §1 (`list`
pagination, A4-4) and §2 (watch resume, §3) build on. The **unary HTTP surface carries no cursor vector on the
wire** — a single key is always exactly one shard, so the unary `seq`/version is a bare scalar and the
"scalar-FORBIDDEN-even-at-N=1" **MUST** of §1 A9-1 / §2 W1-1 (which targets the *multi-shard* list/watch
cursors) is **not** in play here. A driver **SHOULD** nonetheless model the unary `seq`/version as the `S` of a
single `(gid, S)` pair and reuse the one cursor-vector type across the unary, list, and watch surfaces, so
moving to list/watch needs no new type — but this is a code-reuse **SHOULD**, distinct from the on-wire
vector-native **MUST** of §1/§2.

---

## 7. Scope — `?scope=`

**D7-1 (the parameter).** All three data operations accept an optional **`?scope=`** query parameter,
parsed **case-insensitively** into the enum **`{ GLOBAL, REGIONAL, LOCAL }`** (`parseScope`, :620–631). It is
the typed replication-domain axis of §1 A2-1.

**D7-2 (default and routing-only).** Absent or blank ⇒ **`GLOBAL`** (:622–623; the §1 A2-3 default,
byte-identical to the pre-scope GLOBAL-pinned surface). `scope` is a **routing input only**: it selects the
owning shard/replication domain and is **never echoed** in a response header and **never** stored as part of
the key (the path string alone is the key — D2-1). A driver **MUST NOT** encode `scope` as a path segment
(§1 A2-1).

**D7-3 (unknown ⇒ fail-closed `400`; exact match).** An unrecognized `scope` value returns **`400` `Unknown
scope '<v>' (expected GLOBAL, REGIONAL, or LOCAL)`** (:627–630) — fail-closed, never silently coerced. The
parameter name `scope` is matched by an **exact** parse (`queryParam`, case-sensitive name match, :659–671),
**unlike** the loose `consistency=` substring (D3-4). A driver **MUST NOT** assume `scope` tolerates the same
sloppiness.

**D7-4 (deployed topology is GLOBAL-only).** Although `REGIONAL`/`LOCAL` are accepted and routed, the deployed
control-plane topology is effectively **single-scope GLOBAL** today (§1 A8-2). A driver **SHOULD** default to
`GLOBAL` for the HTTP plane and **MUST** treat `REGIONAL`/`LOCAL` support as deployment-dependent.

---

## 8. Path / key validation — the SUPERSET the server enforces (not the whole §1 grammar)

**D8-1 (what the server enforces — exactly two rules).** On the HTTP data plane the server validates a key
with **only**: **non-blank** AND **≤ 1024 bytes UTF-8** (`keyValidationReason`, :642–652). That is the
complete server-enforced key contract. The check is **length-before-blank** (a key that is both over-length
and blank yields the length `400`), and a violation returns **`400`** with that reason.

**D8-2 (what the server does NOT enforce — §1's strict grammar is client-side).** The server does **NOT**
apply §1's strict path grammar (absolute, `seg-char`-only, canonical normalization, no `.`/`..`) to these
legacy flat keys. That grammar is a **client-side contract** (§1 A3) the server deliberately does not enforce
here (the raw decoded key is load-bearing for strong-read classification, D2-1). **A driver MUST know which
rules are server-enforced vs. client-side:**

- **Server-enforced** (rejected with `400` if violated): non-blank, ≤ 1024 B key; ≤ 1 MiB value (D8-3).
- **Client-side** (the driver must enforce for cross-driver interop; the server will *accept* a violating
  legacy key): the §1 A3 path grammar and normalization.

A driver targeting the §1 address model **MUST** validate paths against §1 A3 **itself** before sending; a
driver offering a raw legacy-key mode **MUST** document that it sends keys verbatim under only D8-1.

**D8-3 (value limit).** A value **MUST NOT** exceed **1 MiB** (1 048 576 bytes); the server rejects an
over-limit value as `ValidationFailed` → **`400`** (`ConfigWriteService.put`). The key length limit (1024 B)
is enforced both at the edge (D8-1) and in the write service.

**D8-4 (validation order — post-auth, with one pre-auth exception).** Key/scope validation runs **after**
authentication/authorization, so an unauthenticated bad-key request gets `401`/`403` **before** any `400`
(a blank-but-present key such as `%20` is 401/403'd first). **The one exception** is the D2-4 empty-key `400`
(`/v1/config/` with no key at all), which is emitted at routing time **before** `checkAuth`. A driver
distinguishing "bad key" from "forbidden" **MUST NOT** model *all* `400`s as post-auth.

---

## 9. `list` — EXPLICITLY DEFERRED (no endpoint exists in v1)

**D9-1.** v1 ships **no** `list`/enumeration endpoint on the HTTP plane (no route in `handle`, :130–145;
O-2 deferred). The **semantic** model — `CHILDREN` vs. `RECURSIVE` enumeration, pagination via the per-shard
cursor vector, the `LIST` capability — is specified in **§1 §4.2** (A4-3…A4-6), but **no wire is built**. A
driver **MUST NOT** assume a `list` wire, synthesize one, or emulate `list` by guessing keys. When `list`
ships it will be a **new endpoint** using the §1 A4-4 cursor-vector pagination; until then enumeration is
**not available** on the data plane. (Watches — §02 — are the built way to observe a set of keys.)

---

## 10. The operational endpoints (`/health/*`, `/metrics`)

These share the HTTP surface but are not the data plane; specified for completeness.

**D10-1 (`/health/live`, `/health/ready`).** `GET`-only (`405` otherwise). Each returns **real structured
JSON** `{"healthy":<bool>,"checks":[{"name":…,"healthy":<bool>,"detail":…}]}` under `Content-Type:
application/json` (the header from `jsonHeaders()`, :679; the JSON body built by `formatHealthStatus`,
:691–705), with status **`200`** when healthy and **`503`** when not (:155–156) — the `503` body is **also**
real JSON (the exception to D2-5's plaintext rule). These endpoints are **not authenticated**. A driver
**MAY** use `/health/ready` for readiness gating and `/health/live` for liveness; this is the **only** place a
driver should `JSON.parse` a response body (contrast D2-5).

**D10-2 (`/metrics`).** `GET`-only. Returns Prometheus text exposition under `Content-Type: text/plain;
version=0.0.4; charset=utf-8` (:173–175). When authentication is configured, `/metrics` is **bearer-gated**:
a missing/invalid token returns **`401` + `WWW-Authenticate: Bearer`** (this is authentication — there is no
ACL for scraping; the token is never echoed, :163–172). The path is **exact-match** (`/metricsZ` ⇒ `404`,
D2-2). A driver typically does not scrape `/metrics`; an operator's collector does.

---

## 11. Composition and forward-compatibility

**D11-1 (every write/strong-read can redirect — leader-following is mandatory, even at N = 1).** Any
`PUT`/`DELETE`, any `?consistency=linearizable` read, and any strong-read can return **`503`** (D3-5, D3-6,
D4-6). A driver **MUST** implement a leader-follow + backoff-retry loop (the full contract is the planned §05;
the **essentials are inline here**, D4-6's reaction column). Crucially, the `503` **MAY omit `X-Leader-Hint`**
when the leader is unknown — e.g. **during an election**, which is the **normal N = 1 case** (a single node's
only `503` window is pre-election; once elected it stays leader and writes succeed, and `raftNode.leaderId()`
is `null` until then, so the hint is absent). A driver **MUST** handle a **hintless `503`** by **backing off
and retrying the same endpoint** (there is no distinct leader to follow); it **MUST NOT** require the
`X-Leader-Hint` header to be present on a `503`. Leader-following is therefore **not** an N > 1-only concern.

**D11-2 (errors).** The status codes named here (`200`/`400`/`401`/`403`/`404`/`405`/`429`/`503`/`504`, plus
`409` under the optional replay guard, D11-3) each carry their required driver reaction **inline** (D4-6,
D3-8, D11-1). The planned §07 will consolidate the cross-section taxonomy in one table; a v1 driver is **not**
blocked on it.

**D11-3 (optional replay guard — `409`/`401`).** A deployment **MAY** enable an opt-in replay guard
(`-Dconfigd.replay.enabled`, default **off**; `replayRejected`, :581–601) on mutating requests. When on, a
`PUT`/`DELETE` **MAY** carry `X-Configd-Timestamp` (epoch ms) + `X-Configd-Nonce`; a stale/missing stamp ⇒
**`401`**, a replayed nonce ⇒ **`409` `Conflict: replayed request (nonce already seen)`**. **No client
populates these today.** A driver **MAY** support them as an OPTIONAL capability; absent the headers under an
enabled guard, a mutating request is rejected `401`, and a retry **MUST** use a **fresh** timestamp+nonce
(D4-3). **Trust model (honesty — do not overstate):** the guard defends only against a **passive** attacker
re-sending a captured request **verbatim**; it does **NOT** stop a holder of the bearer token from minting a
**fresh** request (new nonce + current timestamp). Per-request integrity against an active token-holder needs
content signing (an S8 item, not built). A driver/operator **MUST NOT** treat the replay guard as mitigating a
stolen token.

**D11-4 (named forward extensions — fail closed).** The following are **named** v1 omissions a driver **MUST
NOT** assume: a `list` endpoint (D9-1); a "read at version ≥ N" / bounded-staleness read parameter (D6-4); a
**conditional write** (`If-Match` on `X-Config-Version` / CAS) — v1 `put` is unconditional LWW (D4-3); a
**batch** multi-key write (the proposer guards cross-shard batches but the HTTP path is single-key); a
structured-JSON response body (D2-5). A driver **MUST** fail closed on a status/header/parameter it does not
recognize (§1 A1.3 fail-closed-on-unknown).

---

## 12. Summary of normative requirements (driver checklist)

- [ ] Address `/v1/config/{key}`; `{key}` is the percent-decoded path tail, **un-normalized** = the storage
      key; HTTP version is the `/v1/` prefix only (no negotiation) (D1-1, D2-1).
- [ ] `GET`=read, `PUT`=write, `DELETE`=delete; other methods `405`; `/health/*` + `/metrics` are `GET`-only
      (D2-3).
- [ ] **Do not `JSON.parse` a non-`/health` body** — error/write bodies are **plaintext** under a misleading
      `application/json`, MAY echo your request key/value, and MUST NOT be rendered as markup; a read `200` is
      raw `application/octet-stream` (D2-5, D2-5a).
- [ ] Read version is the **header `X-Config-Version`**; write/delete `seq` is in the **body**
      `Committed: seq=<N>` — parse it out (D3-2, D4-2, D6-1).
- [ ] `?consistency=linearizable` is a **loose substring** match (send exactly that, and never let it appear
      elsewhere in the query); `?scope=` is an **exact** match — do not parse them the same way (D3-4, D7-3).
- [ ] A **strong-read** key (operator-classified, default-`secure/`, **may be disabled**) is force-linearizable
      and **fails closed `503` `X-Fail-Closed: strong-read`** rather than serve stale; **verify
      `X-Strong-Read: true` + `X-Consistency: linearizable`** rather than trust the key name; it is
      **freshness, not encryption** (D3-5, D3-5a, D3-5b).
- [ ] `X-Consistency: linearizable` on a **non-`secure/`** key is a requested-mode echo, **not** proof of
      freshness (a stale-only deployment may serve it stale) (D3-2a).
- [ ] Treat `seq`/version as **one opaque monotonic per-shard `uint64`**; never compare across shards; gaps
      are normal (D6-2, D6-3).
- [ ] For read-your-writes use `?consistency=linearizable` / a strong-read key — there is **no** read-at-version
      parameter (D6-4).
- [ ] `?scope=` absent ⇒ `GLOBAL`; unknown ⇒ `400` fail-closed; routing-only, never echoed/stored (D7).
- [ ] Server enforces only **non-blank + ≤ 1024 B** key and **≤ 1 MiB** value; the §1 path grammar is
      **client-side** — enforce it yourself for interop (D8).
- [ ] `PUT` empty body ⇒ `400`; `put`/`delete` are **idempotent LWW**; a **`504` is indeterminate — the write
      MAY commit later**, so retry to a definite outcome rather than treating a negative re-read as proof, and
      do **not** read-modify-write across an unresolved `504` (D4-1, D4-3, D4-8).
- [ ] `200` = committed-and-applied (durable); `Lost`/`NotLeader` `503` = definitely-not-committed (D4-7, D4-8).
- [ ] `list` is **DEFERRED** — no endpoint; do not synthesize one (D9-1).
- [ ] Implement a leader-follow + **backoff** retry loop — every write/strong-read can `503`, **even at N = 1**,
      and that `503` **may carry no `X-Leader-Hint`** (back off and retry the same endpoint) (D11-1).
