# Gate 2 · Workstream C — edge anti-exhaustion (WH-11, WH-12, WH-13, WH-15)

Edge/fan-out plane. All must keep valid frames byte-identical (golden: `EdgeFrameCodecGoldenFixtureTest`
+ V2/V3). Modules: configd-distribution-service (EdgeFrameCodec), configd-edge-cache (EdgeClientCore),
configd-server (NettyFanOutServer / FanOutServer).

## WH-11 — edge slow-loris: no post-mTLS pre-SUBSCRIBE deadline
- Site: `NettyFanOutServer.initChannel` (~:267-281) installs no idle handler; JDK `FanOutServer` does
  `ssl.setSoTimeout(0)` (~:330) after handshake.
- Nuance: a legitimate fan-out subscriber is idle BY DESIGN (server pushes; client rarely sends). A
  naive read-idle timeout would kill healthy subscribers. The correct control is a **pre-SUBSCRIBE
  handshake deadline**: once the connection is admitted (post-mTLS), it must send its first routed
  control frame (SUBSCRIBE / WATCH_CREATE) within a bounded window, else reap (close + counted metric).
  After the subscription is established, rely on the existing server->client HEARTBEAT for liveness (do
  NOT read-idle-reap an established subscriber).
- Implement on BOTH transports (Netty: a one-shot scheduled task armed on channelActive/handshake-complete,
  cancelled when the first routed frame arrives; JDK: a bounded soTimeout for the first-frame read, then
  restore 0). Config: a `firstFrameDeadlineMs` (default generous, e.g. 10s) with a `-D` override, mirroring
  `RaftWireProtocol.inboundReadTimeoutMs`. Prove with a slow-loris test (mirror `InboundReadDeadlineFuzzTest`):
  a peer that completes mTLS then sends nothing is reaped; a peer that SUBSCRIBEs then idles is NOT reaped.

## WH-12 — SUBSCRIBE prefixCount loose byte-bound (pre-auth ~8-16x amplification)
- Site: `EdgeFrameCodec.decodeSubscribe:699` — `prefixCount < 0 || prefixCount > p.remaining()` then
  `new ArrayList<>(prefixCount)`. Loose (bytes, not elems).
- Fix: replace with the tight `(long)prefixCount * 4 > p.remaining()` pre-check (min prefix = 4 bytes:
  a u32 length of a zero-length string), parity with the watch decoders (cursor/shards/changes). Add a
  `MAX_PREFIXES` cap constant (choose a generous production ceiling; a real SUBSCRIBE has a handful).
  Valid frames unchanged.

## WH-13 + WH-15 — unbounded client-side snapshot accumulation + BEGIN cross-field
- Site: `EdgeClientCore.onSnapshotBegin` (~:535) captures only `snapshotSeq`; `onSnapshotChunk` (~:541)
  does `pendingChunks.add(c)` with NO bound and ignores `SnapshotBegin.chunkCount`. Only ceiling is
  `EdgeSnapshotCodec.reassemble`'s ~2 GiB, AFTER full accumulation -> edge OOM from a malicious/compromised
  distribution server (or plaintext).
- Fix (WH-13): at `onSnapshotBegin`, capture `chunkCount` and `totalBytes`; in `onSnapshotChunk`, reject
  the (chunkCount+1)-th chunk and reject once accumulated bytes exceed `totalBytes`; add a hard absolute
  ceiling constant as backstop (both chunkCount and totalBytes are attacker-declared, so the hard ceiling
  is the real bound — e.g. a MAX_SNAPSHOT_TOTAL_BYTES). Throw the same protocol-error path
  `onSnapshotChunk` already uses (IllegalStateException -> poison/reconnect). Also cap `chunkCount` and
  `totalBytes` sanity at BEGIN.
- WH-15: this IS the cross-field validation — use BEGIN's declared values as the accumulation caps.
- VERIFY the WATCH_SNAPSHOT_CHUNK veneer path (watch snapshots) mirrors the same accumulation and apply
  the same caps there. Grep the watch snapshot reassembly (FanOutSessionCore / watch client core).
- Test: a chunk flood beyond chunkCount/totalBytes is rejected before OOM; a valid multi-chunk snapshot
  still reassembles identically.

Report: files, new constants + defaults + `-D` overrides, new metrics (catalog parity), test names+results,
byte-identity confirmation. Leave uncommitted.
