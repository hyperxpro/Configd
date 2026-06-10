# Multi-Node Smoke Test — Configd (audit-session-1)

**Auditor:** sre-auditor (the 3am on-call) · **Date:** 2026-06-10
**Workspace:** `/home/ubuntu/ws-smoke` (clone of `ws-clean`, branch
`session-1-ground-truth`, HEAD `423a654`, BUILD SUCCESS pre-proven)
**Shaded jar under test:**
`/home/ubuntu/ws-smoke/configd-server/target/configd-server-0.1.0-SNAPSHOT.jar`
(2.5 MB, built; `Main-Class: io.configd.server.ConfigdServer`)
**Java:** Corretto 25.0.0.36.2 (`java 25`, `--enable-preview` required)
**Host:** 2 vCPU / 7.7 GiB. JVM heaps sized `-Xmx256m` per node.

> Methodology: every PASS/FAIL below is backed by a command + pasted output.
> All docs (runsheet, runbooks, ledger) are treated as **claims**, never as
> evidence. AUDIT, DO NOT FIX — no production code was changed; only
> launcher/gate scripts were authored (logged in §"Scripts created").

---

## Result summary (per step)

| # | Step | Verdict |
|---|------|---------|
| 1 | Discover launch procedure | **PASS** — exact invocation recorded |
| 2 | 3-node localhost cluster, leader elected, all healthy | **PASS** |
| 3 | Write config via PUT | **PASS** (200; R-14 ACK≠commit observed) |
| 4 | Read back from each node | **PASS** (default on all 3; lin leader-only) |
| 5 | Edge observes the write | **FAIL — NOT DEMONSTRABLE (P0 candidate)** |
| 6 | Kill leader, re-elect, write+read on survivor | **PASS** (~2.3 s re-elect) |
| 7 | Clean shutdown; `touch /home/ubuntu/SMOKE-DONE` | **PASS** |

Control-plane smoke (steps 2,3,4,6) is **productized** as
`/home/ubuntu/Code/Configd/gates/smoke-multinode.sh` and passes **5/5**
back-to-back, ~10 s/run, idempotent, exit 0, zero leftover JVMs/ports.

---

## Step 1 — Launch procedure (PASS)

**Single server main class:** `io.configd.server.ConfigdServer`
(`configd-server/src/main/java/io/configd/server/ConfigdServer.java:860`).
The only `main()` in `src/main` across the whole repo besides three linz
harness runners (`HarnessMain`, `LostWriteScenario`, `StaleReadScenario`).
There is **no `configd` CLI** main — confirmed:

```
$ grep -rln "public static void main" --include="*.java" | grep src/main
.../io/configd/server/ConfigdServer.java
.../io/configd/linz/runner/LostWriteScenario.java
.../io/configd/linz/runner/HarnessMain.java
.../io/configd/linz/runner/StaleReadScenario.java
```

**Flags** (`ServerConfig.parse`, `ServerConfig.java:66-162`):
`--node-id` (int, req), `--data-dir` (req), `--peers` (CSV of *other* ids,
req), `--bind-address` (def `0.0.0.0`), `--bind-port` (Raft, def 9090),
`--api-port` (HTTP, def 8080), `--peer-addresses` (`id=host:port,...` for
**all** nodes incl. self), plus optional `--tls-*`, `--auth-token`,
`--signing-key-file`.

**Exact node command** — replicated verbatim from how the linz harness
spawns real separate-JVM clusters (`ClusterNode.launch()`,
`configd-linz/.../cluster/ClusterNode.java:53-74`, port/peer layout from
`Cluster.create()` lines 24-43: node *k* uses Raft `raftBase+k`, API
`apiBase+k`, peer-addresses lists all nodes):

```
java -Xmx256m --enable-preview -jar <shaded-jar> \
  --node-id K --data-dir <dir> --peers <other-ids> \
  --bind-address 127.0.0.1 --bind-port 909K --api-port 808K \
  --peer-addresses 1=127.0.0.1:9091,2=127.0.0.1:9092,3=127.0.0.1:9093
```

HTTP API endpoints (only four contexts; `HttpApiServer.java:74-82`):
`GET /health/live`, `GET /health/ready`, `GET /metrics`,
`GET|PUT|DELETE /v1/config/{key}`.

---

## Step 2 — 3-node cluster, leader elected, all healthy (PASS)

Launched via `launch-cluster.sh` (Raft 9091-9093, API 8081-8083):

```
node 1 pid=12923 raft=9091 api=8081
node 2 pid=12936 raft=9092 api=8082
node 3 pid=12949 raft=9093 api=8083
```

