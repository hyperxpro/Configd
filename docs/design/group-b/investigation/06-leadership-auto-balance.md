# Group B §2.6 — Leadership auto-balance (investigation)

Read-only investigation. No production code was modified. The goal is a concrete,
industry-grounded design for a control loop that keeps Raft-group leaders spread
~1-per-box across a multi-Raft Configd cluster, closing the measured op gap where
leader drift collapses horizontal scale back toward the single-box plateau.

## 1. Executive summary and recommendations

The primitive already exists and is fully wired: only the leader of a group can
initiate a transfer, the transfer is bounded and self-healing, and it is
ADMIN-gated and owner-thread-confined. What is missing is the *control loop* that
decides *when* and *which* leadership to move. This gate builds that loop over the
existing `transferLeadership`.

Recommendations on every fork:

- **Where it runs: DECENTRALIZED, per-leader (the CockroachDB model), not a central
  coordinator.** Only the current leader of a group can transfer that group's
  leadership (`RaftNode.transferLeadership` returns `false` if `role != LEADER`,
  `RaftNode.java:1076`). Decision authority is therefore *already* sharded by
  ownership: two nodes can never contend to move the same group, because exactly one
  node leads it. Each node runs a loop that sheds — never pulls — one of its own
  groups to an under-loaded peer. A central coordinator would need its own election
  (a meta-Raft or reuse of group-0's leader), which is a single-point/split-brain
  surface, and it would still have to route each transfer to the group's real leader
  since only the leader can execute it. Reject the coordinator.

- **A cluster-wide leader view does NOT need to be built as new distributed
  machinery — it is derivable locally.** Every node hosts a replica of every group
  (one `RaftConfig` built from `config.peers()` drives all groups —
  `ConfigdServer.java:380`, group build loop `:576`–`:608`), so every node's
  per-group `RaftNode` already tracks the current leader
  (`private volatile NodeId leaderId`, `RaftNode.java:90`, updated on every
  AppendEntries at `:2012`; read off-owner via `leaderId()` `:1882` and
  `monitorView().leaderId()` `:1875`). The loop computes the full
  leader-count-per-node distribution from its own local view across
  `driver.groupIds()`. The only new code is a small read helper; no gossip, no new
  RPC. See §3 for the one caveat (follower `leaderId` staleness) and why it is
  harmless.

- **Imbalance metric: absolute max-minus-min leader count; act at spread ≥ 2.** At
  Configd's scale (N ≤ 16 groups, `MAX_SHARD_COUNT`, `ConfigdServer.java:127`), a
  fractional 5%-of-mean threshold — CRDB's rule — is meaningless (5% of a mean of ~2
  is 0.1 leaders). An absolute spread threshold is the correct analogue here. Spread
  of 1 is *unavoidable* whenever G is not divisible by M, so the minimum actionable
  imbalance is 2.

- **Cadence 30s, jittered ±25%; one transfer per node per cadence; 60s per-node
  cooldown after a transfer.** Conservative and dampened, mirroring CRDB's 1-minute
  jittered interval and one-at-a-time shed, and PD's leader-schedule-limit.

- **Instability back-off: pause the cycle on recent term churn, a pending membership
  change, any unknown (null) leader in the view, or an in-flight transfer.** When in
  doubt, do nothing — a rebalance is never urgent.

- **On by default, with a hard kill switch, and inert at N=1 or single-node.**
  Shipping it off-by-default reproduces the exact gap the gate exists to close (an
  operator who must know to flip it on is the failure mode already observed). The
  conservative dampening makes on-by-default safe. Offer a one-release **dry-run
  (observe-only)** mode as the safest rollout, for the operator to choose.

## 2. Reference-system findings (primary sources)

### 2.1 CockroachDB — decentralized, per-store lease rebalancing

Model: each range has exactly one *leaseholder*, which is "always the same replica
as the Raft leader, except briefly during lease transfers"
(reads-and-writes-overview). Every *store* independently runs a rebalancer; there is
no central mover. This is the decentralized design Configd should adopt.

