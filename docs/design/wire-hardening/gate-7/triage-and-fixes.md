# Gate 7 — review-loop triage (round 1) → fix list

Three parallel review lanes (redteam re-attack, distinguished-engineer correctness, protocol RFC/coverage)
re-attacked the whole arc. **No Critical/High.** Consolidated substantive findings + disposition:

## Code fixes (do all)
- **C1 (MED, redteam F1) — coalesced-heartbeat in-body identity bypass.** `RaftTransportAdapter.inBodyRoutingId`
  returns null for the coalesced-heartbeat path (it is decoded via `decodeCoalescedHeartbeat`, not the single-
  message `decode`), so each per-group `leaderId` is NOT bound to the authenticated `from` when the allow-list
  is enforced. A Byzantine allow-listed peer could forge another node's leaderId inside a coalesced HB. FIX:
  in the coalesced-HB dispatch path, when identity is enforced, reject the frame if ANY per-group
  `ae.leaderId() != from` (drop + count), mirroring the single-message in-body check.
- **C2 (MED, correctness F1) — WH-06 strict-end incompleteness.** Workstream D added strict-end (reject trailing
  bytes) to `decodeAppendEntries` + `decodeInstallSnapshot`, but the FIXED-SIZE decoders
  (AppendEntriesResponse, RequestVote(+PreVote), RequestVoteResponse, InstallSnapshotResponse [after its
  optional trailing field], TimeoutNow) still `checkRemaining` a lower bound but never reject SURPLUS trailing
  bytes — so WH-06's "every fixed-shape decoder" uniformity claim is overstated. FIX: add strict-end
  `hasRemaining()` rejection to those fixed-size decoders too (NOT the InstallSnapshotResponse pre-optional
  field — only after it), completing the uniformity. Low security (bounded by frame length), real consistency.
- **C3 (LOW-MED, correctness F2) — JDK FanOutServer WH-11 deadline is per-read, not absolute.** The Netty path
  (production default) uses a one-shot absolute scheduled reap (correct). The JDK `FanOutServer` arms a per-read
  soTimeout, so a slow-loris dribbling ≥1 byte per window could evade the first-frame deadline. FIX: make the
  JDK first-frame reap an ABSOLUTE deadline (track first-frame-by wall-clock; reject if the first routed frame
  has not arrived by start+firstFrameDeadlineMs regardless of dribbles). Retained-not-default, so LOW-MED.
- **C4 (LOW, redteam F2 / correctness F3) — TcpRaftTransport raw System.err in decode-failure path.** Currently
  unreachable as a flood (the adapter self-swallows+throttles), but it is the WH-10 anti-pattern. FIX: route
  the TcpRaftTransport decode-failure/handler-error prints through the rate-limited Logger for uniformity.

## Docs fixes (do all — docs-only, no code/goldens)
- **D1 (MED, protocol F1) — RFC F6-3** says NOTIFY decode does NOT enforce the 256 KiB cap; the WH-14 fix means
  it DOES. Correct F6-3.
- **D2 (LOW, protocol F2) — RFC** omits SUBSCRIBE `MAX_PREFIXES=4096`. Document it.
- **D3 (LOW, protocol F3) — RFC §3** F3-1/F3-2 have stale file:line citations (~40-110 lines off). Refresh.
- **D4 (LOW, protocol F4) — gate-3/fuzz-coverage.md** misstates the SNAPSHOT_CHUNK decode cap. Correct.

## Verified CLEAN (all three lanes, genuinely attacked)
Identity binding (Layer-1 cert→NodeId pin, Layer-2 senderId + single-message in-body, outbound-target pin,
enforce-requires-mTLS parity, LdapName parsing fail-closed, separator-only/duplicate throws) beyond C1;
poison-pill deterministic skip (no BufferUnderflow, deterministic across replicas); edge prefixCount +
snapshot caps + watch-veneer-absent; WH-07 revert safe (consensus handles stale term); byte-identity of
valid frames; coverage complete (every frame + reject path has fuzz/integration/E2E).

## Loop plan
Round 1: apply C1-C4 + D1-D4 (one fix pass, with tests for C1-C3). Re-review: redteam re-checks C1 (the
identity fix) + C2; then re-verify full reactor green. If the re-review finds nothing substantive, the loop
is clean and the arc closes.
