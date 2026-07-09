# Configd Driver Protocol RFC — §05: Routing, Leader-Following & Topology

**Status: DRAFT (2026-06-30). Docs-only; normative.** Fifth section of the Configd driver-protocol RFC.
This section specifies how a driver **routes** a unary control-plane request to the node that can serve it and
how it **follows a redirect** when it hits the wrong one: the advisory **`X-Leader-Hint`** mechanism (there is
**no** HTTP 3xx redirect), the **operator-provided `NodeId → address` map** (there is **no** wire topology
discovery), why **leader-following is REQUIRED even at N = 1**, why a driver does **no client-side sharding**,
the **retry / backoff / idempotency** contract for the unary plane, and the **security boundary** a redirect
implies. It is written so a driver in **any** language routes and retries **identically**, and so a
single-shard (N = 1) v1 driver is **forward-compatible** to a sharded (N > 1) cluster with **no code change**.

Every clause is **validated against the deployed implementation**: the `503`/`X-Leader-Hint` emission in
[`AdminApiHandler.java`](../../../configd-server/src/main/java/io/configd/server/AdminApiHandler.java)
(`writeResult` :383–414, `handleGet` :240–251, `failClosed` :279–291), the **shard/scope-resolved** hint
suppliers in [`ConfigdServer.java`](../../../configd-server/src/main/java/io/configd/server/ConfigdServer.java)
(the read GET lambda :946–959 and the write proposer hint :793–797 → `ConfigWriteService.mapOutcome` :332–342
— the read supplier comment notes it "mirrors the scope-aware write redirect"), the server-side `shardFor` in
`StaticShardMap`, the `--api-port` / `--bind-port` / `--peers` / `--peer-addresses` configuration in
`ServerConfig.java`, and the absence of any topology endpoint in `AdminApiHandler.handle` (:214–233). Where
this section and a prior RFC claim disagree, **the code wins**. This section is **normative**; it **composes
with**:

- [`04-data-plane.md`](04-data-plane.md) — the operations that produce a redirect. Every `PUT`/`DELETE`, every
  `?consistency=linearizable` read, and every strong-read can return `503` (§04 D3-5, D3-6, D4-6); §05 says
  what a driver **does** with it. Error-body handling (plaintext, unescaped, reflected) is §04 D2-5a.
- [`01-paths-and-access.md`](01-paths-and-access.md) — the `(scope, path)` address and the hash-sharded
  keyspace (A2-4). §05 relies on the fact that a subtree scatters and the shard hash is **server-side**.
- [`03-authentication.md`](03-authentication.md) — the credential a driver presents (and re-presents to a
  hinted node, §8); the `401` boundary.
- [`07-errors.md`](07-errors.md) — the consolidated per-code taxonomy. §05 gives the
  routing-relevant driver reaction per status inline (§6); §07 will be the cross-section table. A v1 driver is
  **not** blocked on §07.

Clauses are referenced as **`R<n>-<m>`** (the routing-section clause prefix; parallel to §1 `A`, §2 `W`,
§3 `AU`, §4 `D`), so the composed RFC has no clashing identifiers.

---

## 1. Conventions, scope, versioning

### 1.1 Requirement keywords

The keywords **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**,
**MAY**, and **OPTIONAL** are to be interpreted as in RFC 2119 / RFC 8174.

### 1.2 Scope

This section is the **unary control-plane (HTTP)** routing/redirect/retry contract. It applies to **both**
N = 1 and N > 1 — the routing logic is identical (R5). The binary edge plane has its own connection/session
model (§02 / §06) and is out of scope here.

### 1.3 Versioning

Routing has **no** version negotiation; it is a property of the `/v1/` HTTP surface (§04 D1-1). A future
routing change rides a new path prefix, not a renegotiation of `/v1/`.

---

## 2. No HTTP redirect — the advisory `X-Leader-Hint`

**R2-1 (no `3xx`, no `Location`; read the hint from the header only).** Configd does **NOT** use an HTTP `3xx`
redirect or a `Location` header. Redirection is advisory, in-band, via the **`X-Leader-Hint`** response
**header** on a **`503`** (`writeResult`/`handleGet`/`failClosed`). A driver **MUST** read the leader from the
**`X-Leader-Hint` header** and **MUST NOT** parse the `503`/`504` **body** for it: the body is plaintext under
a misleading `application/json` (§04 D2-5a), it renders the leader as `Node-<id>` (`NodeId.toString()`) — **not**
the bare numeric form the header carries — and it may echo your request key verbatim (unescaped). Treat the
body as opaque diagnostic text only.

