# HTTP API

The complete HTTP surface of both runnable services. Values are opaque bytes: a config value is the
raw request/response body, always `application/octet-stream` — there is no imposed document format.
Error responses carry a short plain-text message with Content-Type `application/json`.

Configd exposes four network surfaces; two of them are HTTP:

| Surface | Service | Port flag (default) | Protocol |
|---|---|---|---|
| Control-plane API — config read/write, health, metrics, admin | `configd-server` | `--api-port` (8080) | HTTP/1.1, this page |
| Edge read API — cached reads, health, metrics | `configd-edge-node` | `--api-port` | HTTP/1.1, this page |
| Edge fan-out — SUBSCRIBE and watches | `configd-server` | `--edge-port` (disabled) | Binary frames, [driver-protocol RFC](../rfc/driver-protocol/) |
| Raft consensus (intra-cluster) | `configd-server` | `--bind-port` (9090) | Binary, [RFC §13](../rfc/driver-protocol/) |

There is no separate metrics port: `/metrics` is served on the API port of each service. Unknown
paths return `404`; a known path with the wrong method returns `405`.

## Conventions

**Authentication (control plane).** When auth is enabled, a request presents one of:

- `Authorization: Bearer <token>`
- `Authorization: Basic <base64 user:pass>` (RFC 7617)
- a verified mTLS client certificate (used when no usable `Authorization` header is present; the
  server requests a client cert only when the auth chain includes `mtls`)

Missing/invalid credentials → `401` with `WWW-Authenticate: Bearer`; authenticated but not authorized
→ `403`; auth backend unreachable → `503` (fail closed, never fail open).

