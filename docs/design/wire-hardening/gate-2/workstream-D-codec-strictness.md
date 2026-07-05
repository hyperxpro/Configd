# Gate 2 · Workstream D — codec strictness batch (WH-05, WH-06, WH-07, WH-10, WH-14)

One consistent scheme, one commit. Keep valid frames byte-identical (goldens:
`WireCompatGoldenBytesTest` Raft, `EdgeFrameCodecGoldenFixtureTest` edge). Modules:
configd-server (RaftMessageCodec, RaftTransportAdapter), configd-transport (FrameCodec/MessageType),
configd-distribution-service (EdgeFrameCodec).

## WH-05 — INSTALL_SNAPSHOT.offset no negative check
`RaftMessageCodec.decodeInstallSnapshot` (~:508): `int offset = buf.getInt()` passed raw to the request.
The response's `nextExpectedOffset` IS negative-checked (:569-572) — close the asymmetry: reject
`offset < 0` at decode.

## WH-06 — inconsistent trailing-byte strictness (Raft)
Only `decodeCoalescedHeartbeat` rejects trailing bytes. Add strict-end `buf.hasRemaining()` rejection to
`decodeAppendEntries` (after the last entry) and `decodeInstallSnapshot` (after configData), matching the
coalesced-heartbeat model. (Edge plane is already strict; CommandCodec was fixed in Workstream B.)
NOTE the InstallSnapshotResponse optional-trailing `nextExpectedOffset` is a DELIBERATE optional field —
do NOT make that one strict-end; only the request-side fixed-shape decoders.

## WH-07 — RPC term / groupId no range validation
`FrameCodec.decode` (~:312-313) reads `term`(i64)/`groupId`(i32) raw. Reject negative RPC `term` and
bound `groupId` to `[0, shardCount)` at the codec/demux. groupId's valid range needs the configured shard
count — do the bound where it is known (the demux/`RaftTransportAdapter`), not in FrameCodec if FrameCodec
lacks shardCount. Negative-term reject can live in FrameCodec.decode (a negative term is never valid).
CAREFUL: confirm no legitimate sentinel uses a negative term/groupId (coalesced heartbeat uses groupId=0
sentinel in the FRAME header but demuxes by type, not groupId — verify before bounding).

## WH-10 — undecodable/dormant type log-flood
Dormant types (PLUMTREE_* 0x08-0x0B, HYPARVIEW_* 0x0C-0x0D, HEARTBEAT 0x0E) hit `RaftMessageCodec.decode`
`default` throw -> per-frame `printStackTrace` in `RaftTransportAdapter` / `TcpRaftTransport:425-431`.
Fix: replace the per-frame stack-trace print with a counted, rate-limited drop (a `raft_unknown_type_drop`
metric + a rate-limited WARN). Do NOT remove the enum codes (frozen-format / golden touch) unless trivial
and golden-safe — prefer the rate-limited-drop.

## WH-14 — NOTIFY decode doesn't enforce MAX_NOTIFY_BATCH_BYTES
`EdgeFrameCodec.decodeNotify` enforces `count <= 64` + per-blob `<= remaining`, but not the 256 KiB payload
sum (encode-only, :341-345). Add the decode-side payload-bytes cap for canonical-encoding parity. Bounded
already (frame cap 2 MiB) so this is strictness, not a security fix — but do it for symmetry.

## WH-16 — aggregate buffer ceiling (document/defer decision)
`cap x max-frame` ~= 16 GiB (Raft) / 2 GiB (edge) worst case. If a global in-flight-bytes counter is cheap
(a shared atomic decremented on frame completion, rejecting new frames over a ceiling), add it; else
document the bound in the RFC (Gate 5) and defer. Decide during implementation; do not over-engineer.

Report: files, each WH item's exact check + where, any new metric (catalog parity), test names+results,
byte-identity confirmation (goldens green). Leave uncommitted.

## WH-10 addendum (found during Workstream A review)
`RaftTransportAdapter` has a PRE-EXISTING decode-failure `System.err.println` (~line 119, on the
`catch` around `RaftMessageCodec.decode`) — an unbounded per-frame log-flood vector on hostile input,
the same anti-pattern WH-10 targets. Sweep it into the WH-10 rate-limited-Logger fix (it was left as-is
by Workstream A to stay in scope).