**R2-2 (the hint is a bare numeric `NodeId`, not an address).** `X-Leader-Hint`'s value is a **bare decimal
`NodeId`** — `String.valueOf(nodeId.id())` (`writeResult` :388–389/:396–397, `handleGet` :247–249, `failClosed`
:283–285) — e.g. `X-Leader-Hint: 3`. It is **NOT** a `host:port`. **The wire never supplies a network
address.** Therefore a driver **MUST** resolve the `NodeId` to a connection target through its own out-of-band
`NodeId → address` map (R3). *(This is the section's **anti-SSRF invariant**: because the hint is an opaque
`NodeId` resolved only through the operator's map, a `503` — even one forged by a compromised node — can never
redirect a driver to an attacker-chosen address. A driver **MUST NOT** "optimize" this into accepting a
wire-supplied address; §8.)*

**R2-3 (the hint is advisory and MAY be absent).** Every hint is added **only when a leader is known**
(`if (hint != null)` / `if (leaderId() != null)` at each site). When the leader is unknown — most importantly
**during an election**, which is the **normal N = 1 `503` window** (R4-2) — the `503` carries **no**
`X-Leader-Hint`. A driver **MUST** handle a **hintless `503`** (R4-2, R6) and **MUST NOT** require the header
to be present.

**R2-4 (`503` vs `504` — and the `504` correction).** The redirect hint rides the **`503`** family only:

- **`503`** (`NotLeader`, `Lost`, strong-read fail-closed, **ordinary-key linearizable-unavailable**) — "this
  node can't serve it now"; carries `X-Leader-Hint` **when a leader is known** (R2-3). Driver: progress toward
  the hinted leader (R4-3, R6-1) or, if hintless, back off + retry the same endpoint (R4-2).
- **`504`** (`WriteResult.Indeterminate`, `json(504, …)` :402) carries **no** `X-Leader-Hint` and is **not** a
  redirect — it is the *outcome-unknown* write result (§04 D4-8: the write **may still commit later**). A
  driver **MUST NOT** look for a hint on a `504`; it re-reads / retries-to-definite per §04 D4-8.

  > *Correction (code wins): a `504` is not hint-bearing. The redirect surface is `503` only. A driver MUST
  > NOT treat a `504` as "follow a leader" — it is indeterminate, not misrouted.*

---

## 3. The `NodeId → address` map — operator config, no wire discovery

**R3-1 (the map resolves a `NodeId` to its HTTP api-port endpoint — not the Raft port).** The mapping from a
hinted `NodeId` to a connection target is **deployment/operator configuration**, not wire-discovered, and it
**MUST** resolve to that node's **control-plane HTTP API endpoint** — the `--api-port` (default **8080**) /
`https://<host>:<api-port>/v1/…` base — because that is where `/v1/config` requests (and therefore a followed
hint) go (`ConfigdServer` starts the HTTP API on `config.apiPort()`, :952).

> **Do not use `--peer-addresses` / `--bind-port` for this.** The server's `--peer-addresses`
> (`ServerConfig` :141/:229) and `--bind-port` (default **9090**) configure the **Raft inter-node transport**,
> a *different* port from the HTTP API. They illustrate only that *addresses are operator-config, not
> wire-discovered* — a driver **MUST NOT** reuse their port values as the request endpoint. A driver that
> builds its map from the Raft addresses resolves every hint to the wrong port and every follow fails.

**R3-2 (no topology / shard-map / membership discovery endpoint).** v1 exposes **no** `/shards`, `/topology`,
`/members`, `/peers`, or membership endpoint (none in `AdminApiHandler.handle`, :214–233; only `/health/*`,
`/metrics`, `/v1/config/`, and — when its seam is wired — the leadership-transfer **control** route
`/v1/admin/groups/…/transfer-leadership`, §04 D2-2a). A driver **CANNOT** discover the cluster map, the shard
count `N`, or peer endpoints from the wire: the leadership-transfer route is a **control** operation that
**discloses nothing** about membership (it takes a `NodeId` the operator already configured and returns only a
transfer status), so this no-**discovery** guarantee holds. A driver **MUST** be configured with the set of
`NodeId → api-endpoint` entries it may be redirected to (and that it may name as a leadership-transfer target).
*(A discovery endpoint is a named **v2** candidate — see R7; do not assume it.)*