Readiness on all three (200 == `raftNode.leaderId() != null`, the registered
readiness check at `ConfigdServer.java:417-423`):

```
=== node api=8081 ===  /health/live -> 200   /health/ready -> 200
   {"healthy":true,"checks":[{"name":"raft-leader","healthy":true,"detail":"OK"}]}
=== node api=8082 ===  /health/live -> 200   /health/ready -> 200   (same)
=== node api=8083 ===  /health/live -> 200   /health/ready -> 200   (same)
```

Leader identified by probe PUT (200 == leader; 503+`X-Leader-Hint` == follower):

```
node api=8081 -> HTTP 503  body=Not Leader (leader=Node-3)  X-leader-hint: 3
node api=8082 -> HTTP 503  body=Not Leader (leader=Node-3)  X-leader-hint: 3
node api=8083 -> HTTP 200  body=Accepted: proposalId=1
```

**Leader = Node-3.** Election succeeded; all 3 healthy. **PASS.**

> Liveness vs readiness vs startup: `/health/live` always 200 once the HTTP
> server is up (process liveness). `/health/ready` is the only gate that flips
> on leader presence. There is **no startup probe** distinct from liveness,
> and the readiness check has a **single** registered check (`raft-leader`) —
> it does NOT cover drain/shutdown state (see §"On-call observations").

---

## Step 3 — Write a config via PUT (PASS; R-14 observed)

```
$ curl -s -X PUT -d "v1-payload-alpha" http://127.0.0.1:8083/v1/config/smoke/key1 -w "\nHTTP_CODE=%{http_code}\n"
Accepted: proposalId=3
HTTP_CODE=200
```

**R-14 (ACK≠commit) confirmed at the code level.** The 200 body
`Accepted: proposalId=N` is `WriteResult.Accepted`, returned the instant the
proposal is **appended to the leader's local log** — *before* quorum commit:

- `RaftNode.propose()` (`RaftNode.java:285-289`): `log.append(entry); …;
  return ProposalResult.ACCEPTED;` — returns on local append, no wait for
  commit/replication.
- `raftProposer` (`ConfigdServer.java:709-737`): treats `ACCEPTED` as success.
- `ConfigWriteService.put` (`ConfigWriteService.java:150-154`): on accepted,
  returns `WriteResult.Accepted` → HTTP 200.

So a 200 means "appended to leader log," not "committed by quorum." In this
healthy run the write *did* commit (step 4 read-back from all 3 nodes proves
it), but the API contract is leader-local-append acknowledgement.

---

## Step 4 — Read back from each node (PASS)

```
--- node api=8081 ---
  default GET (local applied state): v1-payload-alpha [HTTP 200]
  linearizable GET               : Not Leader - cannot serve linearizable read [HTTP 503]
--- node api=8082 ---
  default GET (local applied state): v1-payload-alpha [HTTP 200]
  linearizable GET               : Not Leader - cannot serve linearizable read [HTTP 503]
--- node api=8083 ---
  default GET (local applied state): v1-payload-alpha [HTTP 200]
  linearizable GET               : v1-payload-alpha [HTTP 200]
```

**Consistency mechanisms (two distinct paths,
`HttpApiServer.java:223-245` + `ConfigdServer.java:465-528`):**
- **Default GET** = read of the node's **local applied state**
  (`configStore.get(key)`), served by **all 3** nodes. Proves the write
  committed and replicated to every follower's state machine.
- **`?consistency=linearizable`** = ReadIndex protocol (record commit index →
  confirm leadership via heartbeat quorum → wait `lastApplied ≥ readIndex` →
  serve), **leader-only**; followers return 503 "Not Leader." Budget 150 ms;
  transiently flaky (the linz client retries via `linReadConfirm` for this
  exact reason).

---

## Step 5 — Edge observation: NOT DEMONSTRABLE — **P0 candidate** (FAIL)

> **Architecture claim:** "committed writes propagate to edge nodes
> (<500 ms p99)." **Verdict: there is NO supported mechanism by which any
> edge can observe a control-plane write.** The system **cannot be
> demonstrated end-to-end.** Three independent, code-cited root causes:

### 5.1 Deltas go to die in the FanOutBuffer (server-side dead-end)

The state-machine listener builds a `ConfigDelta` on every apply and calls
`fanOutBuffer.append(delta)` (`ConfigdServer.java:347-362`, append at **:360**).
That `append` is the **only** reference to `fanOutBuffer` in all of `src/main`:

```
$ grep -rn "\.deltasSince\|\.latest()\|\.canReplayFrom\|fanOutBuffer\." \
    --include="*.java" | grep src/main | grep -v FanOutBuffer.java
.../io/configd/server/ConfigdServer.java:360:   fanOutBuffer.append(delta);
```

