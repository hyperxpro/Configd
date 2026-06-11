# C5 Design Draft — New-Edge Bootstrap Under Sustained Writes

> **Status: DRAFT for review-architect screening.** Contract row CT-24; charter §4 C5.
> The smallest component by new code — the mechanism already exists end-to-end after
> C1+C2+C3; C5 is the adversarial proof that it is gapless and duplicate-free **under
> sustained concurrent writes**, judged by the V1 snapshot–delta equivalence invariant.

## 1. The charter's choose-justify-test decision: exact cutover cursor (primary), with idempotent-apply defense-in-depth

A zero-state edge SUBSCRIBEs with cursor=0 → server answers SNAPSHOT_FIRST →
`SNAPSHOT_BEGIN(seq=S)` + chunks + `SNAPSHOT_END(S)` → edge applies atomically
(`loadSnapshot`, single volatile swap) and sets cursor=S → server tails `readSince(S)`.

- **Exact cutover cursor:** S is the snapshot's applied-mutation seq (ADR-0034 §4); the
  first NOTIFY after SNAPSHOT_END contains only seq > S (the session core's
  `lastAckedSeq=S` discipline post-C1(a) bug fix #1). There is no overlap window by
  construction: writes committed during the snapshot transfer land in the ring and are
  delivered as the seq>S tail — captured by `readSince(S)` contiguity (ADR-0034's
  exactly-once-over-effect, boundary-proven by `replayThenTailObservesEveryMutationEffectExactly`).
- **Idempotent apply as defense-in-depth, not the mechanism:** `DeltaApplier`'s
  `STALE_DELTA` (seq ≤ cursor discarded) and the backward-snapshot refusal mean a
  duplicated frame (sim's dup-injecting channel; a retransmitting transport) cannot
  double-apply or regress — but the design does NOT rely on it for the happy path.
  Justification: exact-cutover puts the correctness burden on one proven seam
  (ADR-0034's cursor contract) instead of distributing it over every apply site.

## 2. What C5 adds

Tests only, plus whatever small fixes they force:

- `EdgeBootstrapUnderSustainedWritesTest` (sim): writes flowing at full sim workload
  rate; a fresh edge actor joins mid-run; assert (a) zero duplicate-application
  divergence (V1 equivalence `finalCheck` — the judge), (b) no gap (the edge's applied
  seq chain from S is contiguous), (c) the cutover NOTIFY straddle (a write committing
  exactly while SNAPSHOT_END is in flight) is covered — drive with seeds that provably
  hit the straddle (assert non-vacuity: at least one write committed during transfer).
- `EdgeBootstrapMidChurnTest` (sim): bootstrap while the CP leader is killed mid-transfer
  (snapshot source node crashes → edge resubscribes to another node with cursor=0 or the
  partial state's cursor; assert convergence + no regression).
- Live: the C6 Compose scenario's "bootstrap a fresh edge mid-load" step (C5 supplies the
  assertion script: cursor continuity + final byte-equality vs a control-plane dump).
- Sim dup-channel coverage: ensure the edge fault schedule's dup rate exercises duplicated
  SNAPSHOT/NOTIFY frames across the cutover (report the observed dup count, non-vacuous).

## 3. Explicit non-goals

No incremental/resumable snapshot transfer beyond C1's chunking+per-frame CRC (resume =
reconnect + fresh SNAPSHOT_FIRST; acceptable at config-store sizes — a future session
prices resumption if snapshot sizes grow); no parallel multi-source bootstrap.
