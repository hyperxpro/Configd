# Idiomatic-Java Quality Pass — §2 No-Touch Proposals

This directory collects the **review-only proposals** produced by the conservative idiomatic-Java
quality pass (one `java-distinguished-engineer` per module, each change gated by a divergence-analyst +
code-reviewer). The pass itself landed only **strict, behavior-preserving** idiomatic/GC fixes (~17
lines across 7 modules; the other 8 modules were already clean).

The items below were **identified but NOT applied** — they live in §2 no-touch zones (measured hot
paths, consensus, ACL decision types, wire codecs) or otherwise need validation. They are recorded
here for the **EC2 measurement session** and deliberate follow-ups. None of them is part of the
byte-identical pass.

## EC2-actionable allocation hypotheses (need the allocation/gc oracle to bless)
- **edge-cache** — `PrefixStorageFilter:74` `subscriptions.prefixes().isEmpty()` allocates a throwaway
  `LinkedHashSet` per `filter()` on the delta-apply path; the lock-free `subscriptions.isEmpty()` is
  the allocation-free equivalent. Lowers a measured §2 alloc profile. (`configd-edge-cache-proposals.md` P1)
- **edge-node** — `EdgeReadHandler` hoists constant response bodies (`"Not Found"`, `{"live":true}`…)
  to `static final byte[]` to drop per-request UTF-8 encoding; would re-baseline the ~1716 B/req read
  floor (note the shared-mutable-array caveat). (`configd-edge-node-proposals.md` P2)
- **server** — `EdgeFrameToByteEncoder`/`ByteBufFrameSink` allocate a fresh sink holder per outbound
  encode (~240 B/op over the 25,520 B floor); propose a per-connection reusable mutable sink. Needs
  `FanOutEncodeIntoBenchmark -prof gc`. (`configd-server-proposals.md`)
- **replication-engine** — `SnapshotTransfer:303` presize the reassembly `ByteArrayOutputStream`.
  (`configd-replication-engine-proposals.md` P2)
- **config-store** — `DeltaComputer:42` presizable `HashSet`. (`configd-config-store-proposals.md` #4)

## Latent defect — separate, deliberate correctness PR (NOT byte-identical, do not fold into this pass)
- **config-store** — `SigningKeyStore.writeForTest` (`:170`) has a discarded
  `PosixFilePermissions.fromString("rw-------")` no-op; the intended `Files.setPosixFilePermissions`
  call is missing, so the helper never chmods `0600` (production `generateAndWrite` is correct).
  Currently **dormant** (zero callers repo-wide). (`configd-config-store-proposals.md` #1)

## Other notable observations (cosmetic / cross-module / deferred)
- **consensus-core** — `MAX_COMMAND_LEN` duplicated across `RaftNode` and `RaftMessageCodec` (server),
  kept in sync only by comment. Cross-module; a deliberate change.
- **transport** — `FrameCodec.decode` reads the CRC trailer via `ByteBuffer.wrap(...)` (allocates a
  throwaway `HeapByteBuffer`); absolute `buf.getInt(crcOffset)` is byte-identical and allocation-free.
  §2 decode path (golden test pins encode only). (`configd-transport-proposals.md` P1)
- **edge-node** — `NettyEdgeHttpServer.stop()` fire-and-forget `serverChannel.close()` before group
  shutdown leaks the listen FD **under io_uring** (opt-in; Epoll default tolerates it). And a residual
  boss/worker-constructor leak window sits just outside the bind-path `try` that was hardened in this
  pass. (`configd-edge-node-proposals.md` D1 + code-review note)
- **testkit** — 8 genuinely-unused imports across the measurement instruments; zero-bytecode but kept
  frozen until after the EC2 run, then sweep in one commit. (`configd-testkit-proposals.md` P1)
- **observability / control-plane-api / distribution-service / common / consensus-core** — assorted
  unused-import / FQN-vs-import / minor-DRY notes, all deferred as §2-file or cosmetic-only churn.

See each `configd-<module>-proposals.md` for the full per-module detail and the "deliberately left
alone / traps" sections (idioms that look free but would break byte-identity, e.g.
`IllegalArgumentException`→`NullPointerException` flips from `Objects.requireNonNull`).
