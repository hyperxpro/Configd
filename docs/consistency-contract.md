# Consistency Contract — Configd

> **Phase 2 deliverable.** Every guarantee is a testable invariant.
> Every invariant has both a TLA+ formalization and a runtime assertion.

---

## 1. Linearizability Scope

### Linearizable Operations
All write operations to any Raft group (global or regional) are **linearizable**:
- `PUT(key, value)` — config creation/update
- `DELETE(key)` — config removal
- `BATCH(mutations[])` — atomic batch of puts/deletes within a single Raft group — **PLANNED, not yet wired** (CM-033): `HttpApiServer` exposes only `PUT`/`GET`/`DELETE` on `/v1/config/{key}`; there is no BATCH endpoint. The guarantee is specified here for when BATCH lands; it is not a claim about the current API surface.

Linearizable reads are available via **ReadIndex** on the control plane:
- Client sends read request to Raft leader
- Leader records current commit index, sends heartbeat to confirm leadership
- Once confirmed, leader waits for local state machine to apply through recorded index
- Returns result — guaranteed to reflect all writes committed before the read began

#### Write acknowledgement and failure taxonomy (ADR-0033)
A write is acknowledged **only after quorum commit + local apply**. The client sees exactly one of (HTTP mapping in parentheses):

| Outcome | HTTP | Meaning |
|---|---|---|
| **Committed(S)** | 200 `Committed: seq=<S>` | Durably committed and applied; `S` is the applied-mutation sequence (the read-cursor version, §3/§6). 200 is returned on **no other path**. |
| **NotLeader** (pre-append) | 503 + `X-Leader-Hint` | This node was not the leader; nothing was appended. Definite, safe to retry. |
| **Lost** (post-append) | 503 + `X-Leader-Hint` if known | Leadership lost before the entry committed. Definite non-commit, safe to retry. |
| **Indeterminate** | 504 | Deadline expired with the outcome unknown (quorum slow / leadership in flux). The write MAY still commit later; client may re-read or retry (PUT/DELETE are last-writer-wins idempotent). |
| Overloaded | 429 | Backpressure (pre-append). Retryable. |
| Validation | 400 | Permanent. |

**200 means committed, never "accepted":** `Committed(S)` is surfaced only from the apply path, so an acknowledged write is never lost on failover (RR-004; the prior `200 "Accepted: proposalId=N"` ack-before-commit behavior is removed).

#### GLOBAL / security strong reads (ADR-0030 INV-1)
A read of a **GLOBAL/security ("strong-read") key** (a configured key-class, default prefix `secure/`) is **always served via the linearizable ReadIndex path**, regardless of the requested consistency. If the linearizable read cannot be confirmed (not leader / ReadIndex unconfirmed / timeout / no read path), the read **fails closed** — HTTP 503 with `X-Fail-Closed: strong-read` and `X-Leader-Hint` — and the local/bounded-stale value is **never** served (RR-020). See §9.

### Explicitly Non-Linearizable Operations
- **Edge reads** — bounded staleness (see §2)
- **Cross-group reads** — no ordering guarantee between reads to different Raft groups
- **Stale reads** — explicitly requested stale reads on control plane followers

### Formal Invariant
```
INV-L1: ∀ operations op1, op2 on the same Raft group:
  if op1 completes before op2 begins (real time),
  then op1's effect is visible to op2
```

---

## 2. Edge Staleness Bound

### Hard Upper Bounds
| Percentile | Maximum Staleness |
|---|---|
| p99 | 500ms |
| p999 | 1s |
| p9999 | 2s |

### Staleness Measurement
Staleness at an edge node = `edge_wall_now − commit_timestamp(last_applied_notification)`, where `commit_timestamp` is assigned by the **leader at commit/apply time** and delivered on the commit-notification stream (§4.6). The edge `StalenessTracker` stores the commit timestamp of the most recently applied notification and reports `now − that`. The systematic error is the bounded leader→edge one-way propagation delay; inter-node clock skew is bounded operationally by NTP (target skew ≤ 50ms) and is the only residual error term. **No per-entry HLC is carried in the Raft log** (ADR-0035, RR-015; per-entry HLC was contract fiction — `LogEntry` has no timestamp field). Session 3 implements the measurement; the bounds below are the contract targets.

### Behavior on Violation

| Staleness | State | Behavior |
|---|---|---|
| < 500ms | `CURRENT` | Normal operation |
| 500ms - 5s | `STALE` | Set `X-Configd-Stale: true` header on all read responses. Increment `configd.edge.staleness_violation_total` counter. |
| 5s - 30s | `DEGRADED` | Above + emit alert. Edge node reports unhealthy to load balancer. Continue serving stale data. |
| > 30s | `DISCONNECTED` | Above + trigger re-bootstrap sequence. Attempt snapshot catch-up from regional relay. |

