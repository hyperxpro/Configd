# EXP-003 — RR-008: inbound-routing Throwable swallowed → mute zombie

- **Workstream:** B (storage & durability faults)
- **Register row:** RR-008 (P1, Consensus / error handling), OPEN at pickup
- **Status:** CODE-FINDING confirmed → fix landed

## 1. Hypothesis (oracle, cited)

A follower whose inbound message handling throws — the motivating case being a **disk
write failing during `applyCommitted -> stateMachine.apply`** — must SURFACE the failure
(structured SEVERE log + a metric), per the H-009 precedent (`docs/...` tick-loop fix) and
the storage-fault oracle catalogue (`storage-fault-layer-design.md` §2, write-failure row).
A silently swallowed Throwable means **no ack, no log to aggregation, no metric** — the
node drops the message and the cluster cannot tell a disk-failing follower from a slow one.
Architecture §6 *Gray Failures* expects data-plane failures (disk I/O) to be detected and
surfaced (fsync latency → step-down; the principle: disk faults are observable, not silent).

## 2. Injection

`raftInboundHandler` marshals inbound routing onto the single tick executor:
`raftExecutor.execute(() -> driver.routeMessage(groupId, message))`. The H-009 fix wrapped
only the **tick** lambda; this `execute()` task had **no try/catch**. On a
`ScheduledThreadPoolExecutor`, a Throwable from `execute()` goes to the worker's default
uncaught handler (stderr — invisible to log aggregation), the worker is replaced, and the
message is dropped with no ack and no metric.

Deterministic reproduction (`InboundRoutingThrowableHandlerTest`): a follower whose response
`transport.send` throws — `handleAppendEntries` always ends by sending an
`AppendEntriesResponse`, so any inbound append makes `routeMessage` throw. (A disk write
failing during apply is the motivating real case; it reaches the same swallow path and is
exercisable via `FaultInjectingStorage.failNextWrites` over a storage-backed WAL — a
kill-matrix cell.) Repro:
`./mvnw -pl configd-server test -Dtest=InboundRoutingThrowableHandlerTest`.

## 3. Observation

- **Pre-fix (captured RED, `rr008-prefix-failure.txt`):** the wiring test fails —
  `inbound routing Throwable must be surfaced as a counter (RR-008) ... expected: not <null>`
  — the executor swallowed the Throwable; no counter, no SEVERE log. (The executor *does*
  keep serving subsequent messages — the worker is replaced — so it is not a *permanent*
  death like the tick loop; but a persistent disk fault drops every message identically:
  a mute zombie toward the cluster, SRE-invisible.)
- **Post-fix (GREEN, 2/2):** the routing task catches the Throwable and routes it to
  `handleInboundRoutingThrowable` → `configd_inbound_routing_throwable_total{class}`
  counter increment + a SEVERE log record carrying the throwable; the executor keeps
  serving subsequent messages.

## 4. Verdict

**CODE-FINDING (P1), CONFIRMED.** The H-009 tick-loop fix did not cover the inbound-routing
path; the swallow was real and SRE-invisible. Fix: wrap the routing task in try/catch →
`handleInboundRoutingThrowable` (mirrors `handleTickLoopThrowable`: cardinality-bounded
counter + structured SEVERE log). `configdMetrics` hoisted above the handler registration
so the handler has a stable metrics handle.

Mutation-revert: restoring the bare swallow makes the wiring test go RED (counter null);
the direct-handler test stays GREEN (it drives `handleInboundRoutingThrowable` directly) —
so the wiring test specifically pins the catch. configd-server suite 134/0/0/0 post-fix.

## 5. Recovery bound

N/A (observability fix, not a recovery path). The value is *detectability*: a disk-failing
follower is now alertable (the counter + SEVERE log) rather than a silent black hole. The
follower still cannot make progress while its disk fails — that degradation is the
ENOSPC/disk-pathology oracle (B3, pending), which this fix makes observable.