- **Cadence.** `kv.allocator.load_based_rebalancing_interval`, default `1m0s` —
  "the rough interval at which each store will check for load-based lease / replica
  rebalancing opportunities" (cluster-settings). The interval is **jittered ±25%**:
  `store_rebalancer.go`'s `jitteredInterval` (`store_rebalancer.go:1237`) "returns a randomly jittered (+/-25%)
  duration from checkInterval". The jitter matters — it desynchronizes the
  per-store loops so they do not fire in lockstep and herd onto the same target.

- **Imbalance threshold.** `kv.allocator.lease_rebalance_threshold`, default `0.05`
  — "minimum fraction away from the mean a store's lease count can be before it is
  considered for lease-transfers" (cluster-settings). Companion thresholds:
  `range_rebalance_threshold` `0.05`, `qps_rebalance_threshold` `0.1`.

- **Explicit anti-thrash tolerance.** The replication-layer doc states the system
  "intentionally tolerates small deviations between nodes to prevent thrashing
  (i.e., excessive adjustments trying to reach an equilibrium)." When all replicas
  share a locality, "decisions focus solely on lease counts."

- **Leases first, then replicas.** `store_rebalancer.go` rebalances *leases* (cheap
  — just move the leaseholder) before *replicas* (expensive — move data), and only
  touches replicas if it "ran out of leases worth transferring and load [is] still
  above desired threshold." Configd's analogue: moving a Raft leader is the cheap
  operation; there is no data-move axis in this gate.

- **One at a time.** The rebalancer "processes one lease at a time within each
  iteration loop", checking `OwnsValidLease` and attempting a single transfer before
  the next candidate.

- **Minimum-load floors (churn prevention).** `minLeaseLoadFraction = 0.005` (`store_rebalancer.go:94`),
  `minReplicaLoadFraction = 0.02` (`:98`) — do not bother moving something responsible for a
  trivial fraction of load. Request distribution is tracked as an **exponentially
  weighted moving average**, so a momentary spike does not trigger a move.

- **Only the owner sheds.** A store moves leases *away from itself* when overfull;
  only the current leaseholder can transfer its lease. This ownership rule is what
  makes decentralization safe against double-moves.

### 2.2 TiKV / PD — centralized scheduler, with strong anti-storm accounting

Model: multi-Raft (many Regions), balanced by PD, a single elected central
scheduler. Configd is NOT adopting the central-scheduler topology, but PD's
*anti-storm* mechanisms are the sharpest primary source for "don't herd" and are
worth porting as ideas.

- **balance-leader-scheduler.** Picks a source store (most leaders) and target store
  (fewest leaders); `shouldBalance()` compares `sourceScore`/`targetScore` and
  `createOperator` returns `nil` when "the difference between the two stores is
  tolerable" — a score **tolerance** that suppresses transfers on small imbalances
  (`balance_leader.go`).

- **Pending-operator influence — the herd-avoidance mechanism.** When an operator is
  created, `GetOpInfluence()`/`operator.AddOpInfluence()` (`balance_leader.go:340`,`:422`) factor the
  *pending* transfer's expected effect into store scores, and candidates are re-sorted
  (`resortStoreWithPos`, `:305`), "ensuring future selections account for uncommitted
  operations and prevent over-concentration on single targets" (`balance_leader.go`).
  This is exactly the "two shedders onto one victim" problem; PD solves it by making
  in-flight moves visible to the next decision.

- **Concurrency limit.** `IsScheduleAllowed()` gates on
  `OperatorCount(OpLeader) < leader-schedule-limit` (`balance_leader.go:229`). `leader-schedule-limit` default
  `4`; `BalanceLeaderBatchSize = 4` (`balance_leader.go:48`; ceiling `MaxBalanceLeaderBatchSize = 100`, `:50`);
  `scheduler-max-waiting-operator` default `5` (pd-control).