> **Implementation status (ADR-0035 / RR-015).** The state thresholds and bounds above are the contract targets and stay. The *measurement mechanism* — the leader-assigned commit-notification timestamp consumed by the edge `StalenessTracker` — is **implemented in Session 3** (the data-plane / commit-notification §4.6 interface). Today `StalenessTracker` measures local idle time (`nanoTime − lastUpdateNanos`), a proxy; Session 3 makes the commit timestamp load-bearing. Until then, the `STALE`/`DEGRADED`/`DISCONNECTED` transitions are exercised by `StalenessUpperBoundTest` against the proxy clock.

### Formal Invariant
```
INV-S1: ∀ edge nodes e, ∀ times t:
  staleness(e, t) := wall_now(e, t) − commit_ts(last_applied_notification(e, t))
  where commit_ts is the leader-assigned commit-notification timestamp (ADR-0035).

INV-S2: Under normal network conditions (no partition):
  P(staleness(e) > 500ms) < 0.01 (p99)
  P(staleness(e) > 2s) < 0.0001 (p9999)
```

---

## 3. Monotonic Read Guarantee

### Guarantee
Once a client reads a value at version V from any edge node, all subsequent reads by the same client will return values at version >= V.

### Mechanism
1. Every read response includes a `VersionCursor(version, timestamp)`.
2. Client stores the cursor and passes it on subsequent reads.
3. Edge node receiving a read with cursor checks: `local_version >= cursor.version`.
   - If yes: serve the read, return updated cursor.
   - If no: the current implementation (`LocalConfigStore.get(key, cursor)`) returns `NOT_FOUND` **immediately** (and increments `invariant.violation.monotonic_read` via `InvariantMonitor`), signalling the client to retry or fail over to another edge node. It does **not** block for `monotonic_read_timeout` and does **not** serve stale data on a cursor-behind read — refusing to violate monotonicity is the safe behavior. (CM-017/CM-041: the earlier "block up to 100ms then serve stale" mechanism is **not implemented**; the blocking-catch-up variant is a possible future enhancement, not current behavior.)

### Edge Failover
When a client reconnects to a different edge node after failover:
1. Client passes its last `VersionCursor` in the connection handshake.
2. New edge node checks if its local version >= cursor.
3. If behind: waits briefly for catch-up (same timeout as above).
4. If still behind: serves stale with notification. Client can retry or accept.

### Formal Invariant
```
INV-M1: ∀ client c, ∀ reads r1, r2 by c where r1 happens-before r2:
  version(response(r2)) >= version(response(r1))
  
INV-M2: ∀ client c, ∀ key k, ∀ reads r1, r2 of k by c where r1 happens-before r2:
  if value(r1) was written at version V, then value(r2) was written at version >= V
```

---

## 4. Version Semantics — Global Monotonic Sequence Number

### Choice: Global monotonic sequence number per Raft group

**Justification (ADR-0004):**

| Criterion | Global Monotonic | Per-Key Version | Vector Clocks |
|---|---|---|---|
| Gap detection | Trivial (seq+1) | Requires per-key tracking | Complex (vector comparison) |
| Memory at edge (10^9 keys) | 8 bytes total | 8 GB (8 bytes/key) | 8+ GB |
| Cross-key ordering | Within group: total | None | Causal only |
| Implementation complexity | Low | Medium | High |
| Fit for single-writer model | Perfect | Adequate | Overkill |

> The "Cross-key ordering" comparison contemplated an HLC for approximate cross-group ordering; that column is **descoped under ADR-0030** (single Raft group → no cross-group order to approximate). See §5.3.

### Semantics
- Each Raft group maintains an independent, monotonically increasing 64-bit **applied-mutation sequence** counter S. Every committed entry that **mutates the config state machine** (PUT/DELETE/BATCH apply) receives `S = previous_S + 1`. Non-mutating committed entries (leader no-ops and configuration-change RCFG entries) **do not consume a sequence number** and are skipped by the counter. S is the value returned to a client on a confirmed write (ADR-0033) and is the version carried in the read cursor (§3/§6). It is gap-free **over the mutation stream**, not over raw log indices.
- Edge nodes track `last_applied_seq` per subscribed Raft group.
- Gap detection: `received_seq == last_applied_seq + 1` → apply; `> last_applied_seq + 1` → gap, enter catch-up.

