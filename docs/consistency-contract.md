# Consistency Contract — Configd

> **Phase 2 deliverable.** Every guarantee is a testable invariant.
> Every invariant has both a TLA+ formalization and a runtime assertion.

---

## 1. Linearizability Scope

### Linearizable Operations
All write operations to any Raft group (global or regional) are **linearizable**:
- `PUT(key, value)` — config creation/update
- `DELETE(key)` — config removal
- `BATCH(mutations[])` — atomic batch of puts/deletes within a single Raft group

Linearizable reads are available via **ReadIndex** on the control plane:
- Client sends read request to Raft leader
- Leader records current commit index, sends heartbeat to confirm leadership
- Once confirmed, leader waits for local state machine to apply through recorded index
- Returns result — guaranteed to reflect all writes committed before the read began

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
Staleness at an edge node = `current_wall_time - timestamp_of_last_applied_entry`

Each Raft log entry carries an HLC timestamp. The edge node's `StalenessTracker` computes the difference between the current wall clock and the timestamp of the most recently applied entry.

### Behavior on Violation

| Staleness | State | Behavior |
|---|---|---|
| < 500ms | `CURRENT` | Normal operation |
| 500ms - 5s | `STALE` | Set `X-Configd-Stale: true` header on all read responses. Increment `configd.edge.staleness_violation_total` counter. |
| 5s - 30s | `DEGRADED` | Above + emit alert. Edge node reports unhealthy to load balancer. Continue serving stale data. |
| > 30s | `DISCONNECTED` | Above + trigger re-bootstrap sequence. Attempt snapshot catch-up from regional relay. |

### Formal Invariant
```
INV-S1: ∀ edge nodes e, ∀ times t:
  staleness(e, t) is defined as wall_time(t) - timestamp(last_applied(e, t))
  
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
   - If no: block for up to `monotonic_read_timeout` (default: 100ms) waiting for local version to catch up.
   - If timeout: serve stale data with `X-Configd-Stale: true` header.

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

### Semantics
- Each Raft group maintains an independent, monotonically increasing 64-bit sequence counter.
- Every committed log entry receives `seq = previous_seq + 1` (gap-free within a group).
- Each entry also carries an HLC timestamp for cross-group approximate ordering.
- Edge nodes track `last_applied_seq` per subscribed Raft group.
- Gap detection: `received_seq == last_applied_seq + 1` → apply; `> last_applied_seq + 1` → gap, enter catch-up.

### Overflow Analysis
At 10K writes/s: 64-bit counter overflows in 2^63 / 10,000 / 86,400 / 365 ≈ 29 billion years.

### Formal Invariant
```
INV-V1: ∀ Raft group g, ∀ committed entries e1, e2 in g:
  if e1 committed before e2, then seq(e1) < seq(e2)
  
INV-V2: ∀ Raft group g, ∀ consecutive committed entries e_i, e_{i+1} in g:
  seq(e_{i+1}) = seq(e_i) + 1
```

---

## 5. Write Ordering

### Per-Key Total Order: REQUIRED
All writes to the same key are totally ordered. Guaranteed by Raft: single leader serializes all writes within a group. If write W1 to key K is committed before write W2 to key K, then W1's sequence number < W2's.

### Cross-Key Order Within Same Raft Group: GUARANTEED
All writes within the same Raft group share a single log, so they are totally ordered.

### Cross-Key Order Across Raft Groups: NOT GUARANTEED
Writes to keys in different Raft groups have independent sequence numbers. Cross-group ordering is approximate via HLC timestamps. Applications needing cross-group causality must:
1. Use HLC timestamps and accept bounded uncertainty, OR
2. Route both keys to the same Raft group (by assigning the same scope).

### Formal Invariant
```
INV-W1: ∀ key k, ∀ writes w1, w2 to k where w1 committed before w2:
  seq(w1) < seq(w2)
  
INV-W2: ∀ Raft group g, ∀ writes w1, w2 in g where w1 committed before w2:
  seq(w1) < seq(w2) AND hlc(w1) < hlc(w2)
```

---

## 6. Read-Your-Writes

### Scope: Same client, same region — GUARANTEED
After a client writes key K and receives acknowledgment with commit sequence S:
1. Client sets its `VersionCursor.version = S`.
2. Subsequent reads from any edge node in the same region will see version >= S.
3. Intra-region propagation (Plumtree) typically completes in < 50ms.
4. If edge node hasn't received version S yet: blocks for `ryw_timeout` (default: 100ms), then falls back to stale-allowed.

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
| INV-L1 | `LinearizabilityTest` | Verify linearizable writes via concurrent client operations and linearizability checker | Run concurrent writes + reads against simulated cluster; verify history is linearizable using Wing & Gong algorithm |
| INV-S1/S2 | `StalenessUpperBoundTest` | Verify edge staleness stays within bounds | Simulate cluster with varying network latency; measure staleness distribution; assert p99 < 500ms |
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
| INV-V1 | `assert_sequence_monotonic` | Raft apply thread | `new_seq > last_applied_seq` | Metric: `configd.invariant.violation{name="sequence_monotonic"}` + CRITICAL log |
| INV-V2 | `assert_sequence_gap_free` | Raft apply thread | `new_seq == last_applied_seq + 1` | Metric + CRITICAL log + halt apply (data corruption) |
| INV-M1 | `assert_monotonic_read` | Edge read path | `response_version >= cursor_version` | Metric: `configd.invariant.violation{name="monotonic_read"}` + WARN log |
| INV-W1 | `assert_per_key_order` | Config state machine | Version of new write > version of existing value for same key | Metric + CRITICAL log |
| INV-S1 | `assert_staleness_bound` | StalenessTracker | `staleness_ms < threshold` | State transition (CURRENT→STALE→DEGRADED→DISCONNECTED) |
| INV-L1 | `assert_leader_completeness` | Raft election | New leader's log contains all committed entries | Metric + CRITICAL log (should never fire if Raft is correct) |

**Production behavior:** Assertions NEVER crash the process. They increment a metric counter and emit a structured log entry at CRITICAL level. Alerting is configured on the metric. In test mode, assertions throw `InvariantViolationException` for immediate failure.

---

## 9. Summary of Guarantees

| Property | Scope | Guarantee | Mechanism |
|---|---|---|---|
| Write linearizability | Per Raft group | Full | Raft consensus |
| Read linearizability | Control plane | On request (ReadIndex) | Raft ReadIndex |
| Edge read consistency | Per edge node | Bounded staleness (< 500ms p99) | Plumtree push + staleness tracking |
| Monotonic reads | Per client session | Guaranteed | Version cursor |
| Read-your-writes | Same region | Guaranteed (with timeout fallback) | Version cursor + intra-region Plumtree |
| Read-your-writes | Cross-region | Opt-in (cursor or ReadIndex) | Explicit client action |
| Per-key total order | All replicas | Guaranteed | Raft single-leader serialization |
| Cross-key order (same group) | All replicas | Guaranteed | Shared Raft log |
| Cross-key order (cross group) | N/A | Not guaranteed | HLC for approximate ordering |
| Version monotonicity | All nodes | Guaranteed | Monotonic sequence numbers |