**R3-3 (an unresolvable hint degrades to hintless).** If a `503` hints a `NodeId` the driver's map does **not**
contain, the driver **MUST** treat it as a **hintless `503`** (R2-3): it cannot resolve an endpoint, so it
backs off and retries / rotates among the nodes it **does** know (R6-1), rather than failing hard. A driver
**SHOULD** log the unknown-`NodeId` hint (a sign its map is stale vs. the deployment).

---

## 4. Leader-following is REQUIRED even at N = 1

**R4-1 (the rule).** Writes (`PUT`/`DELETE`) and **leader-served reads** (a `?consistency=linearizable` read on
a node with a linearizable path wired, and **any** strong-read — R4-4) return **`503`** (+ hint when known)
when they reach a **follower** (or a node mid-election). A driver **MUST** implement **hint-follow + retry/
backoff** to complete such a request. This is a **v1 (N = 1) requirement**, not an N > 1-only concern — the
audit's key correction.

**R4-2 (even one node `503`s — the hintless election window).** A single-node deployment is **not** exempt:
before it wins its initial election (startup, or after a transient step-down), a write / leader-served read
returns `503`, and because `raftNode.leaderId()` is `null` until a leader is established, that `503` carries
**no** `X-Leader-Hint`. Since there is no *other* node to follow, the driver's correct action is **back off and
retry the same endpoint** — **subject to the bounded attempt/deadline budget of R6-3** (so the loop is finite;
a node that never wins election terminates as a bounded failure, not an infinite hang) — until the node
becomes leader. **A v1 driver therefore MUST implement the `503` → backoff-retry loop** even when it only ever
talks to one node.

