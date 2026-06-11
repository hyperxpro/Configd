# C3 Design Draft — Catch-up, Replay, Gap Detection (+ poison-pill / negative-cache decisions)

> **Status: DRAFT for review-architect screening.** Contract rows: CT-06, CT-13, CT-16,
> CT-31, CT-32, CT-33; charter §4 C3. Much of C3's server half already landed inside C1
> (by design — the session core IS the replay path); C3 is the edge-side recovery
> orchestration plus two explicitly-owed implement-or-descope decisions.

## 1. What already exists (C1, tested) vs what C3 adds

Existing, with tests: server-side `readSince` Gap → demotion → chunked snapshot → resume
(`FanOutSessionCore`, `SubscriberOverflowDemotionTest`, sim sweep); `SUBSCRIBE` cursor vs
`oldestSeq()` → TAIL | SNAPSHOT_FIRST decision; edge-side chain validation
(`DeltaApplier` `fromVersion == currentVersion` → `GAP_DETECTED`, `STALE_DELTA` dedupe);
backward-snapshot refusal (C1(a) bug fix #2).

C3 adds (edge side, in `EdgeClientCore` per the C2 layering):

1. **Gap recovery orchestration = resubscribe-with-cursor.** On `GAP_DETECTED` (or
   decoder-level stream corruption), the client core emits `RECONNECT_RESUBSCRIBE(cursor)`;
   the server's existing TAIL/SNAPSHOT_FIRST decision logic (C1-tested) resolves replay vs
   re-bootstrap. **No new protocol frames** — the recovery path is the subscription path,
   so it shares all of C1's tests and the sim's invariant coverage.
2. **Replay horizon = the boundary ring's retention**, decided server-side. The
   horizon-boundary case (charter: "including the horizon-boundary case, under concurrent
   writes"): a cursor at `oldestSeq ± 1` while the ring advances — the server may decide
   TAIL and be lapped before the first `readSince`, which returns Gap → demote → snapshot
   (self-healing, already C1 logic). C3 pins this with a deterministic test
   (`ReplayHorizonBoundaryTest`: cursor exactly at, one-below, one-above the horizon,
   with a concurrent writer driving eviction between decision and first drain).
3. **DISCONNECTED → re-bootstrap trigger (CT-06):** staleness state DISCONNECTED (per the
   ADR-0039 frontier clock, C2) emits `RECONNECT_RESUBSCRIBE(current cursor)` — NOT
   cursor=0; the server decides (a truly-behind edge gets SNAPSHOT_FIRST anyway). cursor=0
   is reserved for suspected local corruption (poison-pill terminal path, below).
4. **Monotonic-read safety during recovery (CT-13 family):** during CATCHUP the store may
   be behind a client's cursor — reads keep refusing via the existing cursor check; a
   snapshot apply is atomic (single volatile snapshot swap), so reads never observe a
   partially-applied snapshot.

## 2. Poison-pill (CT-33) — implement NARROW, descope the §8 circuit breaker (ADR-0040)

Architecture §8's poison-pill story (dual-TTL, schema validation, serve-previous-known-good
circuit breaker) presumes a value-validation layer that does not exist: Configd stores
opaque bytes and never deserializes values; "fails validation" has no current meaning.
**Descope the §8 circuit breaker by ADR-0040.**

What CAN actually poison an edge, and the narrow policy C3 implements:

- A delta that repeatedly **throws during apply** (e.g., mutation decode error): skipping
  it is forbidden (chain break = silent divergence), so the recovery is **re-bootstrap via
  snapshot past the poison seq** — the snapshot carries cumulative state, so the bad delta
  is never re-applied. Policy: bounded retries (`PoisonPillDetector`, existing class,
  re-pointed at apply exceptions keyed by seq) → on quarantine: `configd.edge.poison_pill`
  metric + structured log + `RECONNECT_RESUBSCRIBE(cursor=0 force-snapshot)` → if the
  SNAPSHOT itself fails to apply, terminal fail-loud (process exits non-zero; an edge
  serving from a state it cannot advance is the lying-dashboard failure mode).
- An **invalid-signature** delta is NOT a poison pill: it is rejected fail-closed
  (F-0052), the chain halts, staleness rises, DEGRADED surfaces it — already correct.

## 3. Negative caching (CT-32) — descope (ADR-0040)

§8's negative cache (key-only index + Bloom filter) existed to distinguish "doesn't
exist" from "not subscribed" and to bound 10^9-key lookups. Under ADR-0038 the edge
stores its full subscription slice (full chain, storage filter): within the subscription
a HAMT miss IS authoritative non-existence (lock-free, zero-alloc miss path already
proven); outside the subscription the read is refused by policy with a distinct reason
(`X-Configd-Refused: not-subscribed`, 404). No correctness or measured performance need
remains; the `BloomFilter` class is retained as tested-but-unwired (deleting is S7's
orphan sweep call) and the map row flips to ADR-RENEGOTIATED(adr-0040).

## 4. Tests (written first)

`ReplayHorizonBoundaryTest` (the ±1 horizon matrix under concurrent writes);
`EdgeGapRecoveryTest` (forced GAP_DETECTED → resubscribe → convergence, sim);
`DisconnectedRebootstrapTest` (CT-06: frontier-stalled edge walks to DISCONNECTED →
resubscribe → recovers, sim, no sleeps); `PoisonPillRebootstrapTest` (apply-throwing
delta → bounded retries → metric + snapshot re-bootstrap → convergence; and the terminal
fail-loud case); `NotSubscribedReadTest` (refusal reason). All ride `EdgeClientCore`
(sim-drivable) — the sim's lossy edge channel exercises gap recovery on the 507-seed gate
automatically once the client core handles it.
