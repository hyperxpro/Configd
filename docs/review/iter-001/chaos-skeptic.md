# chaos-skeptic — iter-001
**Findings:** 16

## C-001 — No wire-frame integrity check; silent bit-flip survives transport
- **Severity:** S0
- **Location:** `configd-transport/src/main/java/io/configd/transport/FrameCodec.java:64-79` (encode), `:113-136` (decode); `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java:62-95`; harness gap in `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java`
- **Category:** corruption
- **Evidence:** `FrameCodec.encode` writes `[Length:4][Type:1][GroupId:4][Term:8][Payload:variable]` with **no CRC/MAC trailer** (FrameCodec.java:25-30). `decode()` only validates the length field and message-type code; a single bit-flip in `Term`, `GroupId`, or any payload byte (e.g. `prevLogIndex`, `entry.command`, snapshot `data`) decodes to a structurally-valid `RaftMessage` and is processed normally. TLS protects bytes in flight, but kernel buffers, NIC DMA, RAM, the JIT-compiled bytebuffer copy, and the on-disk WAL CRC live OUTSIDE the TLS boundary.
- **Impact:** Silent data loss / divergence. A flipped bit in `AppendEntries.entries[i].command` is signed by `ConfigStateMachine.signCommand` AFTER decode, so the signature is valid for the wrong payload — committed across the cluster as if intentional.
- **Fix direction:** (a) Add a 4-byte CRC32C trailer to `FrameCodec.Frame` (or use the existing WAL CRC32 idiom from `FileStorage:96-104` for symmetry). (b) Add `SimulatedNetwork.corruptEveryNthByte(n, mask)` chaos primitive and a regression test asserting that any flipped bit fails decode.
- **Proposed owner:** transport (FrameCodec) + testkit (SimulatedNetwork)