**R4-2a (multi-endpoint hintless rotation — N > 1).** R4-2 describes the single-node case ("no *other* node to
follow"). When a driver is configured with **more than one** endpoint, a **hintless `503`** means the *current*
endpoint is not serving (mid-election or unhealthy) and offers no hint. Such a driver **MAY** — and a reference
driver **SHOULD** — **rotate to the next configured endpoint** on each hintless-`503` retry (under the same
bounded backoff / R6-3 budget) rather than hammer the endpoint that just `503`'d: another node may be the leader
or may return a followable `X-Leader-Hint` (R4-3), so rotation + hint-following converges on the leader faster
than retrying one non-serving endpoint. At **N = 1** this is a no-op — the driver necessarily retries the same
endpoint (R4-2). Either way the loop stays finite (R6-3). A driver **MUST NOT** rotate to any endpoint outside
its configured `NodeId → api-endpoint` map (anti-SSRF — R3-2 / R8).

**R4-3 (follow-once — `hop < 2` — to avoid redirect ping-pong).** On a `503` **with** a hint, a driver
**SHOULD** follow the hinted node **at most once per logical attempt** (a bounded `hop < 2`): if the hinted
node **also** `503`s (whether or not it carries a further hint), the driver **MUST NOT** immediately chase the
next hint in a tight loop — it **MUST** back off (R6-3) before the next attempt. This bounds a leader-in-flux
ping-pong (A hints B, B has just stepped down and hints A, …). This is a **client-side** contract this RFC
prescribes; it is **not** server-enforced (the server holds no hop state).

**R4-4 (any read can `503` — strong-read classification is server-side and invisible).** A driver **MUST NOT**
assume a read avoids the redirect loop merely because it did not request `?consistency=linearizable`. A key in
the server's **strong-read class** (operator-configured, default `secure/`, §04 D3-5) is **upgraded to
linearizable server-side regardless of the requested consistency** and **fails closed `503`** (with
`X-Fail-Closed: strong-read`) when it cannot be confirmed — even for a `?consistency=stale` request. Because
the strong-read prefix set is server-side and **invisible to the driver**, a driver **MUST** be prepared for a
`503` on **any** read and apply R6. *(A default stale read of an **ordinary** key is served locally and does
**not** normally `503` for leadership — §04 D3-8; but if such a read ever returns a transient `503`, the driver
re-reads, no hint needed.)*

---

## 5. No client-side sharding — identical routing at N = 1 and N > 1

**R5-1 (the shard hash is server-side; the driver never replicates it).** Key placement is decided **on the
server** by hashing the full `(scope, path)` (`StaticShardMap.shardFor`; §1 A2-4). A driver **MUST NOT**
compute a shard, replicate the hash, route by key prefix, or otherwise assume placement.

**R5-2 (`N` is a deploy-time constant the driver never needs).** The shard count `N` is fixed at deployment. A
driver **MUST NOT** require knowledge of `N`; its routing logic is **byte-for-byte identical** at N = 1 and
N > 1.

**R5-3 (the hint is per-request and already shard- and scope-resolved).** The `X-Leader-Hint` for a request on
`(scope, key)` is resolved as the leader of **the shard that owns `(scope, key)`** — for reads
`driver.getGroup(shardMap.shardFor(scope, key)).leaderId()` (`ConfigdServer` :955–957), and for writes the
identical shard/scope resolution at the proposer (:793–797 → `ConfigWriteService.mapOutcome` :332–342). So at
N > 1 a hint is **not** the leader of some unrelated group; following it is **not** a misroute. A hint is valid
**only for the request that produced it** — a driver **MUST NOT** cache a hint and reuse it for a *different*
key (a different key may live on a different shard with a different leader); it self-corrects within one hop if
it does, but it **MUST NOT** rely on that.

**R5-4 (forward-compatibility and the v1 shared-node premise).** R5-1…R5-3 are what make a v1 single-shard
driver **forward-compatible to sharding**: the same `NodeId → api-endpoint` map and the same follow-the-hint
loop work unchanged when the cluster grows to N > 1. This rests on the v1 **shared-node** topology — **every
node hosts every shard group**, so any configured node can serve as an entry point and resolve a hint for any
shard. A *partitioned-hosting* topology (distinct nodes own disjoint shards — the "sharded endpoints" v2
candidate, R7-2) would change this; a v1 driver **MUST NOT** bake in either a "one leader for everything"
assumption *or* a "this node owns only some shards" assumption — following R5 it forms neither.

---

## 6. Retry / backoff / idempotency contract (unary plane)

**R6-1 (outcome → driver action).** A driver **MUST** react to a unary response as follows (the routing view;
§04 / §07 give the full per-code detail):

| Result | Meaning | Driver action |
|---|---|---|
| `200` | committed-and-applied / value served | done |
| `503` **+** `X-Leader-Hint` | not-leader / lost / strong-read fail-closed / ordinary-key linearizable-unavailable; leader **known** (typically a different node ⇒ usually N > 1; at N = 1 the hint can rarely point at **self** under a ReadIndex timeout) | **progress toward the hinted leader** — resolve it (R3) and follow it on this or a later attempt (`hop < 2`, R4-3); do **not** merely re-hit the same endpoint when the hint names a different node (it is then not the leader for that shard); a hint that names the current node ⇒ back off + retry it (R4-2) |
| `503` **without** hint | leader unknown (election; the normal N = 1 case) | **back off + retry the same endpoint** (R4-2), bounded by R6-3 |
| `504` / transport timeout | **indeterminate** — write MAY have committed (§04 D4-8) | retry-to-definite (idempotent LWW); a negative re-read is **not** proof of failure; **no** read-modify-write across it |
| other `5xx` | transient server-side | back off + retry (treat as indeterminate for a write) |
| `404` | definite "absent" (not a routing outcome) | **do not** retry as a routing failure (it is a real answer) |
| `400` | permanent request error | **do not retry unchanged** — fix the request |
| `401` | authentication required/failed | **(re)authenticate**; do not hot-loop the same credential. *(Also: under an enabled replay guard a stale/missing `X-Configd-Timestamp` ⇒ `401` — the fix is a **fresh timestamp+nonce**, R6-4, not re-auth.)* |
| `403` | permanently forbidden for this principal | **do not retry** unchanged |
| `429` **+** `Retry-After` | overloaded — a **pre-commit** reject (definitely **not** committed, like `Lost`/`NotLeader`) | back off **`Retry-After`**, then retry |
| `409` | replayed nonce (optional replay guard, R6-4) | retry with a **fresh** timestamp+nonce, not the same |

**R6-2 (idempotency is what makes retry safe).** `PUT`/`DELETE` are **idempotent last-writer-wins** (§04 D4-3)
and `GET` is side-effect-free (§04 D3-8). This is the **only** reason a `504`/timeout/`5xx` on a write is safe
to retry. A driver **MUST NOT** assume any non-idempotent operation exists on this plane (there is none in v1).

**R6-3 (backoff and the bounded budget).** A driver **SHOULD** use **bounded exponential backoff with jitter**
between retries and **MUST** bound the total attempt count / deadline (so a node stuck mid-election is not
hammered and no loop is infinite, R4-2). It **MUST** honor `Retry-After` on a `429`. **If the budget is
exhausted while the last outcome was a `504`/indeterminate, the request terminates as `UNKNOWN`** — **never**
reported as a definite failure — and the driver **MUST NOT** perform a read-modify-write across that unresolved
write (§04 D4-8). A hintless `503` and a `504` share the same "try again shortly" backoff.

**R6-4 (the optional ReplayGuard headers).** A deployment **MAY** enable an opt-in replay guard (§04 D11-3;
`-Dconfigd.replay.enabled`, **default off** — **no client populates these today**). When a driver supports it,
each mutating request carries `X-Configd-Timestamp` (epoch ms) + `X-Configd-Nonce`; a verbatim in-window
replay ⇒ **`409`**, a stale/future/missing stamp ⇒ **`401`** (`replayRejected` :590–598). For **retry** this is
load-bearing: a driver **MUST** mint a **fresh** nonce + current timestamp on **every** attempt — re-sending
the original on a retry is a self-inflicted `409` (in-window) or `401` (after the window). **Trust model
(honesty):** the guard is **passive-replay-only** — it does **not** bind a credential to a node and does
**not** provide request integrity against a token holder minting fresh requests (§04 D11-3); a driver **MUST
NOT** treat it as authentication-strength. **Ignore-path:** a driver that does not implement R6-4 can mutate
only against a deployment with the guard **disabled** (the default); against an **enabled** guard, a `401`/
`409` on a mutation that survives valid re-authentication is the guard, and the driver **MUST** implement R6-4
(fresh stamp per attempt) to proceed — re-authentication alone cannot resolve it.

---

## 7. Composition and forward-compatibility

**R7-1 (where the codes come from).** §04 produces the `503`/`504`/`X-Leader-Hint`/`429`/`409`; §01 is the
`(scope, path)` + server-side-shard model R5 relies on; §03 is the credential re-presented on a follow (§8);
§07 will consolidate the per-code taxonomy. §05 owns only the **routing/redirect/retry** view.

**R7-2 (named forward extensions — fail closed).** The following are **named** v1 omissions a driver **MUST
NOT** assume and **MUST** fail closed on: a **topology / shard-map / membership discovery endpoint** (R3-2; a
v2 candidate so a driver can learn the `NodeId → api-endpoint` map and `N` from the wire); a **richer hint**
(e.g. `host:port`, or a multi-candidate hint); an **HTTP `3xx`/`Location` redirect**; a **partitioned /
sharded-endpoints** topology (R5-4); a **read-from-follower** routing knob. A driver built to R2–R6 and §8
keeps working when these are added because it already (a) follows an advisory hint it can ignore, (b) sources
addresses out-of-band, and (c) does no client-side sharding.

---

## 8. Security considerations (the redirect is a trust boundary)

**R8-1 (a follow re-presents the credential — the `NodeId → address` map is a trust boundary).** Following an
`X-Leader-Hint` means **re-sending the request, including the caller's credential** (the bearer token, or the
mTLS client identity — §03 AU3), to the hinted node. A `503` can hint **any** `NodeId`, and a compromised
node could steer a driver toward a node it would not otherwise contact. The wire-never-supplies-an-address
invariant (R2-2) bounds the redirect to the **operator-configured set**, so that set is a **trust boundary**:
an operator **MUST** populate the driver's `NodeId → api-endpoint` map with **only nodes in the same trust
domain**, and a driver **MUST NOT** add a map entry from any untrusted source. A driver **SHOULD** make its
map static/operator-pinned, not dynamically expandable from responses.