- **Back-off on repeated failure.** `retryQuota` (`balance_leader.go:164`) limits retries per store; a store that keeps failing
  to schedule is `attenuate()`d (progressively reduced retry allowance), reset on success
  via `resetLimit()` (`:406`–`:408`). Plus a
  per-store `store limit` rate cap.

### 2.3 etcd — single group, no auto-balance

etcd is a single Raft group and exposes `MoveLeader` as a *manual*, admin-initiated
transfer. The proto is explicit — "MoveLeader requests current leader node to
transfer its leadership to transferee" (`api/etcdserverpb/rpc.proto:260`), surfaced as
`POST /v3/maintenance/transfer-leadership` (`:263`) taking a single
`MoveLeaderRequest.targetID` (`:1085`), available since etcd v3.3; it is backed by the
raft library's "Leadership transfer extension" (`etcd-io/raft README:26`). There is no
automatic leader balancing because a single group has nothing to balance. Lesson for Configd: our
per-group primitive is the etcd `MoveLeader` equivalent; the *new* thing is the
multi-group control loop that decides targets automatically.

### 2.4 The reference design pattern extracted

signal → threshold → decide-one-move → cooldown:

1. **Signal**: per-node leader/lease count (optionally load), smoothed against
   transient spikes (CRDB's EWMA).
2. **Threshold**: act only when a node deviates from the mean by more than a
   tolerance (5% CRDB; a score margin in PD). Prevents thrashing on tiny imbalance.
3. **Decide one move**: most-overfull source → most-underfull target; move one
   leader; account for in-flight moves before the next pick (PD `opInfluence`).
4. **Cooldown / limits**: bounded, jittered check interval (1m ±25% CRDB); cap
   concurrent transfers (leader-schedule-limit 4, PD); back off on repeated failure
   (PD `retryQuota`/`attenuate`).

## 3. Configd seam grounding (file:line)

- **The primitive.** `RaftNode.transferLeadership(NodeId target)`,
  `configd-consensus-core/.../RaftNode.java:1074`. Guards, in order: asserts the
  owner thread (`:1075`); returns `false` if `role != LEADER` (`:1076`), if target is
  self (`:1079`), if target is not a voter (`:1082`), or if a config change is
  pending (`:1085`). Then catches the target up and sends TimeoutNow. Only the leader
  can move leadership, and it refuses mid-reconfig — two defensive properties the
  loop inherits for free.

- **Cost of a transfer, and its self-healing bound.** While `transferTarget != null`
  the group rejects every write with `TRANSFER_IN_PROGRESS` (`RaftNode.java:1045`) —
  the brief unavailability cost. If the target never catches up, `tickHeartbeat`
  aborts the transfer after ~one election timeout and resumes proposals without
  stepping down (`:1922`). So a single move is bounded and self-recovering, but not
  free — which is exactly why one-at-a-time plus cooldown is the right discipline.

- **Admin path (already wired).** `DriverLeadershipAdmin.transferLeadership(groupId,
  target)` (`configd-server/.../DriverLeadershipAdmin.java:59`) resolves the group's
  `RaftNode`, marshals `transferLeadership` onto the group's owner executor
  (`:147`–`:159`), and awaits under a 5s bound (`DEFAULT_AWAIT_MILLIS`, `:36`).
  Surfaced as ADMIN-gated `POST /v1/admin/groups/{gid}/transfer-leadership?target=`
  (`AdminApiHandler.java:486`), replay-protected (`:549`), tested by
  `LeadershipTransferAdminTest`. The loop should drive transfers through this same
  owner-confined path, not a new one.

- **Group model.** `N ∈ [1,16]` static shards; `resolveShardCount`
  (`ConfigdServer.java:1637`), fixed at deploy (`enforceTopologyDescriptor`, `:1671`).
  Every node replicates every group (uniform membership from `config.peers()`,
  `ConfigdServer.java:380`; `MultiRaftDriver` holds all groups on one node,
  `MultiRaftDriver.java:49`, `groupIds()` `:636`, `getGroup()` `:627`).

- **Owner threads are NOT leadership.** The `floorMod(gid, N)` owner index
  (`MultiRaftDriver.currentOwnerIndex` `:277`) and `rehomeGroup` (`:393`) balance
  groups across *CPU owner threads on one box* — a within-box concern, dormant in
  production. Leadership balance is a *cross-box* concern (which node is Raft leader).
  The loop must operate only on the leadership axis and must not be conflated with
  rehoming.

- **Leadership observability today.** Per-group gauges `raft.shard.leader.<gid>`
  (1 if this node leads gid, `ConfigdServer.java:2059`) and the node-level
  `raft.node.leader_count` (how many groups this node leads, `:2063`), plus per-shard
  term/commit/apply-lag (`registerPerShardMetrics`, `:2050`). These are per-node
  scrapes; an external Prometheus aggregates them. **There is no in-process aggregated
  cluster-leader-view object.** The raw datum the loop needs — *who* leads each group
  — is available locally on every node via `getGroup(g).monitorView().leaderId()`
  across `driver.groupIds()`, because every node is a replica of every group.

- **The one caveat.** A follower's `leaderId` is only as fresh as its last
  AppendEntries (≤ election-timeout stale, `null` during an election, cleared at
  `:1907`/`:2263`). The loop already backs off on unknown leaders and term churn (§4),
  so stale/null entries only delay a correction by one cadence — they never cause a
  wrong move. No new distributed view is required; the small new piece is a local
  aggregation helper.

- **Cadence infrastructure to reuse.** The consensus tick runs every
  `TICK_PERIOD_MS = 10` (`ConfigdServer.java:111`, per-owner loop `:1204`).
  Independent periodic work already uses dedicated single-thread scheduled executors
  — `nodeAnchorExecutor` at 1000ms (`:1179`) and `tlsReloadExecutor` at 60s (`:1267`).
  The balance loop should get its own such executor so it never touches or delays the
  10ms consensus tick. Election timeout is 150–300ms, heartbeat 50ms
  (`ConfigdServer.java:376`–`:378`) — the units that bound a transfer's cost and the
  instability window.

- **Instability signals available.** Per-group `monitorView().currentTerm()` (term
  bump == an election/leadership change; Configd already derives an elections counter
  from term deltas at `ConfigdServer.java:1221`); role transitions; the primitive's
  own `configChangePending` refusal (`RaftNode.java:1085`); `replicationLagMax` in
  `monitorView` for target caught-up-ness. `configChangePending` is not currently in
  `monitorView` — see open question 5.

- **Config plumbing note.** The existing transfer tunable uses
  `Long.getLong("configd.admin.transferAwaitMillis", …)`
  (`DriverLeadershipAdmin.java:42`). The new loop's tunables must instead be read
  through the Gate-1 ConfigSource SPI (arc rule), so they compose with the arc and
  are testable/reloadable rather than JVM system properties.

## 4. Recommended loop design for Configd

**Where it runs.** A decentralized `LeaderBalanceLoop`, one per node, on its own
dedicated single-thread `ScheduledExecutorService` (modeled on `tlsReloadExecutor`).
Each node only ever *sheds* leadership of a group it currently leads; it never pulls.
Ownership shards the decision, so two nodes cannot move the same group. The actual
transfer goes through the existing `DriverLeadershipAdmin` owner-confined path.

**Each cycle (per node):**

1. Build the cluster-wide distribution from the local view: for every
   `g ∈ driver.groupIds()`, read `getGroup(g).monitorView().leaderId()`; tally
   `leaders[node]`. Candidate target set = `config.peers() ∪ self`.
2. **Instability gate (back off — do nothing this cycle — if any hold):**
   - any group's `leaderId == null` (mid-election / incomplete view);
   - any group's `currentTerm` increased within `instabilityWindowMs` (recent
     election/leadership churn), tracked across cycles from `monitorView`;
   - a membership change is in flight (see open question 5 — either a surfaced
     `configChangePending` signal, or treat the primitive's `false` return as the
     back-off);
   - this node has a transfer already in flight on any group it leads;
   - this node is inside its post-transfer cooldown.