## C-002 — DurableRaftState .dat file has no checksum; silent corruption replays bogus term/votedFor
- **Severity:** S0
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/DurableRaftState.java:128-147`; `configd-common/src/main/java/io/configd/common/FileStorage.java:46-86`
- **Category:** restart
- **Evidence:** `DurableRaftState.persistValues` writes 12 bytes (`putLong(term); putInt(votedFor)`) via `storage.put(STORAGE_KEY, …)`. `FileStorage.put` writes the raw bytes via `channel.write(buf); channel.force(true)` — **no CRC, no length, no version byte** (FileStorage.java:54-70). On `load()` (line 137-147) it accepts ANY 12-byte file. A bit-flip on disk (cosmic ray, dying SSD cell, ZFS scrub miss) on the term or votedFor field cannot be detected. `WAL` traffic is CRC-protected (FileStorage.java:96-104) but the most safety-critical state is not.
- **Impact:** S0 — restart with corrupted votedFor allows a node to vote a SECOND time in the same term (Election Safety violation). Restart with corrupted term lets a node accept stale AppendEntries. Both produce silent divergence with no warning.
- **Fix direction:** Wrap `FileStorage.put`'s payload with `[len:4][crc32:4][payload]` (mirror the `appendToLog` framing) and validate on `get`. Add a chaos test that flips a random byte in the .dat file and asserts `DurableRaftState` refuses to load it.
- **Proposed owner:** common (FileStorage) + consensus-core (DurableRaftState)

## C-003 — Asymmetric-partition / bridge-node topology not representable
- **Severity:** S2
- **Location:** `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:71-89` (partition API); `spec/ConsensusSpec.tla` does not model partitions explicitly either
- **Category:** partition
- **Evidence:** Partitions are encoded as a flat `Set<Long>` of `(from,to)` pairs (line 28). `addPartition(a,b)` and `isolate(a,b)` are the only primitives. There is no concept of a "minority island that can still talk to the bridge", no half-open partition where `A→B` works but `B→A` is delayed by 30s (the classic Jepsen "asymmetric" partition that breaks lease-based leaders). `isolate` is symmetric only.
- **Impact:** Real production partitions are routinely asymmetric (NAT traversal, asymmetric routing, NIC offload bug on one side). The harness cannot exhibit a leader-stale-lease scenario where the leader still receives heartbeat ACKs but cannot send commands — a known Raft edge case.
- **Fix direction:** Document that `addPartition` is one-way (it already is) and add a `bridgeNode(NodeId pivot, Set<NodeId> islandA, Set<NodeId> islandB)` helper plus a `ChaosScenariosTest` that verifies the cluster never elects two leaders when the bridge node is the only A↔B link and is itself partitioned outbound. Add `BridgePartition` invariant scenario to `ConsensusSpec.tla` if scope allows.
- **Proposed owner:** testkit (SimulatedNetwork)

## C-004 — STW-GC freeze of 30s+ has no harness scenario covering peer impact
- **Severity:** S2
- **Location:** `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:154-163` (`freezeNode` exists); `configd-testkit/src/test/java/io/configd/testkit/ChaosScenariosTest.java:169-222` (PeerFreeze tests cover 100ms-1s, not 30s+)
- **Category:** stw-gc
- **Evidence:** `ChaosScenariosTest.PeerFreeze` exercises freeze windows of 1_000 ms (line 177) and 150 ms (line 196). No scenario freezes a peer for ≥ election-timeout × N (e.g. 30s) and asserts: (a) the cluster elects a new leader within 2× election timeout, (b) the frozen node, on thaw, does NOT win an election with stale data, (c) the frozen node's pending message backlog does not OOM the leader.
- **Impact:** ZGC pauses can hit several seconds under heap pressure (and Configd ships ZGC per ADR-0009). A 30s STW pause that crosses election timeout has no regression guard.
- **Fix direction:** Add `ChaosScenariosTest.stwGcLongerThanElectionTimeout()` using `freezeNode(N, t, 30_000L)` plus a leader-handoff assertion. Document required election-timeout-vs-max-GC relationship in `RaftConfig`.
- **Proposed owner:** testkit + consensus-core

## C-005 — Cert-rotation mid-flight: TLS reload window only drops, doesn't re-handshake
- **Severity:** S2
- **Location:** `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:171-175` (simulateTlsReload); `configd-transport/src/main/java/io/configd/transport/TlsManager.java:84-86` (`reload()` rebuilds context but ChaosScenariosTest only models drop)
- **Category:** cert-expiry
- **Evidence:** `TlsManager.reload()` swaps the volatile `currentContext` (TlsManager.java:85). `TcpRaftTransport.createClientSocket` reads `tlsManager.currentContext()` only at socket-open (TcpRaftTransport.java:285) — long-lived sockets opened with the OLD cert keep working until they fail. The ga-review.md notes S5 is YELLOW: "production rebuild not landed" (line 73 of ga-review.md). The harness `simulateTlsReload(t,d)` drops messages in `[t, t+d]` but does NOT model: (a) the cert physically expiring while a long-lived `SSLSocket` is open, (b) the new handshake failing because the old cert was renewed under a different SAN.
- **Impact:** A cert-expiry-during-replication will manifest as random connection death long after the rotation event, with no harness-level reproducer. S2 because a runbook (`ops/runbooks/cert-rotation.md`) exists but the chaos covers only the easy case.
- **Fix direction:** Add `SimulatedNetwork.expireCertOnConnection(NodeId)` that forces an `IOException` on the next message after a configurable wall-clock event. Add `ChaosScenariosTest.certExpiryMidStreamRecovers()` that asserts the connection is rebuilt with the new cert and no Raft message is permanently lost.
- **Proposed owner:** transport (TlsManager) + testkit

## C-006 — Disk-full on WAL append is not exercised; behaviour is undocumented
- **Severity:** S1
- **Location:** `configd-common/src/main/java/io/configd/common/FileStorage.java:89-114` (appendToLog); `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java` (no test); harness has no disk-full primitive
- **Category:** disk-full
- **Evidence:** `FileStorage.appendToLog` calls `channel.write(frame)` and `channel.force(true)` — both throw `IOException` on ENOSPC and are wrapped in `UncheckedIOException`. No caller in `RaftNode` or `ConfigStateMachine` catches this; the exception will propagate up the apply thread. There is no test that fills the disk and asserts the node fails-stop rather than fail-silent. `ChaosScenariosTest` has zero disk-full scenarios. ga-review.md C-series row says "Process-death / disk-full / fsync-stall chaos | RED" (line 98) — explicitly NOT covered.
- **Impact:** S1 — no harness AND no spec coverage. A leader that cannot append to its WAL but reports success to followers (because the in-memory log is updated first) would commit to followers without local durability. Need to verify code-path order; if the in-memory append happens BEFORE the WAL fsync, this is S0.
- **Fix direction:** Add `FaultyStorage` test double that throws `IOException("ENOSPC")` deterministically, run `RaftNode` against it, and assert: (a) leader does not advance commit index for the failed append, (b) leader steps down or self-fences, (c) followers do not see the entry.
- **Proposed owner:** common (Storage SPI) + consensus-core (RaftNode)

## C-007 — DNS resolution failure mid-cluster-life is unmodeled
- **Severity:** S2
- **Location:** `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:267-291` (`createPeerConnection`, `createClientSocket` resolve hostnames every time); `configd-server/src/main/java/io/configd/server/ServerConfig.java` parses peer hostnames at startup; no harness primitive for DNS failure
- **Category:** dns
- **Evidence:** `TcpRaftTransport` peers are stored as `InetSocketAddress` (line 53). Each new SSL socket is created with `factory.createSocket(host, port)` (line 291) where `host = address.getHostString()`. The JDK resolves the hostname per socket — a DNS outage during a reconnect storm prevents reconnection until DNS recovers. The simulator has no `simulateDnsOutage(durationMs)` hook and no test for "leader steps down because it cannot resolve any peers."
- **Impact:** During a control-plane DNS outage (CoreDNS crash on K8s), reconnects silently fail. The leader does not step down because heartbeats appear to "work" on already-open sockets — until they don't. S2 because Kubernetes deployment is the documented target (`deploy/kubernetes/configd-statefulset.yaml`).
- **Fix direction:** Add `ConnectionManager`-level DNS-failure injection. Add a chaos test that asserts: when DNS fails for 5 minutes, no node advances `commitIndex` past pre-outage state, and on DNS recovery the cluster catches up within 2× heartbeat interval.
- **Proposed owner:** transport (ConnectionManager) + testkit

## C-008 — Rolling-upgrade-during-fault: no scenario combines version skew + network partition
- **Severity:** S2
- **Location:** Spec `spec/ConsensusSpec.tla` does not model heterogeneous-version peers; harness has no node-version concept
- **Category:** rolling-upgrade
- **Evidence:** `SimulatedNetwork` treats all messages as opaque `Object`; no per-node version tag. `ChaosScenariosTest` does not exercise upgrading one node while another is partitioned. The implementation reads `MessageType.fromCode(typeCode)` (FrameCodec.java:128) but if a future version adds a `MessageType` enum value and a v0.1 receiver gets it during a rolling upgrade, decode throws `IllegalArgumentException` and kills the connection silently.
- **Impact:** A real rolling upgrade with a network partition can elect a v0.1 leader that does not understand a v0.2 follower's snapshot. Loss of liveness, possibly safety if a new MessageType is added without a `MIN_PROTO_VERSION` negotiation.
- **Fix direction:** Add a `protocolVersion` byte to `FrameCodec.Frame` header (currently 17 bytes — extend to 18 with a version byte). Add a chaos scenario `rollingUpgradeWithMinorityPartition()` that runs a 5-node cluster, partitions one node, "upgrades" two others (different `protocolVersion`), heals, and asserts no commit divergence.
- **Proposed owner:** transport (FrameCodec) + consensus-core (RaftNode)

## C-009 — Slow-disk fsync (10s+) behavior on quorum is not asserted
- **Severity:** S1
- **Location:** `configd-common/src/main/java/io/configd/common/FileStorage.java:89-114` (no fsync stall injection); `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:135-145` (link slowness exists but only network, not disk)
- **Category:** slow-fsync
- **Evidence:** ga-review.md line 98 admits: "Process-death / disk-full / fsync-stall chaos | RED — Out-of-scope for network-only harness." `addLinkSlowness` adds latency to message delivery but the disk-side fsync inside `RaftLog.append → FileStorage.appendToLog → channel.force(true)` cannot be slowed. A 10s fsync on the leader stalls the apply thread (single-threaded by design — RaftLog.java comment line 24), blocking heartbeats, causing unnecessary leader churn.
- **Impact:** S1 — no harness, no spec coverage. The single-threaded apply path means a 10s fsync also blocks consumption of inbound messages from the I/O thread, potentially backing up the kernel buffer until OOM.
- **Fix direction:** Add `Storage` test double `SlowStorage(fsyncDelayMs)` that sleeps `Thread.sleep(delayMs)` inside `sync()`. Add chaos test asserting the cluster transfers leadership when the leader's fsync exceeds half the election timeout for >3 consecutive heartbeats.
- **Proposed owner:** common (Storage) + consensus-core (RaftNode)

## C-010 — HLC has no defense against backward wall-clock jump
- **Severity:** S1
- **Location:** `configd-common/src/main/java/io/configd/common/HybridClock.java:89-108` (now); `:114-142` (receive)
- **Category:** clock
- **Evidence:** `now()` does `if (pt > curPhys) { nextPhys = pt; nextLogical = 0; } else { nextPhys = curPhys; nextLogical = logicalOf(cur) + 1; }`. If `pt` jumps backward by 1 hour (NTP step-correction, VM live-migration), the HLC pins to the OLD physical time and increments the 16-bit logical counter forever — overflow at ~65k events, which at 100k ops/sec is 650ms, after which `encode(nextPhys, nextLogical)` silently truncates. There is no test that does `physicalClock.setTime(t-3600_000)` and asserts the HLC remains monotonic without overflow. `receive()` has the same problem.
- **Impact:** S1 — no harness, no spec coverage. HLC overflow corrupts subsequent timestamps; silent ordering loss. NTP step-corrections in production are documented (>500ms drift triggers step on default chronyd) and we must survive them.
- **Fix direction:** Add `LogicalCounterOverflowException` (fail-fast) and add a chaos primitive `SimulatedClock.jump(deltaMs)`. Test asserts: (a) backward jump of 1h does not overflow logical counter for 1M events, (b) forward jump of 1h is accepted and old packed timestamps still order correctly.
- **Proposed owner:** common (HybridClock) + testkit (SimulatedClock)

## C-011 — Process-death + restart during snapshot install is unmodeled
- **Severity:** S2
- **Location:** `spec/SnapshotInstallSpec.tla:64-79` (Init has no "node restarts" action); `configd-consensus-core/src/test/java/io/configd/raft/InstallSnapshotTest.java` (no kill-mid-install case); harness has no `crashRestart` primitive
- **Category:** restart
- **Evidence:** `SnapshotInstallSpec.tla` models in-flight RPCs (line 51-52) but does not have a "Crash(n) ≜ snapshot[n] := [index|->0, term|->0]" action that simulates crash mid-install. The implementation receives chunked InstallSnapshot (RaftMessageCodec.java:209-265) but if the receiver crashes after writing the file but before durably recording `snapshotIndex`, recovery will replay from the wrong position.
- **Impact:** S2 — formal spec covers post-conditions but not the crash-during-install transition. Implementation may have a torn-snapshot bug.
- **Fix direction:** Add a `Crash(n)` action to `SnapshotInstallSpec.tla` and re-check `SnapshotMatching` and `NoCommitRevert`. Add `InstallSnapshotTest.crashAfterDataBeforeMetadataReplaysSafely()` that uses a `FaultyStorage` to fail the metadata write.
- **Proposed owner:** spec + consensus-core (InstallSnapshot)

## C-012 — Re-entrant chaos: no test that two chaos primitives fire inside each other
- **Severity:** S2
- **Location:** `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:92-112` (`send` checks each chaos primitive in sequence); `ChaosScenariosTest.DeterminismGuard` only stacks primitives, doesn't trigger interaction
- **Category:** reentrant
- **Evidence:** `send()` evaluates: partition → dropRate → dropEveryNth → tlsReload → freeze. The `messageCounter++` (line 97) increments BEFORE the freeze and reload checks (line 101-104). So a frozen-peer message still bumps the counter — meaning `dropEveryNth=5` plus a freeze that happens to drop 5 messages will desynchronize the "every 5th" expectation. `clearChaos()` (line 178-185) does not reset `sendSequence` or the priority queue; chaos cleared mid-test leaves dangling pending messages.
- **Impact:** S2 — flaky chaos tests under composition. The DeterminismGuard test (ChaosScenariosTest.java:325) asserts seed-determinism but the asserted trace is sorted by `Object::toString` (line 340) which masks ordering bugs.
- **Fix direction:** Define an explicit precedence in `SimulatedNetwork.send` and document it. Add a property test asserting: `send(N, msgs); freeze(); send(N, msgs)` is order-equivalent to `freeze(); send(2N, msgs)` after thaw, modulo deliveries.
- **Proposed owner:** testkit (SimulatedNetwork)

## C-013 — Out-of-order / duplicate delivery is promised but unimplemented in harness
- **Severity:** S2
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftTransport.java:8-13` (Javadoc claims simulator controls "reordering and loss"); `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:188-203` (`deliverDue` is strict priority-queue order, no duplication)
- **Category:** reorder
- **Evidence:** `RaftTransport` interface comment promises a "deterministic in-memory transport is used to control message delivery, reordering, and loss." `SimulatedNetwork.deliverDue` polls the priority queue ordered by `(deliverAtMs, sendSequence)` — strict FIFO per-link. There is no `duplicate(NodeId, NodeId, double)` primitive and no `reorder(int permutationSeed)` primitive. A duplicate AppendEntries with the same `prevLogIndex`/`term` is what triggers the classic "log truncation under retransmit" bug.
- **Impact:** S2 — RaftTransport contract is broader than the only implementation of it for tests. Real TCP/TLS does not duplicate, but a Raft implementation that assumes "no duplicates" may still violate safety with a buggy retry loop. We have no harness to catch this.
- **Fix direction:** Add `SimulatedNetwork.duplicateRate(double)` (re-enqueues a copy of every Nth message) and `swapAdjacentDeliveries(seed)`. Add a property test that random duplicate-and-reorder leaves cluster state convergent.
- **Proposed owner:** testkit (SimulatedNetwork)

