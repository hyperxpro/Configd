# Wire-protocol fuzz corpus (S7 security, charter §6)

Seed/regression inputs for the Configd wire-protocol fuzzers
(`FrameCodecFuzzTest`, `EdgeFrameCodecFuzzTest`). Each entry is a documented
adversarial input class or a concrete regression case surfaced during fuzzing.

## Format and rationale

Seeds are stored as **human-readable hex** in `frame-codec-seeds.txt`, one
`name = hex` per line, NOT opaque binary `.bin` files. This deliberately mirrors
the project's golden-fixture convention
(`configd-transport/.../wirecompat/GoldenFixtures.java`): a hex diff is
reviewable, and a byte change shows up in code review rather than as a silent
binary blob churn.

The fuzz tests are **self-seeding** — every `@Property` pins a fixed
`seed = "..."`, so jqwik regenerates the exact adversarial inputs each run and
also auto-persists any newly-failing seed under
`.jqwik-database` (jqwik's own store). This file is the *curated, durable*
corpus: the canonical regression inputs and one representative of every
adversarial class, so a future reader can replay the security-relevant inputs
without re-deriving them from the property generators.

## Replay

The corpus is exercised by the fuzz tests directly (the `@Property` generators
cover every class below, with the pinned seeds reproducing them). To replay the
whole bounded fuzz lane:

```
./mvnw -o -pl configd-transport test -Dtest=FrameCodecFuzzTest
./mvnw -o -pl configd-distribution-service test -Dtest=EdgeFrameCodecFuzzTest
```

To feed a single hex seed by hand against the decoder, decode the hex to bytes
and call `FrameCodec.decode(bytes)` — the oracle is: it returns a `Frame` or
throws one of `IllegalArgumentException` / `UnsupportedWireVersionException` /
`BufferUnderflowException`, and never `OutOfMemoryError` / `NullPointerException`
/ `ArrayIndexOutOfBoundsException` / `NegativeArraySizeException` / a hang.

## Enforced ceilings (so resource-bound claims are concrete)

- Raft `FrameCodec.MAX_FRAME_SIZE` = **16 MiB** (16777216). Header = 18 B,
  trailer (CRC32C) = 4 B, minimum frame = 22 B.
- Edge `EdgeFrameCodec.MAX_EDGE_FRAME_SIZE` = **2 MiB**; snapshot chunk cap
  = 1 MiB; NOTIFY batch caps = 64 notifications / 256 KiB.
- `RaftMessageCodec`: MAX_ENTRIES_PER_APPEND = 10000, MAX_COMMAND_LEN = 1 MiB,
  MAX_SNAPSHOT_BLOB_LEN = 4 MiB.
- `CommandCodec.MAX_VALUE_SIZE` = 1 MiB (the **per-config-value** ceiling).

## Charter "1 MB hard ceiling" vs. 16 MiB FrameCodec cap (FLAG for lead)

Charter §0.1/§6 references a "1 MB hard ceiling", but `FrameCodec.MAX_FRAME_SIZE`
is **16 MiB**. There is no single 1 MB frame cap; instead there is a *layered*
set of enforced ceilings (all verified by tests, all reject-before-allocation):

| Layer | Constant | Value | What it bounds |
|-------|----------|-------|----------------|
| Raft frame | `FrameCodec.MAX_FRAME_SIZE` | 16 MiB | any single Raft wire frame |
| Per-config value | `CommandCodec.MAX_VALUE_SIZE` | 1 MiB | one config value in a PUT |
| Per LogEntry cmd | `RaftMessageCodec.MAX_COMMAND_LEN` | 1 MiB | one committed command blob |
| Snapshot blob | `RaftMessageCodec.MAX_SNAPSHOT_BLOB_LEN` | 4 MiB | InstallSnapshot data/config |
| Edge frame | `EdgeFrameCodec.MAX_EDGE_FRAME_SIZE` | 2 MiB | any single edge wire frame |
| Edge snap chunk | `EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES` | 1 MiB | one snapshot chunk |

The **real per-config-value ceiling is 1 MiB** (`CommandCodec`), which is most
likely what the charter's "1 MB" referred to. The 16 MiB frame cap exists to fit
a worst-case InstallSnapshot (two 4 MiB blobs + headers). This is NOT a defect —
but the charter wording is misleading. **Recommendation (lead to decide):** either
(a) correct the charter/docs to say "1 MiB per config value; 16 MiB per Raft
frame (snapshot envelope)", or (b) if a true 1 MiB frame ceiling is intended for
the steady-state Raft path, lower `MAX_FRAME_SIZE` and chunk InstallSnapshot more
aggressively. Do not change the constant without lead sign-off (it is golden-fixture
and wire-compat gated).

## Slow-drip (slowloris) + connection flood (FLAG for S7.5)

`TcpRaftTransport` inbound readers set **no read deadline** on accepted sockets
(RR-002 cleared it for steady state on the client path; the server path never sets
one), use an **unbounded** `newVirtualThreadPerTaskExecutor()`, and keep an
**unbounded** `acceptedSockets` set. A peer that stalls mid-handshake or drips
bytes holds a reader + socket FD indefinitely; a flood of such connections
exhausts file descriptors → `accept()` fails → legitimate peers locked out.
Mechanism is pinned deterministically in `InboundReadDeadlineFuzzTest`.
**Recommendation:** add an inbound idle/read deadline (`setSoTimeout`) and a cap
on concurrent inbound connections. The end-to-end mTLS slow-drip reproduction and
the connection-flood scale test are integration-scale and flagged for S7.5.