**R8-2 (bearer tokens are replayable across the cluster — prefer mTLS / scoped tokens).** A **bearer token** is
replayable: **any** node that receives it can reuse it cluster-wide (and the replay guard does not change this
— R6-4). Because a follow sends the credential to the hinted node, an operator **SHOULD** prefer **mTLS** (a
per-connection identity, §03 AU3-2) or **short-TTL, audience-scoped** bearer tokens over long-lived static
bearer tokens for multi-node deployments, to bound the blast radius of a credential reaching an unexpected
node.

**R8-3 (a follow MUST NOT relax transport security).** When a driver follows a hint to a node, it **MUST**
apply the **same** transport security as a direct connection — full TLS / mTLS certificate validation,
hostname/SAN verification (§03 AU3-2/AU3-4; the §06 TLS profile, planned). A driver **MUST NOT** downgrade,
skip cert validation, or accept a different trust anchor for a "redirected" connection. (Combined with R2-2's
address-from-own-map rule, this closes redirect-to-attacker.)

**R8-4 (the hint is authorization-gated — no anonymous topology disclosure).** The `X-Leader-Hint` is emitted
**only after** the per-key authorization check passes (`checkAuth` precedes every hint site — GET→`READ`
:197, PUT/DELETE→`WRITE` :294/:348): an **unauthenticated** caller gets `401` and an **unauthorized** caller
`403` **before** any hint. So an attacker does **not** learn a leader `NodeId` from hints; a hint is an opaque
`NodeId` (never an address or a node count) disclosed only to a principal already authorized for that key. An
authorized caller **can** enumerate per-shard leader ids over time (acceptable — it is already trusted for
those keys; this is also why no anonymous discovery endpoint exists, R3-2). The **only** unauthenticated
topology signal is `/health/ready` returning `503` when no leader is elected — a leader-**presence boolean**,
not an identity or count — which is exactly the election/`503` window R4-2 relies on (observable out-of-band).