> The earlier "every committed entry receives seq=prev+1" wording and the per-entry HLC timestamp are **descoped** (ADR-0035, RR-015): per-entry HLC was never implemented (`LogEntry` has no timestamp field) and its two uses are obviated — cross-group approximate ordering is moot under the single-group topology (ADR-0030), and edge staleness uses leader-assigned commit timestamps (§2). The sequence is the applied-mutation counter (ADR-0033), gap-free over mutating applies. ADR-0004 is **amended, not superseded** by ADR-0035.

### Overflow Analysis
At 10K writes/s: 64-bit counter overflows in 2^63 / 10,000 / 86,400 / 365 ≈ 29 billion years.

### Formal Invariant
```
INV-V1: ∀ Raft group g, ∀ mutating committed entries e1, e2 in g:
  if e1 committed before e2, then S(e1) < S(e2)

INV-V2: ∀ Raft group g, ∀ consecutive mutating committed entries e_i, e_{i+1} in g
  (adjacent in the applied-mutation stream, skipping no-op/RCFG entries):
  S(e_{i+1}) = S(e_i) + 1
```

---

## 5. Write Ordering

### Per-Key Total Order: REQUIRED
All writes to the same key are totally ordered. Guaranteed by Raft: single leader serializes all writes within a group. If write W1 to key K is committed before write W2 to key K, then W1's sequence number < W2's.

### Cross-Key Order Within Same Raft Group: GUARANTEED
All writes within the same Raft group share a single log, so they are totally ordered.

### Cross-Key Order Across Raft Groups: N/A under the single-group topology (ADR-0030)
Configd runs a single region-local Raft group (`DEFAULT_RAFT_GROUP = 0`; ADR-0030), so there is no cross-group order to specify. If a future multi-group deployment is adopted, a cross-group ordering mechanism is re-specified at that time (the descoped per-entry HLC was never a real mechanism — see §4/ADR-0035). Applications needing two keys ordered must route both to the same Raft group (same scope).

### Formal Invariant
```
INV-W1: ∀ key k, ∀ writes w1, w2 to k where w1 committed before w2:
  seq(w1) < seq(w2)
  
INV-W2: ∀ Raft group g, ∀ mutating writes w1, w2 in g where w1 committed before w2:
  S(w1) < S(w2)
  (the hlc(w1) < hlc(w2) conjunct is removed — per-entry HLC is descoped, ADR-0035)
```

---

## 6. Read-Your-Writes

### Scope: Same client, same region — GUARANTEED
After a client writes key K and receives acknowledgment with commit sequence S:
1. Client sets its `VersionCursor.version = S` (the ack sentence above — the commit sequence S returned by a confirmed write, ADR-0033 — is unchanged).
2. Subsequent reads from any edge node in the same region will see version >= S.
3. Intra-region propagation (Plumtree) typically completes in < 50ms.
4. If the edge node hasn't received version S yet: the cursor read returns `NOT_FOUND` immediately (see §3 — no blocking `ryw_timeout` catch-up is implemented today; the client retries or fails over). The blocking-wait fallback is a future enhancement, not current behavior.

### Cross-Region: NOT GUARANTEED (without opt-in)
Cross-region propagation takes 50-250ms. If client reads from a different region immediately after writing, the edge node may not have the update yet. Client can:
1. Pass `VersionCursor` cross-region and accept brief blocking.
2. Use control plane `ReadIndex` for guaranteed freshness (higher latency).

### Global Read-Your-Writes: Available via ReadIndex
Client sends read to control plane with `min_version = S`. Control plane performs ReadIndex on the appropriate Raft group, returning only after applying through version S.

### Formal Invariant
```
INV-RYW1: ∀ client c, ∀ write w by c that commits at seq S,
  ∀ subsequent reads r by c with cursor.version >= S,
  in the same region:
    version(response(r)) >= S (within ryw_timeout)
```

---

## 7. Property Test Mapping

Every invariant maps to a property test in `testkit/`:

| Invariant | Test Name | Description | Implementation |
|---|---|---|---|
| INV-L1 | `configd-linz` harness + Porcupine checker (ADR-0032) | Drive a real separate-JVM cluster (shaded `configd-server` over the real `TcpRaftTransport`) under OS-level faults (`iptables --dport REJECT` + `kill -9`), record a per-key checker-neutral op-history (`ack≠commit` ⇒ writes float/confirm-bound; ADR-0033 ⇒ 200 `Committed` = `:ok`), and check each key as an independent linearizable register with the trusted `anishathalye/porcupine` checker — NOT a hand-rolled "Wing & Gong" checker. | Real binary: `configd-linz/runner/HarnessMain` → `configd-linz/src/main/go/porcupine-check`; gates: self-test `CheckerSelfTest` (`PORCUPINE_BIN`-gated, `gates/gate-1.sh` step b), discrimination `scripts/run-discrimination.sh`, `scripts/run-gate.sh`. Replayable complement: the deterministic-sim history source (configd-testkit `AdversarialSim` + `HistoryRecorder`, emitted by `OpHistoryTest`) producing the identical checker-neutral op-history (`docs/session-2/adversarial-sim-design.md §6`), checked through the same Porcupine binary by `configd-linz`'s `SimHistoryCheck`. |
| INV-S1/S2 | `StalenessUpperBoundTest` | Verify the staleness state machine transitions correctly (CURRENT→STALE→DEGRADED→DISCONNECTED at the 500ms/5s/30s thresholds, reset on update) | Today asserts **threshold transitions** against the proxy idle-time clock (CM-049), NOT a p99 staleness distribution. The p99-distribution test (INV-S2) is **owed to Session 3** — it requires the leader-assigned commit-timestamp clock (ADR-0035 §2); only then can the distribution be measured against real data age. |
| INV-M1 | `MonotonicReadTest` | Verify version never decreases for a client session | Single client reads repeatedly during concurrent writes; assert version cursor monotonically increases |
| INV-M2 | `MonotonicReadFailoverTest` | Verify monotonic reads survive edge failover | Client reads from edge A, failover to edge B with cursor; assert reads from B >= cursor |
| INV-V1 | `SequenceMonotonicityTest` | Verify sequence numbers are strictly increasing | Apply many writes; verify each committed entry has seq = prev + 1 |
| INV-V2 | `SequenceGapFreeTest` | Verify no gaps in committed sequence | Check every consecutive pair of committed entries |
| INV-W1 | `PerKeyTotalOrderTest` | Verify writes to same key are totally ordered | Concurrent writes to same key; verify all replicas see same order |
| INV-W2 | `IntraGroupOrderTest` | Verify all writes in a group share total order | Multiple keys in same group; verify sequence ordering |
| INV-RYW1 | `ReadYourWritesTest` | Verify client sees own writes | Write then read in same region; assert read returns written value |

---

## 8. Runtime Assertions

Every formal invariant has a runtime assertion:

| Invariant | Assertion Name | Location | Check | On Violation |
|---|---|---|---|---|
| INV-V5 (apply) | `version_monotonicity` | Raft apply path (`RaftNode.applyCommitted`) | applied `entry.index() > lastApplied` | Metric `invariant.violation.version_monotonicity` + CRITICAL log |
| INV-M1 | `monotonic_read` | Edge read path (`LocalConfigStore.get(key, cursor)`) | `store_version >= cursor_version` | Metric `invariant.violation.monotonic_read` + WARN log |
| INV-W1 | `per_key_order` | Config state machine | version of new write > version of existing value for the same key | Metric `invariant.violation.per_key_order` + CRITICAL log |
| INV-S1 | `assert_staleness_bound` | `StalenessTracker` | `staleness_ms < threshold` | State transition (CURRENT→STALE→DEGRADED→DISCONNECTED) |
| INV-L1 | `state_machine_safety` / leader-completeness | Raft apply / election | applied entry index matches; new leader's log contains all committed entries | Metric + CRITICAL log (should never fire if Raft is correct) |
| **RR-003** | `durable_prefix_no_gap` | Raft recovery + apply (`RaftNode` ctor `:257`, `applyCommitted` `:1655`) | a committed index below a snapshot boundary has restorable bytes (no silent skip of committed state) | Metric `invariant.violation.durable_prefix_no_gap` + SEVERE log; refuses to advance `lastApplied` past the gap |
| **RR-029 / W-1** | `apply_owner_thread` | Config state-machine apply (`ConfigStateMachine.apply` `:280`) | every `apply` runs on the owner thread bound on first apply (single-writer tripwire) | Metric `invariant.violation.apply_owner_thread` (+ `StateMachineMetrics.onApplyOwnerThreadViolation`) + throw in test/sim |
| INV-RI-3 (ReadIndexSpec) | `read_freshness` | ReadIndex serve (`RaftNode.assertReadServeInvariants`) | served `readIndex <= lastApplied` — a read never served ahead of applied state | Metric `invariant.violation.read_freshness` + SEVERE log; throw in test/sim |
| INV-RI-4 (ReadIndexSpec) | `no_stale_leader_serve` | ReadIndex serve (`RaftNode.assertReadServeInvariants`) | node still LEADER and the read's recorded term `<= currentTerm` (no stepped-down/stale serve) | Metric `invariant.violation.no_stale_leader_serve` + SEVERE log; throw in test/sim |
| INV-RI-2 (ReadIndexSpec) | `read_index_bounded` | ReadIndex serve (`RaftNode.assertReadServeInvariants`) | served `readIndex <= commitIndex` (never beyond committed) | Metric `invariant.violation.read_index_bounded` + SEVERE log; throw in test/sim |
| INV-SI-1 (SnapshotInstallSpec) | `snapshot_bounded` | Local snapshot (`RaftNode.triggerSnapshot`) | snapshot `index <= commitIndex` — never snapshot ahead of committed state | Metric `invariant.violation.snapshot_bounded` + SEVERE log; throw in test/sim |
| INV-SI-2 (SnapshotInstallSpec) | `snapshot_matching` | InstallSnapshot receive (`RaftNode.checkSnapshotInstallTwins`) | incoming `lastIncludedTerm` agrees with the term locally recorded at that index | Metric `invariant.violation.snapshot_matching` + SEVERE log; throw in test/sim |
| INV-SI-3 (SnapshotInstallSpec) | `snapshot_no_commit_revert` | InstallSnapshot receive (`RaftNode.checkSnapshotInstallTwins`) | a higher-index install does not carry a lower term than the current snapshot (no commit revert) | Metric `invariant.violation.snapshot_no_commit_revert` + SEVERE log; throw in test/sim |
| INV-SI-4 (SnapshotInstallSpec) | `snapshot_term_consistent` | InstallSnapshot send (`RaftNode.checkSnapshotSendTwin`) | outbound `(lastIncludedIndex, lastIncludedTerm)` matches the term this node records at that index (ships only a snapshot it holds) | Metric `invariant.violation.snapshot_term_consistent` + SEVERE log; throw in test/sim |

