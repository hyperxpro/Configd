# ADR-0004: Global Monotonic Sequence Numbers for Version Semantics

## Status
Accepted

## Context
The system needs a versioning scheme for config entries that supports: gap detection at edge nodes, monotonic read enforcement, total ordering within a key, and efficient fan-out subscription. Three options were analyzed: global monotonic sequence number, per-key version, and vector clocks.

## Decision
Each Raft group maintains its own **monotonic sequence number**. Every committed log entry receives the next sequence number in the group's sequence. Entries also carry an **HLC (Hybrid Logical Clock) timestamp** for cross-group ordering when needed.

Edge nodes track `last_applied_seq` per subscribed Raft group. Gap detection is trivial: if `received_seq != last_applied_seq + 1`, a gap exists.

## Influenced by
- **Cloudflare Quicksilver:** Monotonic sequence numbers with "exactly one higher than the last" gap detection. Incremental hashing for corruption detection.
- **Kafka:** Consumer offsets as monotonic positions in a log. Log compaction preserves offset stability.
- **CockroachDB HLC:** Hybrid Logical Clocks for cross-node ordering without TrueTime hardware.

## Reasoning
### Option 1: Global monotonic sequence number (CHOSEN)
- **Pro:** Simple. One comparison determines ordering. Gap detection trivial. Fits single-source-of-truth model. No per-key overhead (critical for 10^9 key ceiling). Fan-out subscription is position-based (like Kafka consumer groups).
- **Con:** Sequence is per Raft group, not globally unique across groups. Cross-group ordering requires HLC fallback.
- **Impact on §0.1 targets:** At 10K writes/s, sequence numbers increment at 10K/s — 64-bit counter won't overflow for ~58 million years.

### Option 2: Per-key version
- **Pro:** More granular. Enables per-key conflict detection.
- **Con:** Requires per-key metadata tracking at every edge node. With 10^9 keys, version metadata alone = ~8 GB (8 bytes per key). Cannot detect cross-key gaps. Complicates subscription fan-out (must track position per key, not per stream).
- **Rejected:** Memory overhead at 10^9 keys is prohibitive. Gap detection requires per-key state machine.

### Option 3: Vector clocks / version vectors
- **Pro:** Handles concurrent multi-writer scenarios. Detects causal ordering across writers.
- **Con:** Unnecessary complexity for single-writer-per-key model. Our control plane is the sole authoritative writer. Vector clocks solve multi-writer conflicts; we have none. Size scales with number of writers, which is bounded but adds overhead.
- **Rejected:** Solving a problem we don't have. Adds per-entry metadata proportional to writer count.

## Consequences
- **Positive:** Minimal metadata per entry (8-byte sequence + 8-byte HLC = 16 bytes). Trivial gap detection. Position-based subscription. No per-key state at edge.
- **Negative:** Cross-group ordering is approximate (HLC, not exact). Applications needing cross-group causality must use HLC timestamps with bounded uncertainty.
- **Risks and mitigations:** Sequence number per group means a multi-group deployment has multiple independent sequences. Mitigated by HLC for cross-group ordering and by the consistency contract explicitly disclaiming cross-group total order.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-systems-researcher: ✅
- formal-methods-engineer: ✅