3. Compute `spread = max(leaders) - min(leaders)`. If `spread < imbalanceThreshold`
   (default 2), do nothing.
4. Act only if this node is a max-holder. Choose one group it leads and one target
   that is a current min-holder (below the mean). If several minima, pick with
   per-round jitter to avoid two shedders herding onto one victim (the PD
   `opInfluence` concern, reduced here by one-move-per-node-per-cadence).
5. Initiate exactly ONE transfer via `DriverLeadershipAdmin`. Record the cooldown
   timestamp regardless of the immediate result. A `false`/timeout result is treated
   as a no-op and folds into back-off.

**Imbalance metric and threshold.** Absolute `max - min` leader count; act at `≥ 2`.
Ideal ≈ G/M; spread 1 is optimal whenever `G mod M ≠ 0`, so 2 is the minimum
actionable imbalance. This is the integer, small-N analogue of CRDB's 5% tolerance
(§2.1) — chosen because a fractional threshold is meaningless at N ≤ 16.

**Cadence.** `intervalMs` default 30000, jittered ±`jitterPct` (25%). Leadership
drift is slow (failovers/restarts, not continuous), so a corrective loop at tens of
seconds converges a drifted cluster within a couple of minutes without being twitchy.
Jitter desynchronizes nodes' loops (CRDB `jitteredInterval`).