**Reserved prefixes.** Keys under `_acl/` and `_system/` are administrative. Every method on them
requires the `ADMIN` permission — including `GET` — and writing them while auth is disabled is
refused with `403` (so a cluster can't be silently poisoned before auth is turned on).

**Replay protection (opt-in).** When the replay guard is enabled, mutating requests must carry
`X-Configd-Timestamp` and `X-Configd-Nonce` headers. A stale or malformed pair → `401`; a reused
nonce → `409`.

**Limits.** Key: at most 1024 bytes UTF-8, non-blank (`400` otherwise). Value: at most 1 MiB
(`400 Validation failed`). Whole HTTP request: 1 MiB cap at the transport (`413`), 8 KiB
request-line/header limit (`400`), 30 s request deadline and 60 s idle timeout (connection closed).

---

## Control-plane API (`configd-server`)

### `GET /v1/config/{key}`

Read a config value. Requires `READ` (or `ADMIN` for reserved keys) when auth is on.

Query parameters:

| Parameter | Values | Default | Meaning |
|---|---|---|---|
| `scope` | `GLOBAL`, `REGIONAL`, `LOCAL` (case-insensitive) | `GLOBAL` | Scope the key is addressed in; unknown value → `400` |
| `consistency` | `linearizable` | stale | Force a leader-confirmed (ReadIndex) read |

```bash
curl -i http://localhost:8080/v1/config/orders/db/url
curl -i 'http://localhost:8080/v1/config/orders/db/url?consistency=linearizable'
```

Responses:

| Status | When | Headers |
|---|---|---|
| `200` | Found; body = raw value bytes | `X-Config-Version`, `X-Consistency: stale\|linearizable`, `X-Strong-Read: true` (strong-read keys only) |
| `400` | Blank/oversize key, unknown `scope` | |
| `401` / `403` / `503` | Auth denied / forbidden / auth backend down | `WWW-Authenticate: Bearer` on 401 |
| `404` | Key not present | |
| `503` | `consistency=linearizable` requested but this node cannot confirm leadership | `X-Leader-Hint: <node>` |
| `503` | Strong-read key and the linearizable path is unavailable — never served stale | `X-Fail-Closed: strong-read`, `X-Leader-Hint` if known |

Strong-read keys (the `secure/` class by default) ignore the requested consistency: they are served
linearizably or fail closed. See the
[consistency contract](../operations/consistency-contract.md).

### `PUT /v1/config/{key}`

Write a value; the raw request body is the value. Requires `WRITE` (`ADMIN` for reserved keys).
`?scope=` as above. An empty body is `400` (use `DELETE` to remove a key). Writes to `_acl/` keys are
validated as ACL policy before commit; a malformed policy → `400 Invalid ACL policy`.

```bash
curl -X PUT --data-binary 'jdbc:postgresql://db:5432/orders' \
     http://localhost:8080/v1/config/orders/db/url
# 200  Committed: seq=1
```

| Status | When | Headers |
|---|---|---|
| `200` | Committed durably by a quorum; body `Committed: seq=N` | |
| `400` | Key/scope/body validation failed, value > 1 MiB, malformed ACL policy | |
| `401` / `403` / `503` | Auth denied / forbidden (incl. reserved-prefix write with auth off) / auth backend down | |
| `401` / `409` | Replay guard: stale or malformed timestamp+nonce / reused nonce | |
| `413` | Request larger than the transport cap | |
| `429` | Leader overloaded (in-flight proposal bound reached) | `Retry-After: 1` |
| `503` | This node is not the leader, or leadership was lost before commit | `X-Leader-Hint` |
| `504` | Commit unconfirmed within the deadline — the write may or may not have committed; read back before retrying a non-idempotent workflow | |

A `503` with `X-Leader-Hint` is the redirect mechanism: retry the same request against the hinted
node. A `504` is the one genuinely indeterminate outcome.

### `DELETE /v1/config/{key}`

Delete a key. Same permission, validation, replay-guard, and response mapping as `PUT` (no body).

### `GET /health/live` and `GET /health/ready`

No auth. `200` when healthy, `503` when not, body:

```json
{"healthy": true, "checks": [{"name": "...", "healthy": true, "detail": "..."}]}
```

Liveness is process health; readiness additionally gates on serving state (a draining node goes
not-ready on `SIGTERM` before it exits, so orchestrators stop routing to it).

### `GET /metrics`

Prometheus text exposition (`text/plain; version=0.0.4`). When auth is on, any *authenticated*
principal may scrape — authentication is checked, ACLs are not. `401`/`503` follow the standard auth
mapping.

### `POST /v1/admin/groups/{groupId}/transfer-leadership?target={nodeId}`

Move a Raft group's leadership to `target`. This endpoint is ADMIN-gated against the reserved
resource `_system/raft/groups/{groupId}/leadership` and is **refused with `403` when auth is
disabled** — it cannot be used at all on an unauthenticated cluster. The replay guard applies.

| Status | When |
|---|---|
| `200` | Transfer accepted — **asynchronous**: it may still auto-abort if the target cannot catch up within about one election timeout. Confirm by re-reading leadership, there is no status endpoint. |
| `400` | Malformed group id, missing/non-integer `target`, or unknown group (checked after the auth gate, so unauthorized callers cannot probe group existence) |
| `403` | Auth disabled, or principal lacks ADMIN |
| `409` | Transfer rejected (e.g. target not a member) |
| `503` | This node does not lead the group (`X-Leader-Hint`), or the request timed out internally |

---

## Edge read API (`configd-edge-node`)

The edge serves bounded-stale cached reads. Config reads carry **no authentication** — identity and
authorization live on the upstream SUBSCRIBE/watch plane, and the edge is deployed inside that trust
boundary. See [known limitations §2](../operations/known-limitations.md#watches-ordering-topology-and-the-security-model).

### `GET /v1/config/{key}`

| Status | When | Headers |
|---|---|---|
| `200` | Found; body = raw value bytes | `X-Configd-Version` (value version), `X-Configd-Cursor` (store version) |
| `400` | Missing key, or malformed `X-Configd-Cursor` request header | |
| `404` | Key not present | `X-Configd-Cursor` |
| `404` | Key outside this edge's subscribed slice | `X-Configd-Refused: not-subscribed` |
| `404` | Client cursor is ahead of this edge (it would be a non-monotonic read) | `X-Configd-Refused: cursor-behind`, `X-Configd-Cursor` |
| `503` | Strong-read key — the edge never serves these; use the control plane | `X-Fail-Closed: strong-read` |

Additionally, `X-Configd-Stale: true` is set on every response while the edge's staleness state is
STALE or worse.

**Monotonic reads:** send the last `X-Configd-Cursor` you observed as a request header of the same
name. An edge that has fallen behind your cursor refuses (`404` + `cursor-behind`) instead of
silently serving older data — fail closed, then retry another edge or fall back to the control
plane.

### `GET /health/live`, `GET /health/ready`

`{"live": true}` / `{"ready": <bool>, "staleness": "<CURRENT|STALE|DEGRADED|DISCONNECTED>"}`.
Readiness fails (`503`) at DEGRADED or worse.

### `GET /metrics`

Prometheus text exposition. Optionally protected by a static Bearer token
(`-Dconfigd.edge.metricsScrapeToken=...`); when set, a missing or wrong token → `401`.

---

## What is deliberately not here

- **Watches and SUBSCRIBE** are not HTTP. They run on the binary edge fan-out plane (`--edge-port`);
  the wire format, auth handshake, and error taxonomy are normatively specified in the
  [driver-protocol RFC](../rfc/driver-protocol/) and implemented by `configd-client`.
- **No list/enumerate endpoint.** Keys are point-addressed; the `LIST` permission exists in the ACL
  model but has nothing to gate yet ([known limitations §4](../operations/known-limitations.md#authorization-list-is-defined-but-not-exposed)).
- **No transactions, locks, leases, or sessions.** Configd is a config store, not a coordination
  service — see the [comparison](Comparison.md).
- **ACL and system administration** happen through the reserved `_acl/` and `_system/` keys on the
  ordinary `/v1/config` surface (ADMIN-gated), not through separate admin routes.