---

## 9. Summary of normative requirements (driver checklist)

- [ ] **No `3xx`/`Location`** — read **`X-Leader-Hint`** from the **header** (never parse the `503`/`504`
      body — it renders `Node-N`, not the wire id, and echoes your key); it is a **bare numeric `NodeId`**, not
      an address (R2-1, R2-2).
- [ ] Maintain an out-of-band **`NodeId → HTTP api-endpoint` map** (the `--api-port`, **not** the Raft
      `--peer-addresses`/`--bind-port`); no wire topology discovery; an unresolvable hint ⇒ treat as hintless
      (R3).
- [ ] **Implement leader-follow + bounded backoff-retry even at N = 1** — a single node `503`s (often
      **hintless**) during its election; back off + retry the same endpoint within a bounded budget (R4-1,
      R4-2, R6-3).
- [ ] On a `503` **with** a hint, **progress toward the hinted leader** (follow `hop < 2`, then back off) —
      **not** merely re-hit the same endpoint; on a **hintless** `503`, retry the same endpoint (R4-3, R6-1).
- [ ] Be ready for a `503` on **any** read — the server's strong-read class is invisible to you and upgrades
      `stale` requests to fail-closed linearizable (R4-4).
- [ ] Do **no client-side sharding** — the hash is server-side, `N` unknown-and-unneeded, the hint is
      per-request and shard/scope-resolved; routing is **identical at N = 1 and N > 1** (do not reuse a hint
      across keys) (R5).
- [ ] React per the outcome table: hintless `503`/`504`/`5xx` ⇒ bounded backoff + retry (idempotent LWW makes
      it safe; a `504` write MAY have committed — terminate **UNKNOWN** on budget exhaustion, no RMW);
      `400`/`401`/`403`/`404` ⇒ don't retry as routing; `429` ⇒ honor `Retry-After` (R6-1, R6-2, R6-3).
- [ ] If supporting the optional replay guard, mint a **fresh** timestamp+nonce on **every** retry; it is
      **passive-replay-only**, not request integrity; without it, mutate only against guard-disabled
      deployments (R6-4).
- [ ] **Treat the `NodeId → address` map as a trust boundary** — same-trust-domain nodes only; following a
      hint re-presents your credential and **MUST NOT** relax TLS/mTLS; prefer mTLS / scoped tokens (§8).
- [ ] **Fail closed** on a future discovery endpoint / richer hint / `3xx` redirect / partitioned topology you
      don't recognize (R7-2).