`FanOutBuffer` (`FanOutBuffer.java`) has read methods (`deltasSince`,
`latest`, `canReplayFrom`) but **nothing in the server ever calls them** and
nothing exports them over the network. Deltas are appended to an in-memory
ring and overwritten; they never leave the process.

### 5.2 No server-side fan-out / subscribe / watch listener port

The HTTP server registers exactly four contexts and **none** is a
subscription/stream/watch/fan-out endpoint
(`HttpApiServer.java:74-82`):

```
createContext("/health/live", …)
createContext("/health/ready", …)
createContext("/metrics", …)
createContext("/v1/config/", …)
```

`WatchService`, `SubscriptionManager`, `FanOutBuffer`, `PlumtreeNode`,
`HyParViewOverlay` are all *constructed* in `ConfigdServer` but **none is
wired to any HTTP handler or listener socket** (grep for them in
`HttpApiServer.java` → 0 hits). `PlumtreeNode.broadcast(...)` — the gossip
fan-out primitive — is referenced **only** by a benchmark
(`configd-testkit/.../PlumtreeFanOutBenchmark.java:14`), never by `src/main`.
The Raft `TcpRaftTransport` carries Raft messages between control-plane
peers only; it is not an edge subscription channel.

### 5.3 No edge process, and the edge client has no network transport

- **No edge `main()`:** zero `public static void main` in
  `configd-edge-cache/src/main` (grep → empty).
- **`io.configd.edge` is never referenced by any `src/main` outside the
  edge-cache module itself** (grep → empty). The edge module is dead code
  relative to the running server.
- **`EdgeConfigClient` has no transport:** its update path is
  `applyDelta(ConfigDelta delta)` and `loadSnapshot(ConfigSnapshot)` —
  both take an **in-process object** handed in by a caller
  (`EdgeConfigClient.java:130-146`). The module contains **zero**
  networking code (`grep Socket|HttpClient|URI|connect|InetSocketAddress`
  over `configd-edge-cache/src/main` → empty). It cannot pull deltas from a
  server; something would have to call `applyDelta` in the same JVM, and
  nothing does.

### 5.4 Runtime corroboration

Live `/metrics` scrape from the leader **after a confirmed committed write**:

```
configd_edge_read_total 0
configd_propagation_delay_seconds_count 0
propagation_lag_violation_total 0
configd_write_commit_total 0          # NOOP-wired; see ops-reality.md
```

Every edge/propagation counter is permanently 0. Nothing drives the edge
pipeline because the pipeline is severed at the server boundary.

**Attempted:** there is nothing to attempt — no port to connect to, no edge
binary to launch, no in-JVM caller of `applyDelta`. The `<500 ms`
propagation SLO is unobservable by construction.

**P0 candidate finding:** *"System cannot be demonstrated end-to-end —
committed writes never reach any edge by any supported mechanism."* Root
cause: the distribution layer (FanOut/Plumtree/HyParView/Watch/Subscription)
is instantiated but never connected to a network listener, and the edge
module has no transport and no entry point.

---

## Step 6 — Kill leader, re-elect, write+read on survivor (PASS)

```
=== kill -9 leader (node 3, pid 12949) at 18:14:43 ===  kill_epoch=1781115283.776
=== poll survivors 8081,8082 for new leader via probe PUT ===
NEW LEADER on api=8082 after 2.291170080s (iteration 15)
```