**Dampening / cooldown.** One transfer per node per cadence
(`maxInFlightTransfers = 1`). Per-node `cooldownMs` default 60000 (≈ 2× cadence)
before the next shed, letting the moved leadership settle and the distribution
re-measure. This is CRDB one-at-a-time + PD leader-schedule-limit, adapted.

**Instability back-off.** As in step 2. Conservative by design: correctness and
availability always beat a perfectly flat distribution. The primitive's internal
`configChangePending` refusal (`RaftNode.java:1085`) is a hard floor even if the loop
mis-times a membership change.

**Fail-safe / on-off.** `enabled` default `true` (hard kill switch). Inert at
`shardCount == 1` (spread always 0) and at single-node (M = 1) — so the active
surface exists only at N > 1 and M > 1, precisely the horizontal-scale regime this
gate targets. Provide `dryRun` (default false): emit the move it *would* make as a
metric/log without executing, for a safe first-release rollout.

**Tuning parameters (via the Gate-1 ConfigSource SPI), with defaults:**

| key | default | meaning |
|---|---|---|
| `configd.balance.enabled` | `true` | hard kill switch |
| `configd.balance.dryRun` | `false` | observe-only: log/metric the would-be move, do not execute |
| `configd.balance.intervalMs` | `30000` | check cadence |
| `configd.balance.jitterPct` | `25` | ± cadence jitter |
| `configd.balance.imbalanceThreshold` | `2` | min max−min spread to act (absolute count) |
| `configd.balance.cooldownMs` | `60000` | per-node post-transfer cooldown |
| `configd.balance.maxInFlightTransfers` | `1` | transfers initiated per node per cadence |
| `configd.balance.instabilityWindowMs` | `5000` | recent term-churn / transfer look-back forcing back-off |

**Observability additions** (register through the `ConfigdMetrics` catalog — the
wiring-6 lesson that alert/metric additions need the catalog plus a full-reactor
build): `configd.balance.leader_spread` (max−min from the local view),
`configd.balance.transfers_initiated_total`,
`configd.balance.skipped_unstable_total{reason}`,
`configd.balance.cooldown_active`, and, in dry-run, `configd.balance.would_transfer`.
Existing `raft.node.leader_count` (`ConfigdServer.java:2063`) and
`raft.shard.leader.<gid>` (`:2059`) already give operators the external view.

## 5. Test matrix

