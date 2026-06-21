# Phase 0 — Workstream B — Stage 1B Adversarial Review (the R-01 deletion)

> Independent adversarial red-team of commit `682cbcf` (R-01 deleted @ N=1), in an isolated worktree,
> mandated to TRY TO BREAK the deletion. Companion to the implementer's verification and the lead's
> line-by-line diff review + independent re-verification (server 165/0, sim 2052/0, net-catch 3/3).

## VERDICT: **Stage-1B SOUND — R-01 safely deleted @ N=1.**
Could not produce a silent data race or a behavioural divergence from R-01 at N=1. All five mandates
PASS with real break-it evidence. One pre-existing, equivalence-preserving caveat (not a regression).

## Mandate results

**#1 — a missed off-owner access the net does NOT cover (the highest-value hunt): PASS, no hole.**
Enumerated every public `RaftNode` method touching non-volatile state, classified O/M/S, and traced
every `RaftNode`/`driver.getGroup`/`propose`/`routeMessage` consumer across server / observability /
fanout / edge / distribution. Every off-owner consumer reads only the **S-set** (`role`/`leaderId`/
`nodeId`) or the published `monitorView()`: health readiness, the `ConfigWriteService` leader hint,
`HttpApiServer` leader reads, and the raft gauges (pushed into `MetricsRegistry` by the owner[0] tick
lambda via `monitorView()` — the Prometheus scrape thread reads the *registry*, never the node). The
four co-tenant riders reference no Raft type (`PropagationLivenessMonitor` is push-fed via
`updateLeaderCommit(long)`). **No ungated off-owner reachable path to non-volatile `RaftNode` state.**
The reviewer wrote its own probe (`AdversarialOffOwnerProbeTest`, run then removed): a foreign thread
calling `metrics()`/`currentTerm()`/`log()`/`clusterConfig()` off-owner **trips the net** (counter ≥1,
prod-mode SEVERE, server keeps serving); the safe set stays silent — proving the guard catches the
worst case (a naive future status/admin handler) if one were ever introduced.

**#2 — bind-ordering / publication race (H-6 + C2): PASS.** `defaultGroupOwner.execute(raftNode::
bindOwnerThread)` (`ConfigdServer:449`) is provably the FIRST submit on owner[0] — `setGroupCommit`
only *installs* the flush lambda (no eager submit); inbound registration + `tcpTransport.start()` +
the tick `scheduleAtFixedRate` all come after. Single-thread FIFO ⇒ bind runs before any inbound /
propose / tick / flush. `monitorView` is volatile + ctor-seeded (`RaftNode:368`) + published via the
`addGroup`→CHM `put` happens-before ⇒ a racing scrape never sees null.

**#3 — behavioural divergence from R-01 at N=1: PASS, none.** `tickOwner(0)` iterates
`groups.entrySet()` filtered by `ownerIndexOf(key)==0`; at N=1 `floorMod(key,1)==0 ∀key`, so the set
is identical to the old `tick()` over `groups.values()`; same `ConcurrentHashMap`, same FIFO on
owner[0], no extra hop on the consensus path.

**#4 — shutdown races: PASS** (one pre-existing, equivalence-preserving caveat). Drain
`readDispatchExecutor` first, then per-owner `shutdownExecutor(ownerByIndex(i),5s)`. *Caveat (NOT a
regression):* the propose-timeout cleanup (`cancelCommitOutcome`) is submitted to an owner from the
HTTP thread and may be dropped if it lands in the owner's shutdown window — **identical to R-01**
(which used `tickExecutor.execute` the same way), the write already returned `Indeterminate`, and it
is best-effort map cleanup at process exit. No task touching consensus state is lost or runs on a
torn-down node.

**#5 — net-catch test non-vacuous: PROVEN.** Neutered `assertOwnerThread()` to `if (true) return;`
→ captured RED at BOTH layers: consensus-core `RaftNodeConcurrencyStressTest.offOwnerAccessTrips...`
(`Failures: 1`) and server `OwnerNetCatchesOffOwnerInboundTest.offOwnerInboundTrips...` (`Failures: 1`,
the off-owner detector specifically; clean-path + wired-clean-run stayed green). Reverted → green
restored (consensus-core 342/0/0, net-catch 3/3, stress 2/2, monitor-view 4/4).

## Could-not-break summary
Attempted: an ungated off-owner read via every server/observability/fanout/edge consumer; a
bind-ordering early-submit race; a `monitorView==null` NPE; an iteration/timing divergence at N=1; a
shutdown task touching a torn-down node; a vacuous net; a hypothetical future off-owner status read.
**None broke it.** R-01 is safely deleted at N=1, behind a net proven (at two layers) to still catch.

## Lead disposition
- Verdict accepted; Stage 1 checkpoint blessed (branch `af34d63`+).
- The §4 caveat is pre-existing and behaviourally exact vs R-01 — recorded, no action.
- The reviewer's "§4.1 doc still says unguarded" nit is a **false positive** — §4.1 was updated in the
  H-3 work to "now CLOSED ... then guarded all five"; the phrase it saw is past-tense describing
  Workstream A. No doc change needed.
