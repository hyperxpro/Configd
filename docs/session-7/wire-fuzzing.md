# Session 7 — Wire-Protocol Fuzzing: deliverable + findings (Workstream C)

> **Prime directive (charter §2/§6):** the fuzz oracle is *no crash / no OOM / no unbounded
> allocation / no hang*; malformed input is rejected cleanly with bounded resource use. The system is
> **no-Netty** (ADR-0037) — the wire protocol is the length-prefixed `FrameCodec` (+ `EdgeFrameCodec`,
> `RaftMessageCodec`, `CommandCodec`), not a Netty pipeline.

## 1. The deliverable — adversarial fuzz tests with a resource oracle

23 new `@Property` (jqwik) tests, all green, fixed-seed-deterministic, each decode wrapped in
`assertTimeoutPreemptively(2s)` with an explicit **allowed-exception set** (anything else — `OOM`,
`NPE`, `AIOOBE`, `NegativeArraySize`, hang — fails the property):

| Test class | Module | Tests | Covers |
|---|---|---|---|
| `FrameCodecFuzzTest` | configd-transport | 13 | arbitrary bytes, boundary sizes, length-lies, bad version/type/CRC, truncate-at-every-offset, trailing garbage, `peekLength` bound |
| `EdgeFrameCodecFuzzTest` | configd-distribution-service | 8 | same oracle on the edge wire (stricter: `CodecException`-only) |
| `InboundReadDeadlineFuzzTest` | configd-transport | 2 | slowloris mechanism (see F-S7-FUZZ-1) |

These **complement** the pre-existing `FrameCodecPropertyTest`/`EdgeFrameCodecPropertyTest`
(round-trip/structural) — the new dimension is arbitrary-bytes + the resource oracle + the read-loop
bounded-allocation proof.

**Bounded-allocation (the OOM lever) is proven at two layers:** the codec rejects a hostile length
before allocating, AND a faithful extraction of `TcpRaftTransport`'s read-loop gate
(`frameLength < MIN || > MAX_FRAME_SIZE` checked *before* `new byte[frameLength]`, `TcpRaftTransport.java:346-353`)
rejects `MAX_FRAME_SIZE+1` and `Integer.MAX_VALUE` length-prefixes with no multi-MB allocation.

**Corpus:** `configd-transport/src/test/resources/fuzz-corpus/` (`frame-codec-seeds.txt` + `README.md`
documenting the layered ceilings). jqwik also auto-persists any failing seed.

**Nightly fuzz lane** (wired in Seam 6): `FrameCodecFuzzTest,InboundReadDeadlineFuzzTest`
(configd-transport) + `EdgeFrameCodecFuzzTest` (configd-distribution-service) — ~30 s total on the
2-vCPU box.

## 2. Finding F-S7-FUZZ-1 (HIGH for availability) — no inbound read deadline ⇒ slowloris/FD exhaustion

**What.** Accepted inbound sockets in `TcpRaftTransport` set **no read deadline** for steady state
(RR-002 deliberately cleared the client-path `soTimeout` after the handshake; the server accept path
never sets one), the per-connection reader runs on an **unbounded** `newVirtualThreadPerTaskExecutor`,
and `acceptedSockets` is unbounded. A peer that completes the mTLS handshake then **drips bytes
slowly or sends a length prefix and stalls** holds a reader thread + socket FD **indefinitely**.
Enough such connections exhaust file descriptors → `accept()` fails → legitimate peers are locked out.
The malformed-input/allocation path is *safe* (the length gate runs before `new byte[]`); the lever
is **connection/FD count**, not a bad frame.

**Evidence.** `InboundReadDeadlineFuzzTest` pins the mechanism deterministically (no timing flake):
the default accepted-socket `soTimeout == 0` blocks indefinitely on a stalled peer, and a
`setSoTimeout` is the available mitigation. The end-to-end mTLS slow-drip repro against a live server
and the FD-exhaustion scale test are integration-scale → **S7.5 manifest**.

**Disposition: documented finding + S7.5/S8, NOT fixed this session (conservative default — D-5).**
The fix (inbound idle/read deadline + a concurrent-connection cap) modifies the **RR-002-hardened**
transport read loop and a clean **red/green** for it requires the integration-scale slow-drip repro
that is flaky on the 2-vCPU box — so applying it here risks a liveness/RR-002 regression without the
proving test the charter demands. Recommended fix for S7.5: a generous inbound read deadline (≫ the
~50 ms heartbeat interval, so live peers are never reaped) that closes a stalled connection, plus a
max-concurrent-inbound-connections bound. This is the single highest-priority availability residual
of Session 7 and is called out in the handoff + pre-S8 summary.

## 3. Finding F-S7-FUZZ-2 (doc/clarity) — the "1 MB ceiling" is layered, not a single frame cap

The charter §0.1/§6 cites a "1 MB hard ceiling". There is no single 1 MB frame cap; the ceilings are
layered and all reject-before-allocate:

| Constant | Value | Bounds |
|---|---|---|
| `CommandCodec.MAX_VALUE_SIZE` | **1 MiB** | one config value (**likely the charter's referent**) |
| `RaftMessageCodec.MAX_COMMAND_LEN` / `MAX_SNAPSHOT_BLOB_LEN` | 1 MiB / 4 MiB | log command / snapshot blob |
| `EdgeFrameCodec.MAX_EDGE_FRAME_SIZE` / chunk | 2 MiB / 1 MiB | edge frame / snapshot chunk |
| `FrameCodec.MAX_FRAME_SIZE` | **16 MiB** | one Raft frame (sized to fit a worst-case InstallSnapshot) |

Each is verified enforced by the fuzz tests. **Recommendation (lead decision, → S8):** correct the
charter/docs wording to "1 MiB per config value; 16 MiB per Raft frame", OR, if a true 1 MiB frame
ceiling is intended, lower `MAX_FRAME_SIZE` and chunk InstallSnapshot harder. `MAX_FRAME_SIZE` is
golden-fixture + wire-compat gated, so it is **not** changed without sign-off.

## 4. S7.5 manifest items (from this workstream)
1. End-to-end mTLS slow-drip reproduction + the inbound-read-deadline fix with red/green (F-S7-FUZZ-1).
2. Connection-flood / FD-exhaustion scale test (integration-scale).
3. Lead/S8 decision on the 1 MiB-vs-16 MiB ceiling wording/constant (F-S7-FUZZ-2).