Most of these are deterministic sim/unit tests over the existing testkit
(`MultiShardSim` tracks per-shard `cachedLeader`, `MultiShardSim.java:124`;
`InMemoryRaftCluster`; `EdgeLeaderKillScenarioTest`), no real network. The
horizontal-scale test is the paid EC2 payoff.

1. **Convergence.** G=8, M=4; force all leaders onto one node; run the loop; assert
   convergence to spread ≤ 1 within K cadences and that it then STOPS (no further
   transfers once balanced).
2. **No-thrash on optimal-but-uneven.** G=5, M=3 (optimal {2,2,1}); assert ZERO
   transfers — directly exercises the ≥ 2 threshold.
3. **No-thrash on transient.** Inject a momentary imbalance that self-corrects
   between cadences; assert no fire, or at most one, with no ping-pong of the same
   group between two nodes across cadences.
4. **Election-storm back-off.** Induce repeated elections (drop heartbeats /
   partition-heal) so terms churn; assert ZERO transfers while unstable, then
   convergence once terms settle.
5. **Membership-change back-off.** Start an add/remove-node on a group; assert the
   loop does not transfer that group until the change commits, and that the
   primitive's `configChangePending` refusal (`RaftNode.java:1085`) is honored (a
   `false` result folds into back-off).
6. **One-at-a-time.** From a heavily skewed start, assert ≤ 1 transfer initiated per
   node per cadence.
7. **Cooldown.** After a transfer, assert no further transfer from that node for
   `cooldownMs`, even while still slightly imbalanced.
8. **Decentralized safety / no double-move.** Two overloaded nodes shedding in the
   same round do not drive a new imbalance that oscillates; assert eventual
   convergence without oscillation, and assert no two nodes ever transfer the same
   group (a property, true by construction).
9. **N=1 / M=1 inertness.** At `shardCount=1` and at single-node, assert zero
   transfers and byte-identical behavior (the arc's N=1-inert discipline).
10. **Horizontal-scale restoration (integration/EC2 payoff).** Reproduce the
    leader-drift scenario (leaders collapse onto one box → throughput falls to the
    single-box plateau); enable the loop; assert leaders re-spread and aggregate
    throughput recovers toward the multi-box number. Sim first (`MultiShardSim`),
    then a paid EC2 run.
11. **Dry-run.** With `dryRun=true`, assert would-transfer metrics/logs are emitted
    and zero real transfers occur.
12. **Kill switch.** With `enabled=false`, assert zero transfers regardless of
    imbalance.

## 6. Open questions needing an operator decision

1. **Rollout posture:** on-by-default with conservative defaults + kill switch
   (recommended), or one release of dry-run then on, or off-by-default? Off-by-default
   reproduces the very gap this gate closes; recommend on-with-kill-switch or
   dry-run-first.
2. **Threshold form:** absolute spread ≥ 2 (recommended for N ≤ 16) versus a
   fractional/mean-relative threshold. Recommend absolute at this scale.
3. **Count vs load signal:** balance by leader COUNT (simple; PD-style) or by LOAD /
   QPS per group (CRDB-style)? Groups are hash-sharded so per-group load is roughly
   uniform, making count a good proxy. Recommend count for v1; note load-based as a v2
   option if a hot shard emerges.
4. **Exact numbers:** cadence 30s / cooldown 60s / instability window 5s are sane
   defaults but may want tuning to the deployment's failover frequency and election
   timeout. Confirm.
5. **Membership-change signal:** should `configChangePending` (and a "recent
   instability" timestamp) be surfaced in `monitorView` for clean back-off, or is
   relying on the primitive's `false` return plus a recent-term-churn signal enough?
   Recommend a lightweight surfaced signal; confirm.
6. **Lease preferences / pinning:** should some groups be pinned to preferred nodes
   (CRDB "lease preferences")? Not needed for v1 (no locality model yet); note it as
   the natural extension point.
7. **Rehoming orthogonality:** confirm the loop is cross-box leadership only and never
   touches owner-thread rehoming (`MultiRaftDriver.rehomeGroup`), which is a separate,
   dormant, within-box axis.