All seven ReadIndexSpec/SnapshotInstallSpec twins above were built this session (RR-030) and are
observed firing by `AssertionTwinFiringTest` (consensus-core), satisfying §4.5 (an assertion never
observed firing is unverified). See `docs/session-2/assertion-verification.md` for the full matrix and
per-twin firing mechanism.

The previously-listed `assert_sequence_monotonic` / `assert_sequence_gap_free` rows are **removed**: those assertions were deleted from the code in A2 (the `new_seq > last_applied_seq` form was locally vacuous; `ConfigStateMachine` no longer carries them — CM-052). The real, non-vacuous successor on the apply path is `version_monotonicity` (asserts the *log-supplied* `entry.index()` strictly exceeds `lastApplied`, which a stale/wrong log entry trips). The sequence-gap guarantee itself is INV-V2 over the **mutation stream** (§4); it is structurally enforced by the single-writer apply, not a standalone assertion.

**Production behavior:** Assertions NEVER crash the process. They increment a metric counter (`invariant.violation.<name>`, via `InvariantMonitor` with `testMode=false`) and emit a structured log entry. Alerting is configured on the metric. In test/sim mode, assertions throw **`AssertionError`** for immediate failure (CM-058 — the contract previously named a non-existent `InvariantViolationException`).

---

## 9. Summary of Guarantees

| Property | Scope | Guarantee | Mechanism |
|---|---|---|---|
| Write linearizability | Per Raft group | Full | Raft consensus |
| Read linearizability | Control plane | On request (ReadIndex) | Raft ReadIndex |
| **GLOBAL/security strong reads** | Configured strong-read key class (default `secure/`) | **Always linearizable, fail-closed** — never served stale; 503 + `X-Fail-Closed`/`X-Leader-Hint` when the linearizable read cannot be confirmed (ADR-0030 INV-1, RR-020) | Forced ReadIndex with no stale fallback (`HttpApiServer` GET) |
| Edge read consistency | Per edge node | Bounded staleness (< 500ms p99) | Plumtree push + staleness tracking |
| Monotonic reads | Per client session | Guaranteed | Version cursor |
| Read-your-writes | Same region | Guaranteed via cursor (cursor-behind read returns NOT_FOUND → retry/failover; no blocking-wait fallback today, §3/§6) | Version cursor + intra-region Plumtree |
| Read-your-writes | Cross-region | Opt-in (cursor or ReadIndex) | Explicit client action |
| Per-key total order | All replicas | Guaranteed | Raft single-leader serialization |
| Cross-key order (same group) | All replicas | Guaranteed | Shared Raft log |
| Cross-key order (cross group) | N/A | N/A under ADR-0030 single-group topology | — (per-entry HLC descoped, ADR-0035) |
| Version monotonicity | All nodes | Guaranteed | Monotonic sequence numbers |