## C-014 — One-way drops cannot model a half-deaf bridge node
- **Severity:** S3
- **Location:** `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:71-89`
- **Category:** partition
- **Evidence:** `addPartition(from, to)` IS one-way (good), but `dropEveryNth` (line 124) operates on a global `messageCounter` (line 97) — there is no per-link drop-Nth. So you cannot say "drop every 5th message ONLY on the link N1→N2 while N1→N3 is healthy." The slow-consumer scenario suffers the same: link slowness is per-link but drop primitives are global.
- **Impact:** S3 — improvement to chaos surface area. Many real partial-degradation faults manifest per-link.
- **Fix direction:** Promote `dropEveryNth` to `Map<Long, Integer> perLinkDropEveryNth`. Add `setDropEveryNth(NodeId from, NodeId to, int n)` overload.
- **Proposed owner:** testkit (SimulatedNetwork)

## C-015 — TCP transport accepts arbitrary sender NodeId in clear text (no peer-id ↔ TLS-cert binding)
- **Severity:** S2
- **Location:** `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:222-223` (reads `senderId` from socket without checking it matches the TLS cert subject)
- **Category:** other (security/chaos overlap)
- **Evidence:** `handleInboundConnection` does `int senderId = in.readInt(); NodeId from = NodeId.of(senderId);`. There is no validation that this id matches the TLS cert's CN/SAN. ga-review.md S4 confirms: "TcpRaftTransport binds peer-id to TLS cert subject | RED" (line 71). A compromised peer with a valid cluster cert can impersonate any node ID. This is also a chaos gap: the simulator cannot inject a "Byzantine peer impersonating the leader" scenario.
- **Impact:** S2 — known security RED, but ALSO a missing chaos scenario class (Byzantine-style impersonation). Even if S4 lands, we should have a chaos primitive.
- **Fix direction:** Add `SimulatedNetwork.injectImpersonatedMessage(NodeId trueFrom, NodeId claimedFrom, Object msg)` and a test asserting that consensus-core ignores or fences messages whose claimed sender disagrees with the transport-validated identity.
- **Proposed owner:** transport + testkit

## C-016 — @Buggify infrastructure is dead code; zero call sites in production
- **Severity:** S3
- **Location:** `configd-common/src/main/java/io/configd/common/Buggify.java:1-25`; `configd-common/src/main/java/io/configd/common/BuggifyRuntime.java:35-43`
- **Category:** other
- **Evidence:** `grep -rln "BuggifyRuntime.shouldFire\|@Buggify" --include="*.java"` returns only the runtime's own test and its own definition. The `@Buggify` annotation is `@Retention(RUNTIME)` but is never read by any reflection scanner. The "FoundationDB-inspired" claim in the Javadoc is currently false — production code has zero injection points.
- **Impact:** S3 — false sense of fault coverage. The mechanism exists but does nothing.
- **Fix direction:** Either (a) actually instrument hot paths (`RaftNode.tick`, `RaftLog.append`, `TcpRaftTransport.send`) with `if (BuggifyRuntime.shouldFire("raft.tick.miss")) return;`, or (b) delete the dead code and remove the documentation references.
- **Proposed owner:** common (Buggify) + consensus-core (call-site instrumentation)