**Re-election ≈ 2.3 s** (within the runsheet's claimed <10 s leader-loss SLA).
New leader = Node-2.

Write to new leader + read-back:

```
$ curl -X PUT -d "v2-payload-bravo-after-failover" http://127.0.0.1:8082/v1/config/smoke/key2
Accepted: proposalId=2   HTTP 200
--- node api=8082 (leader) ---  key2: v2-payload-bravo-after-failover [200]
--- node api=8081 (survivor)--  key2: Not Found [404]  (then replicated within ~100 ms)
=== poll node1 for key2 ===  t+100ms: v2-payload-bravo-after-failover [200]  REPLICATED
linearizable GET key2 on new leader: v2-payload-bravo-after-failover [200]
```

**No committed data lost across failover:** `key1` (pre-kill write) still
reads `v1-payload-alpha` on both survivors. The post-failover `key2` was
present on the new leader immediately and replicated to the lagging follower
within ~100 ms (transient replication lag, not loss). **PASS.**

---

## Step 7 — Clean shutdown + marker (PASS)

SIGTERM to both survivors → graceful shutdown hook ran on both
(`ConfigdServer.java:597-600`):

```
/home/ubuntu/ws-smoke/smoke-run/n1.log:Configd shutting down...
/home/ubuntu/ws-smoke/smoke-run/n2.log:Configd shutting down...
```

Post-shutdown: `NO_JAVA_CONFIGD_JVMS`; `ALL_SMOKE_PORTS_FREE`
(8081-8083, 9091-9093). Marker created:

```
$ touch /home/ubuntu/SMOKE-DONE
-rw-rw-r-- 1 ubuntu ubuntu 0 Jun 10 18:15 /home/ubuntu/SMOKE-DONE
```

---

## On-call observations (3am readiness, against the binary as built)

- **Health probes:** liveness and readiness exist; **no separate startup
  probe**. Readiness has a single check (`raft-leader`) and **does NOT flip on
  drain/shutdown** — there is no drain state; SIGTERM goes straight to the
  shutdown hook. A K8s rolling update cannot gracefully cordon a node via
  readiness because nothing sets readiness false before exit.
- **Graceful shutdown:** SIGTERM → shutdown hook stops HTTP (`stop(2)`), then
  drains read-dispatch (2 s), tick (5 s), tls-reload (2 s) executors
  (`ConfigdServer.java:614-631`). Bounded, but there is **no active-conn
  drain window**: in-flight HTTP requests get the 2 s `HttpServer.stop(2)`
  grace only; no explicit "stop accepting, finish in-flight" signal at the
  listener.
- **Hot reload:** TLS cert reload runs every 60 s on a dedicated executor
  (`:585-593`). Backend-pool / route-table hot reload: **N/A** — this is not a
  proxy; there is no route table or backend pool to reload.
- **Observability gaps:** see `ops-reality.md`. The RED/saturation story is
  thin: `/metrics` exposed only ~10 series (all 0 after a real write); no
  per-route/per-listener RED, no leader/term/role gauge, no histogram
  `_bucket` lines at all (empty schedule → SLO burn-rate alerts have no
  series).
- **Container hardening:** `docker/Dockerfile.runtime` is `USER configd`
  (non-root, good) but base is `eclipse-temurin:25-jre-noble` (**not**
  distroless/scratch) and `apt-get install curl` adds surface. K8s
  `securityContext` (in `deploy/kubernetes/configd-bootstrap.yaml:98-117`)
  does set `runAsNonRoot`, `readOnlyRootFilesystem: true`,
  `allowPrivilegeEscalation: false`, capability drop, seccomp. **There is no
  XDP/eBPF anywhere in the repo** (grep → empty) — the "capabilities limited
  to what XDP requires" expectation is moot; this is a pure-JVM service.

---

## Scripts created (harness-enablement log)

| Path | Purpose | Status |
|------|---------|--------|
| `/home/ubuntu/ws-smoke/launch-cluster.sh` | ad-hoc 3-node launcher used to drive steps 2-7 manually | scratch (workspace) |
| `/home/ubuntu/Code/Configd/gates/smoke-multinode.sh` | **deliverable** machine-verifiable control-plane gate (steps 2,3,4,6) | committed to repo; 5/5 pass, idempotent, ~10 s, exit-nonzero on any failure |

The gate's header documents that the **edge step is excluded because it is
not demonstrable** (step 5 root cause). It re-resolves the leader and polls
for replication/linearizable-read to absorb the documented ReadIndex (150 ms)
flakiness and follower replication lag; verified non-flaky over 5 consecutive
runs with zero leftover JVMs/ports.

---

## Candidate findings

- **P0:** System cannot be demonstrated end-to-end — committed writes never
  reach any edge (§5; FanOut dead-ended at `ConfigdServer.java:360`, no
  fan-out listener port, no edge transport/main).
- **P1:** `configd_write_commit_total` / `configd_write_commit_seconds`
  wired to `StateMachineMetrics.NOOP` (`ConfigdServer.java:209` uses the
  4-arg ctor) → core write metrics read **0** on a healthy committed write;
  the `ConfigdControlPlaneAvailability` SLO alert's denominator is therefore
  always 0 (see ops-reality.md).
- **P1:** No histogram `_bucket{le=…}` series emitted at runtime (server uses
  `new PrometheusExporter(registry)` single-arg → empty schedule,
  `PrometheusExporter.java:83-85`); every MWMBR burn-rate alert/dashboard
  panel querying `*_seconds_bucket` matches **no time series**.
- **P2:** Readiness never flips false on drain; no startup probe; graceful
  shutdown has no active-conn drain window beyond `HttpServer.stop(2)`.
- **P2:** Runbooks/runsheet instruct operators to use a non-existent `configd`
  CLI and non-existent `/raft/*` endpoints (see ops-reality.md).
