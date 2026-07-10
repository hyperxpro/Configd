# Production-Readiness Standard and Gap Assessment — the Postgres Bar, No Deferrals

**Date:** 2026-07-03
**Scope:** main @ `012e213`. READ-ONLY assessment — nothing in the repo changed except this file.

> **AS-BUILT UPDATE (2026-07-04).** This assessment is the honest snapshot as of 2026-07-03; its
> verdicts are preserved. Since then the **frozen-format arc (Gates 1–5)** landed and CLOSED the two
> frozen-format kernel GAPs called out in §0 (line 38–40): the truncation/rollback **durability
> anchor** (Gate 3a `raft-anchor` + merge + persist-before-ack + recovery gates; §2.1-6 fsyncgate is
> part of it) and **non-destructive, reachable key rotation** (Gate 4 persisted dual-slot keyring;
> the previously data-destroying path is now impossible by construction). Gate 3b added the
> `node-anchor`, Gate 3c the peer-quorum `AnchorWitness` (R-a′). Gate 5 added the first
> **encryption-ON, multi-node cluster composition** coverage (`EncryptedMultiShardClusterCompositionTest`)
> and a **> 4 MiB encrypted snapshot** round-trip (`Over4MiBEncryptedSnapshotRoundTripTest`), closing
> several of the §2.5 encryption-ON interaction cells. See
> `docs/design/frozen-format-v1-2026-07-03.md` (its top-of-doc AS-BUILT block) for the shipped detail
> and the honest residuals (R-a freshness, R-a′ N≥5 fast-vote, R-g audit-not-term-versioned, R-h
> no-live-rotation-trigger). The OTHER GAPs here (zero independent protocol clients, soak duration,
> mixed-version story, unversioned edge/etc. formats, leadership auto-balance) are NOT frozen-format
> items and remain as assessed.

> **E1 UPDATE (2026-07-10) — Class E items E1–E4 CLOSED (§2.2).** The faulted-linearizability gaps are
> closed by the E1 arc on the release bytes of `299ba14`. The 15-second, N=1, quorum-preserving smoke is
> replaced by a real **Jepsen-grade adversarial matrix**: `kill -9`+restart, `iptables -j REJECT`
> partitions (single + multi-node **quorum-breaking**), `SIGSTOP`/`SIGCONT` pauses, `iptables -m statistic`
> packet loss, `libfaketime` clock skew, and **overlapping combinations**, on **N=3 and N=5** across
> at-rest-encryption / auth / clock-skew / **multi-shard** postures — every history LINEARIZABLE, checked
> by Porcupine, with the harness's discrimination re-proven on HEAD (both seeded bugs RED). This closes
> **2.2-2** (nemesis breadth — E2), **2.2-3** (Jepsen-grade duration/intensity — E3), **2.2-4** (release-SHA
> evidence + the standing CI job now runs the real matrix — E1), and **2.2-5** (per-shard linearizability
> at N>1 shards — E4). Residual: **asymmetric / partial (bridge) partitions** need per-pair source-addressed
> cuts and remain a recorded **netns follow-up** (single-host loopback cannot do them). **E5 (the ≥72 h
> soak) is a SEPARATE arc and remains OPEN.** Full results: `docs/measurement/e1-faulted-linz-2026-07-10/`.

**The bar:** everything tested, all spec matches industry standards, no compromises. An item is
`MEETS-BAR` only when the code is right AND a named test proves it AND it matches what an
industry-grade system (Postgres, etcd, Spanner, CockroachDB, Vault, the Jepsen practice) actually
guarantees. Everything else is `GAP` — including "untested," "analysis-only," "documented
limitation in a frozen-forever format," and "buildable from the RFC."
**Method:** five-lane Opus agent team — reference-researcher (the cited industry standard per
area), java-distinguished-engineer (code + proving-test inventory, incl. the full interaction
matrix), security-reviewer (§2.3/§2.4), reliability-engineer (§2.1/§2.2/§2.7/§2.8/§2.10),
protocol-expert (§2.6/§2.9/mixed-version cells). Every claim grounded at file:line. Lane
disagreements were reconciled by the coordinator against source (see §5 — three lane claims were
corrected against the code/git history before this document was written).

---

## 0. The verdict — totals

**66 itemized verdicts: 30 MEETS-BAR, 36 GAP.**
Plus the §2.5 interaction matrix in detail: of 21 meaningful feature-pair/triple cells, **8 are
tested, 13 are ABSENT** — all 8 encryption-ON cells among them.

What is genuinely strong (tested, industry-grade):

- The durability kernel: fsync-before-ack with the leader's own vote gated on `durableIndex`,
  group commit, crash/torn-tail/ENOSPC recovery matrices, tamper refusal on every artifact.
- The authorization plane: capability model with deny-precedence and the WATCH∧READ floor,
  whole-target watch authorization with interior-DENY (finer than etcd RBAC), the multi-shard
  `_acl/` shard-completeness fix red-proofed at N>1, SUBSCRIBE authorized with zero-data-frame
  denial, and a real adversarial test population in CI.
- The `IntegrityEnvelope` format discipline: explicit MAC-covered format version distinct from
  algId, unknown-version/downgrade refusal, tested — the template the other formats should meet.
- Frame-level wire hygiene: version pin, unknown-version/frame refusal, jqwik fuzzing in CI,
  golden byte fixtures at all three wire versions.

What stands between here and the Postgres bar (the 36 GAPs, sized in §4): two
frozen-format kernel defects (no truncation/rollback anchor; key rotation unreachable and its
documented path data-destroying), a hermetically untested encryption-ON production configuration,
zero independent protocol clients, a 15-second quorum-preserving linearizability smoke whose last
green run is 7 commits behind HEAD, a 6-hour fault-free soak against a 30-day contract, no
mixed-version story, four unversioned persistent formats, and a set of silent failure paths and
untested limits.

**This assessment does not decide ship/defer. It is the itemized truth for the operator (§6).**

---

## 1. Rules applied (no escapes)

1. TESTED or NOT — "probably/likely/should/composes-by-analysis" appears below only inside GAP
   entries describing what the repo currently offers instead of a test.
2. A documented limitation in a frozen-forever format is a GAP.
3. There is no v2 — every item is in-and-done or an open gap.
4. A protocol is proven by a working conforming client, not an RFC.
5. GAPs are sized honestly; "small" appears only where it truly is.

---

## 2.1 Durability and crash safety — 5 MEETS-BAR / 2 GAP

**The standard** (cited): Postgres `synchronous_commit=on` fsync-before-COMMIT-returns; etcd WAL
fsync before quorum count; ZooKeeper `forceSync`. Torn writes repaired, not trusted (Postgres
`full_page_writes`, InnoDB doublewrite, bbolt dual meta pages). fsync failure is fatal, never
retried (fsyncgate; Postgres `data_sync_retry=off` PANICs). Per-record CRC checked on read
(Postgres WAL CRC-32C). Crash safety proven by fault-injection harnesses (SQLite crash-VFS,
Postgres `src/test/recovery`, etcd robustness/gofail). ENOSPC is a clean bounded rejection (etcd
quota + NOSPACE alarm). Durability validated on the real disk stack (pg_test_fsync).

**2.1-1 fsync-before-ack — MEETS-BAR.**
State: `FileChannel.force(true)` at `FileStorage.java:88` (put), `:136`/`:179` (WAL append/batched
group commit), `:352` (dir fsync). The leader counts its own copy toward the commit quorum only
when `durableIndex >= n` (`RaftNode.java:2175`, advanced strictly after fsync in `flushDurable`
`:2236-2237`); followers fsync before the append RPC returns (`RaftLog.java:449`).
Test: `GroupCommitDurabilityTest.gateBlocksCommitUntilLeaderEntryIsDurable:94` +
`queuedFlushAfterStepDownDoesNotCommitAsFollower:122`; `WalSyncCrashTest`;
`VotePersistenceCrashTest:98`; metal 1000/1000 no-loss kill-9 drill
(`docs/archive/measurement/ec2-2026-06-30/02-dr-drills.md`).

**2.1-2 kill -9 / crash recovery — MEETS-BAR.**
State/Test: seed×crashpoint matrix `SnapshotCrashRecoveryTest`
(`recoversAfterSnapshotAndWalTruncate:105`, `recoversWhenCrashedBetweenPersistAndTruncate:111`,
`recoversWhenCrashedBeforeSnapshotPersist:117`, `matrixHoldsAcrossSeeds:123`);
`AdversarialCrashRecoveryTest.seededCrashAfterSyncedWritesPreservesDurablePrefix:47`. Runs in CI
via `gates/gate-4.sh:121,124`. Honest caveat: the harness is `CrashStorage`, an in-JVM
revert-unsynced model — the same class of simulated crash harness SQLite's crash-VFS bar cites;
the only real-process `kill -9` of a live JVM is the configd-linz nightly (see 2.2-4).

**2.1-3 torn / partial write — MEETS-BAR.**
State: truncated trailing frame discarded at `FileStorage.java:271`; CRC32 per storage frame.
Test: `SnapshotCrashRecoveryTest.recoversCleanlyFromTornFinalWalRecord:153`;
`WalRecordIntegrityTest.tornTrailingWalRecordIsToleratedAndPriorEntriesRecover:87`;
`FileStorageTest.crc32IntegrityVerification:106`; fsync-lie detection
`SnapshotCrashRecoveryTest.gapDetectionFiresWhenSnapshotFsyncLied:274`.

**2.1-4 per-record integrity validated on read — MEETS-BAR.**
State: every artifact wrapped by `IntegrityEnvelope` (MAC or GCM tag + CRC32C); recovery stops at
the first record failing verification. Test: `WalRecordIntegrityTest.tamperedCompleteWalRecordIsRefused:61`;
`SnapshotIntegrityTest` (6 cells incl. `downgradeToAlgNoneIsRefused:143`,
`forgedInstalledSnapshotIsRefusedOnRecovery:179`); `DurableRaftStateIntegrityTest`.

**2.1-5 disk-full (ENOSPC) — MEETS-BAR.**
State/Test: `StorageEnospcConsensusReactionTest` against a LIVE RaftNode —
`enospcOnWalAppendSurfacesAndNeverSilentlyAdvancesTheLog:45`,
`enospcDuringSnapshotWriteLeavesWalIntactNoLoss:98` (injector
`FaultInjectingStorage.enospcAfterBytes:62`).

**2.1-6 fsync-failure fail-closed policy (the fsyncgate class) — GAP.**
State: the snapshot fsync-LIE path is detected (`gapDetectionFiresWhenSnapshotFsyncLied:274` uses
`FaultInjectingStorage.failNextSyncs:56`), but an fsync **throw** on the WAL group-commit path
(`syncWal`→`FileStorage.syncLog`) is never injected against a live RaftNode, and there is no
Postgres-style PANIC/fail-closed policy decision at that seam — no test proves a failed WAL fsync
cannot advance `durableIndex` or ack.
Close: wire `failNextSyncs` into a live-RaftNode cell; assert no-durable-advance/no-ack; adopt an
explicit fail-closed policy (step-down or exit) at `flushDurable`/`syncWal`. ~1 test file + a
small policy change. No hardware.

**2.1-7 durability validated on the target disk stack — GAP (minor).**
State: no pg_test_fsync-equivalent check exists in the deploy/runbook path; the metal drills are
point evidence on one instance type. Close: add an fsync-method/latency probe to the operator
runsheet (script + doc). Small.

---

## 2.2 Consistency — the Jepsen bar — 2 MEETS-BAR / 4 GAP

**The standard**: formal checker (Knossos/Porcupine/Elle); the full nemesis menu — partitions
incl. asymmetric, clock skew, SIGSTOP pauses, kills, membership churn, combined faults; hours of
generative workload across many seeds; run against the shipping build; safety AND
staleness/availability both bounded.

**2.2-1 formal checker — MEETS-BAR.**
State: Porcupine (etcd's checker, `anishathalye/porcupine v1.2.0`) via a trusted Go binary,
`PorcupineChecker.java:60-92`; refuses to pass without the binary (`:35-54`); INDETERMINATE is
never a pass (`Verdict.java:5-9`). Test: `CheckerSelfTest` (8 cases incl. known-non-linearizable
histories rejected).

**2.2-2 fault-matrix breadth — GAP → ✅ CLOSED by E1 (2026-07-10).**
State: real-process nemesis = symmetric whole-node iptables partition + `kill -9` only
(`FaultInjector.java:54-85`; `Schedule.FaultKind` = ISOLATE_LEADER/ISOLATE_NODE/KILL_LEADER/
KILL_NODE, `Schedule.java:23`), sequential single faults, quorum always preserved
(`Schedule.java:14-17,88-101`). ABSENT from the real harness: asymmetric/partial partitions,
clock skew, SIGSTOP pauses, message drop/delay/reorder, simultaneous faults, membership change
under fault. (`PartitionMatrixTest` covers asymmetric/gray/clock-skew against the SIM model only.)
Close: extend `FaultInjector`/`Schedule` with clock-skew, pause, and partial-partition nemeses +
concurrent-fault schedules; needs a multi-core box (loopback limits partial partitions —
`FaultInjector.java:18-22`). Harness work (~days) + paid runs.

**2.2-3 duration / intensity — GAP → ✅ CLOSED by E1 (2026-07-10).**
State: the faulted matrix is 4 seeds × {3,5} nodes × **15 seconds** × 4 clients
(`configd-linz/scripts/run-gate.sh:19-33`), ~800 ops/run — a smoke, not a Jepsen run.
Close: ≥30–60 min per seed, ~100× op volume, more seeds; paid multi-core box, hours.

**2.2-4 release-commit evidence + CI gating — GAP → ✅ CLOSED by E1 (2026-07-10).**
State: last green faulted-linz = `eb9b293`
(`docs/measurement/ec2-drive-to-green-2026-07-02/gate7-final/README.md:8`), now **7 commits
behind HEAD**, and the linearizable applied state changed after it (`ConfigStateMachine.java`,
`ConfigDelta.java`, `ConfigSnapshot.java`, `VersionedValue.java` — PRs #52-55). The
`linearizability-under-fault` job is `schedule`/`workflow_dispatch` only and is deliberately
excluded from the `ci-success` seal (`.github/workflows/ci.yml:391-393`) — it cannot block a PR
and did not gate the #55 merge. No linz run exists on `012e213`.
Close: run the existing matrix on `012e213` (one ≥8-vCPU box with sudo/iptables, <1 h) — and
decide whether faulted-linz should gate release tags structurally.

**2.2-5 multi-shard linearizability at N>1 — GAP → ✅ CLOSED by E1 (2026-07-10).**
State: the linz harness has no shard parameter (grep `shard` in `configd-linz/src` = 0); every
node under Porcupine boots single-group. Per-shard linearizability at N>1 — the shipped
production shape — has never been checker-verified. Cross-shard writes are disclaimed
(`CrossShardWriteGuard`), which is honest, but the per-shard claim itself is unproven at N>1.
Close: `--shards N` in `HarnessMain`/`Cluster`, key partitioning by `shardFor`, per-shard
Porcupine check. Harness change + paid run.

**2.2-6 staleness/liveness bounded alongside safety — MEETS-BAR.**
State/Test: INV-S2 edge-staleness measured decisively GREEN (p99 24 ms vs 500 ms bound; 30-min
18k-sample definitive run) and failover RTO measured (372 ms, 0 loss; 4.2–5.9 s edge RTO)
(`gate7-final/README.md`). Caveat: measured on `eb9b293` — the SHA-staleness is counted once, in
2.2-4, not double-counted here.

---

## 2.3 Encryption at rest — the frozen-forever format — 2 MEETS-BAR / 5 GAP

**The standard**: Vault barrier — AES-256-GCM AEAD with tested nonce discipline; a real key
hierarchy behind an unseal boundary; versioned key terms with O(1) rotation and old-term reads;
AAD binding ciphertext to its location; a version byte on every crypto envelope; and (per
Postgres WAL chaining / CT signed tree heads) tail-truncation of a log detectable only via a
MAC'd/signed monotonic anchor — per-record integrity alone cannot see it. Zeroization is
best-effort on managed runtimes; mlock/no-swap is the enforceable control.

**2.3-1 AEAD correctness + nonce uniqueness — MEETS-BAR.**
State: AES-256-GCM, 128-bit tag, 96-bit nonce = 4 zero bytes ‖ 8-byte BE monotonic counter per
(keyTerm, segmentId) (`IntegrityEnvelope.java:98-104,495-512`;
`SegmentKeyManager.java:154-163,202-209`); DEK = HKDF-SHA256(root[term], salt=segmentId,
"…/dek/v1") (`:190-200`); segment rolls at `REKEY_LIMIT=2^32` (NIST SP 800-38D) with a fresh
random 128-bit segmentId; restart draws a fresh segment before any counter reuse (`:181-188`).
Test: `SegmentKeyManagerTest.noncesAreUniqueWithinASegmentStream:44` (100k),
`concurrentNextSealNeverRepeatsANonce:123` (8×50k), `freshManagerDrawsAFreshSegment…:75`;
`IntegrityEnvelopeEncryptionTest:83`. Note: the 2^32 rollover boundary itself is un-driveable in
a unit test — constant-asserted only.

**2.3-2 AAD completeness forever (truncation / rollback anchor) — GAP. Highest stakes.**
State: AAD = the 40-byte prefix `header(8)‖keyTerm(4)‖segmentId(16)‖nonce(12)`
(`IntegrityEnvelope.java:102-104`, write `:425`, read `:481`) — binds magic/version/alg/key
routing, but **no position, no record count, no monotonic head anchor** (grep
`highWater|recordCount|anchor|manifest` in RaftLog/FileStorage = 0). Deleting whole trailing WAL
frames leaves every survivor independently valid; recovery accepts the shorter log as legit
crash-truncation (`WalRecordIntegrityTest.tornTrailingWalRecordIsToleratedAndPriorEntriesRecover`
proves torn-tail is *accepted* — correct for crashes, indistinguishable from adversarial
truncation). The same root cause admits whole-file rollback of `raft.persistent_state`.
Per-record tamper/AAD/truncation-of-a-record refusal IS tested
(`IntegrityEnvelopeEncryptionTest:96-143`); log-level truncation detection has no mechanism and
no test. Under this assessment's rules — the format freezes with it — this is a GAP, not a
documented limitation. (Also: `docs/v2-backlog.md:7` overclaims that offset-in-AAD would close
this; it would not — offset catches reorder/splice, never trailing truncation.)
Close: a durable **authenticated monotonic high-water mark** (last-durable-index) persisted
inside the already-authenticated `raft.persistent_state` envelope + an anti-rollback recovery
gate + a crash-matrix test surface (truncate-tail / rollback-state / torn-vs-tamper
distinguishing). ~150–300 LOC on the persist-before-ack path — durability-kernel work, the
highest-risk item in this list.

**2.3-3 format version marker — MEETS-BAR.**
State: `FORMAT_VERSION=(short)2` is a dedicated 2-byte field distinct from the 1-byte algId
(`IntegrityEnvelope.java:76,86-90,196-197`), MAC-covered and inside the GCM AAD; rolled/unknown
version refused at parse (`:299-305`). Test: `IntegrityEnvelopeTest.rolledFormatVersionThrows:105`;
`SnapshotIntegrityTest#forgedFormatVersionIsRefused`; algId-downgrade refusal
(`flippedAlgIdToNoneUnderKeyedThrows_downgrade:91`).

**2.3-4 key hierarchy + rotation — GAP.**
State: the mechanism is correct in isolation — `SegmentKeyManager.rotateTo` installs a new term
and retains old ones (`SegmentKeyManager.java:137-146`), `resolveDek` reads any retained term and
fails closed on unknown terms (`:165-179`) — and is API-tested
(`SegmentKeyManagerTest.rotationRetainsOldTermForReadAndUsesNewTermForWrite:104`;
`LocalKmsEncryptionIntegrationTest.rotation_oldTermDataStillDecryptsAfterBumpToNewTerm:101`).
But **`rotateTo` has no production call site** (grep: tests only); boot hardcodes **term=1**
(`ConfigdServer.java:1325`) and never persists/recovers a term. Consequence: the documented
`local` rotation path ("rotate the signing key," `LocalDerivedKmsProvider.java:40-44`) re-derives
a different root still at term 1 → every prior record's GCM tag fails → the node fail-closes on
recovery. **The only documented rotation procedure is data-destroying for an encrypted node**,
and no production-reachable rotation test exists.
Close: persist the keyring term (extend the local key descriptor; boot `unsealFrom` it), wire an
operator rotate trigger, add e2e write@N→rotate→write@N+1→restart→both-decrypt. ~120–200 LOC + 1
integration test.

**2.3-5 key zeroization — GAP (JVM-platform-bounded; low severity).**
State: in-process zeroization is real and tested — bespoke `RootKey` zeroes its private `byte[]`
on `destroy()` (`RootKey.java:105-111`), `withMaterial` wipes the transient clone (`:81-89`),
use-after-destroy throws, deliberately not `SecretKeySpec` (JDK-8160206, `:15-23`); tests
`KmsKeyMaterialTest:36,67,93`. Boot wipes IKM (`ConfigdServer.java:1248-1256`); heap-dump policy
doc exists. Unmet vs the Vault mlock bar: no mlock/no-swap/core-dump suppression, `toSecretKey`
creates un-wipeable JCA copies, GC copying leaves residuals.
Close: not fully closable on the JVM — deploy-level core-dump/swap controls in the runsheet,
audit call sites to prefer `withMaterial`, and convert the residual into a tested boundary. Small
+ docs.

**2.3-6 KMS / unseal / default custody — GAP.**
State: default `LocalDerivedKmsProvider` is key **derivation, not custody** — RootKey =
HKDF(signing-key) (`LocalDerivedKmsProvider.java:46-50,111-120`); unwrap re-derives and never
fails closed (`:104-109`); no independent unseal secret (fate-sharing documented `:36-44`). The
D-1 co-location guard is real, fail-closed, and tested (`ConfigdServer.java:1366-1397`;
`D1FailClosedTest`), and unknown providers refuse boot (`:1310-1317`) — but **only `local`
ships**; no off-host KMS/HSM provider exists, so the fail-closed KMS-unavailable contract has
nothing to test against.
Close: ship one real off-host provider (AWS KMS or Vault-transit) on the existing SPI + a
fail-closed unavailability integration test (KMS emulator). ~1 module, ~300 LOC + tests.

**2.3-7 encryption-ON production coverage — GAP.**
State: the at-rest encryption test population is exactly 5 files, all N=1, all codec/wiring level
(`SegmentKeyManagerTest`, `IntegrityEnvelopeEncryptionTest`, `LocalKmsEncryptionIntegrationTest`,
`RaftLogEncryptionTest` — single node, `EncryptionAtRestWiringTest` — explicit no-server-boot).
Scripted grep intersection with any fan-out/coordinator/multi-shard/signature/filter/watch test =
**zero**. No encrypted record has ever crossed a replicated multi-node cluster in any test. The
composition was argued clean by tracing (`docs/archive/investigation/encryption-interaction-2026-07-03.md`)
— which is exactly the "composes-by-analysis" this bar forbids.
Close: one `MultiShardIntegratedSweepTest` variant passing
`IntegrityEnvelope.encrypting(new SegmentKeyManager(localRoot), null)` into `buildRaftGroup`,
asserting per-shard WAL/snapshot recovery after restart + multi-shard watch catch-up/filtered
tail + fan-out signature verify — closes three matrix cells at once. ~1 test, low risk. A
multi-node-failover-with-encryption cell needs a second, cluster-level test.

---

## 2.4 Authorization and security — 5 MEETS-BAR / 3 GAP

**The standard**: a current written threat model (Vault security model; CNCF audits);
default-deny per-capability per-scope (etcd RBAC, ZooKeeper ACLs, Vault policies); deny
precedence; no endpoint bypasses the authorizer (etcd CVE-2020-15115 as the cautionary case);
adversarial tests/fuzzing in CI; secure-by-default posture (Postgres `listen_addresses=localhost`
+ auth required).

**2.4-1 written threat model — GAP (stale for the shipped system).**
State: a real signed model exists (`docs/archive/security/threat-model.md` — adversaries A1–A6,
boundaries B-WIRE/B-DISK/B-API, a named-negative-test ledger `:118-141`) but predates the shipped
surfaces: no at-rest **confidentiality** plane (KMS unseal, RootKey→DEK, custody fate-sharing),
no roles/policies or `_acl/`/`_system/` config-policy control plane, no multi-shard watch authz,
no edge cache tier as a post-authorization boundary (the edge serves cached values with no
per-read ACL — a trusted-cache-tier design decision documented nowhere in the model).
Close: STRIDE rows for the KMS/encryption plane, the config-policy plane, and the edge tier.
Docs-only, ~1 day.

**2.4-2 capability enforcement — MEETS-BAR.**
State: `AclService.isAllowed` = union-of-ancestors + absolute deny-precedence + default-deny
(`AclService.java:414-511`); WATCH∧READ floor (`:503-509`); ADMIN-not-super (`:116-118,510`);
reserved-prefix (`_acl/`+`_system/`) ADMIN gate with predicate-alignment
(`AdminApiHandler.java:674-744`), auth-off still refuses reserved writes (`:700-706`).
Test: through the real HTTP handler with restricted principals —
`AbstractAdminApiServerContract:309-345,364,1107`; `ReservedPrefixAdminGateTest` (11 adversarial
cases incl. percent-decoding evasion, adversarial self-deny, fail-closed-no-ACL);
`AclServiceRedTeamTest` (13 attack groups); `AclServiceRoleRedTeamTest`;
`AclServiceByteIdentityDifferentialTest`. Caveat (not a bypass): the shipped `AuthInterceptor`
mints exactly one control-plane principal (ROOT=allOf, `ConfigdServer.java:689-704`) — the rich
multi-principal model is exercised via tests and `_acl/` policy, not a shipped second token path.

**2.4-3 LIST capability wiring — GAP (narrow).**
State: `Permission.LIST` is defined and its logic unit-tested
(`AclServiceTest.listIsGrantedAndDeniedIndependently:362`), but **no endpoint or gate maps any
operation to LIST** (grep: enum decl + serializer token only). A defined, advertised capability
with no enforcement point is dead surface in a frozen policy grammar.
Close: ship the LIST endpoint gated by `Permission.LIST` (it is already in the RFC §1 model), or
explicitly remove/reserve the capability. Small (~100-200 LOC + contract tests) either way.

**2.4-4 multi-shard `_acl/` shard-completeness — MEETS-BAR.**
State: scatter-gather policy rebuild over every shard store (Gate 2 / PR #53).
Test at forced N>1: `AclConfigPolicyLoaderMultiShardTest.tB6_multiShard_appliesNonPrimaryShardDeny_watchRejected:114`
+ the **red-proof** that the old primary-only build misses the deny (`:153`), union==single-store
(`:190-224`), N=3 concurrent-apply convergence (`:231`).

**2.4-5 SUBSCRIBE authorization — MEETS-BAR.**
State: legacy full-store SUBSCRIBE is gated at admission on whole-store root READ cover
(`AclServiceWatchAuthorizer.authorizeSubscribe` → `coversTarget(rules,"",READ)`; invoked at
`FanOutConnectionDriver.java:809-818`); identity is the verified cert-DN, not the wire edgeId.
Test: `LegacySubscribeAuthzTest` — denied→NOT_AUTHORIZED with zero data frames (`:95`),
granted→hydrates, throwing→fail-closed (`:123`), wire edgeId cannot self-authorize (`:159`).
(This corrects the earlier drive-to-green finding — the recommended AUTHORIZE was executed.)
Residuals live elsewhere: auth-OFF admits (counted in 2.4-7); N>1 partial-shard SUBSCRIBE
completeness refusal is tested (`LegacySubscribePartialShardViewTest:100/:116/:146`).

**2.4-6 watch-authz whole-target coverage — MEETS-BAR (exceeds the etcd bar).**
State: `coversTarget` decides whole-subtree coverage with an explicit interior-DENY term
(`AclService.java:694-712`); FULL/PREFIX→whole-target cover, KEY→exact-key floor; reject, not
filter (`AclServiceWatchAuthorizer.java:48-51,73-86`); bounded revocation on `_acl/` version
advance (`FanOutConnectionDriver.java:743,989`).
Test: `WatchAuthzGateContractTest:75-146` (interior-DENY carve rejects subtree; FULL→root
cross-tenant guard); the old single-key blind spot is pinned as a red test
(`AclServiceRedTeamTest.subtreeWatchRootCheckMissesDescendantReadDeny:754`) and the fix is proven
by a 200k-rule differential fuzz oracle
(`AclServiceCoversTargetTest.authorizesWatchEqualsPerKeyReadAndWatchUnderFuzz:515`). Pinned
residual: literal `startsWith`, not segment-aware (`allowOverGrantsAcrossSegmentBoundary:275`).

**2.4-7 secure-by-default / no-bypass posture — GAP.**
State: every control-plane store-touching path routes through checkAuth/checkAdmin
(`AdminApiHandler.java:251,346,399,610`); `/metrics` is bearer-gated and path-exact-matched
(`:188,218-244`). But **auth is OFF by default** (`ServerConfig.java:207-209`) with a **`0.0.0.0`
default bind** (`ServerConfig.java:63,84`) — out of the box the whole surface (reads, writes,
SUBSCRIBE) is open on all interfaces behind a stderr banner (`ConfigdServer.java:680-686`). That
is the opposite of the Postgres localhost+auth-required default. Additionally the edge read tier
serves cached values with no per-read ACL (coarse admission-time authz; `EdgeReadHandler.java:197`
is the edge's own bearer check) — safe only under per-tenant edge scoping, which only the (stale)
threat model could state.
Close: default bind to loopback (or refuse non-loopback bind while auth is off) + document the
edge trusted-cache boundary in the threat model. Small code + docs; today this lives as an
operator obligation (go/no-go C1), not a default.

**2.4-8 adversarial tests in CI — MEETS-BAR.**
State/Test: gate-7 CI runs `AclServiceRedTeamTest`, `AclServiceRoleRedTeamTest`,
`ReservedPrefixAdminGateTest`, `WatchAuthzGateContractTest`, `LegacySubscribeAuthzTest`,
`WatchSnapshotAuthzRegressionTest`, `RedTeamCoalescedWirePoCTest`, `D1FailClosedTest`,
`EdgeAdversarialGateSeedSweepTest`, `RaftTransportMtlsAttackTest`; frame fuzzing
`EdgeFrameCodecFuzzTest`/`FrameCodecFuzzTest` (jqwik, in the normal reactor). The un-red-teamed
surfaces are precisely the mechanisms that don't exist yet (2.3-2, 2.3-4, 2.3-7).

---

## 2.5 The feature-interaction matrix — GAP (8 cells tested, 13 ABSENT)

**The standard**: features are tested together, not alone — etcd e2e combines TLS+auth+snapshot;
Postgres check-world combines recovery+replication+SSL; CockroachDB metamorphic/roachtest chaos
runs mixed-version + workload + faults. FoundationDB BUGGIFY randomizes everything at once.

Census method: grep of every src/test tree for encryption-enabling constructs
(`IntegrityEnvelope.encrypting`, `isEncrypting`, `ALG_AES256_GCM`, `configd.raft.encryption.enabled`)
co-located with each feature; decisive proof that sharded tests never encrypt:
`MultiShardIntegratedSweepTest.java:188`, `MultiGroupBringupTest.java:256`,
`ShardedRoutingTest.java:400` all construct the plain keyed-HMAC envelope.

| # | Cell | Verdict |
|---|------|---------|
| 1 | encryption × sharding N>1 | **ABSENT** |
| 2 | encryption × multi-shard watch fan-out | **ABSENT** |
| 3 | encryption × server-side prefix filter | **ABSENT** |
| 4 | encryption × signed version position | **ABSENT** |
| 5 | encryption × failover (multi-node) | **ABSENT** (restart round-trip is N=1 only: `RaftLogEncryptionTest:118`) |
| 6 | encryption × snapshot chunking | **ABSENT** |
| 7 | encryption × ACL policy-as-config | **ABSENT** |
| 8 | encryption × N>1 × watch (triple) | **ABSENT** |
| 9 | N>1 × ACL policy-as-config | TESTED — `AclConfigPolicyLoaderMultiShardTest:114` |
| 10 | N>1 × watch fan-out | TESTED (component) — `MultiShardCoordinatorTest`, `RealHashCompletenessTest`, `ShardedFanOutTest:107,189`; not a full N-raft cluster + live edge |
| 11 | N>1 × prefix filter | TESTED — `LegacySubscribePartialShardViewTest:100`, `EdgeFilterPostureTest`, `NGreaterThanOneBootSmokeTest` |
| 12 | **N>1 × failover/restart** | **ABSENT** — every N>1 test brings up SINGLE-NODE groups (`MultiShardIntegratedSweepTest.java:153` "single-node group should self-elect"); no test kills a leader among N>1 groups |
| 13 | watch fan-out × prefix filter | TESTED — `FanOutFilterDivergenceTest`, `FanOutSessionCorePrefixFilterTest`, `FullChainDeliveryTest` |
| 14 | filter × signed version | TESTED — `FanOutFilterDivergenceTest`, `EdgeFilterPostureTest`, `FanOutFilterMtlsBindTest` |
| 15 | chunking × failover | TESTED (forced 2-byte chunks) — `ChunkedInstallSnapshotTest.multiChunkTransferSurvivesMiddleChunkLoss:577`; see 2.10-1 |
| 16 | ACL × failover/restart | PARTIAL — loader across snapshot/boot (`:309,:365`), not a live leader failover |
| 17 | chunked snapshot × 0x01 edge (live socket) | TESTED — `AbstractFanOutServerContract.propagation…:695-724` |
| 18 | 0x03 filtered server × 0x03 edge (live socket) | **ABSENT** — the shipped filtered fan-out path has never run over a real socket (codec/component only) |
| 19 | 0x03 server × 0x01 edge (filtered off, live) | **ABSENT** (live) — codec back-compat only (`EdgeFrameCodec:704`) |
| 20 | mixed-version frame reject (live socket) | **ABSENT** (live) — codec-level only (`V2GoldenFixtureTest:179-184`, `V3…:109-115`) |
| 21 | old binary × new binary (any surface) | **ABSENT** — no released older build; `SnapshotWireCompatStubTest` is a `@Disabled` stub |

The encryption row (cells 1–8) is the assessment charter's named suspicion, confirmed: the
encryption-ON and distributed-feature test populations have **zero intersection**. Cell 12 is a
new finding of this assessment: multi-shard operation has never been tested through a leader
failover anywhere.

Close-work: cells 1–4, 6–8 largely collapse into the one composed encrypted sweep test (2.3-7)
plus an encrypted multi-node failover cell; cell 12 needs a multi-node-per-group N>1 harness
(medium — the sweep harness currently assumes single-node groups); cells 18–20 are ~2–3 days of
live-socket legs on `AbstractFanOutServerContract`; cell 21 unlocks only after a first release
artifact exists.

---

## 2.6 The protocol and its clients — 2 MEETS-BAR / 3 GAP

**The standard**: a protocol is proven by ≥1 conforming client exercised in CI against a live
server (libpq; etcd clientv3/jetcd; redis-cli), ideally independently implemented (pgjdbc/psycopg
/pgx; gRPC interop suite); golden vectors pin the bytes; downgrade/unknown-version behavior is
tested in both directions.

**2.6-1 independent conforming clients — GAP. Count = 0.**
State: the edge wire has exactly ONE implementation — `EdgeFrameCodec`
(`configd-distribution-service/.../wire/EdgeFrameCodec.java:60`). Every wire party links it: the
production edge (`EdgeStreamClient.java:5,460,611`), the test client
(`EdgeProtocolClient.java:4,48,72`), the testkit encoders (byte-mirrors proven byte-equal to the
same codec). The RFC was transcribed FROM the code (codec landed `ca22214`; RFC §06 later in
`8a0ee2c`; `00-overview.md:12-13` "the code won"), so RFC-vs-code agreement cannot catch a
spec-vs-impl divergence. `sketch/WatchProtocolSketch.java` is a self-declared non-wired design
artifact. **The entire `0x02` WATCH surface (frames 0x0A–0x12) has zero clients** — production
`EdgeStreamClient` never sends a watch frame; only unit tests construct them.
Close: one client in a different language (Python/Rust, ~600–900 LOC for the `0x01` nine-frame
surface + CRC32C + reassembly, written from RFC §06 + `EdgeFrameGoldenBytes` with no access to
the Java codec), run in CI against a live `FanOutServer`; +~400 LOC for `0x02`. ~1.5–2.5
eng-weeks + ~1 week.

**2.6-2 conformance suite against a live server — GAP (UNPROVEN protocol).**
State: a genuinely strong live-server behavior contract exists —
`AbstractFanOutServerContract` over real TLSv1.3 mTLS sockets on both transports (subscribe,
verbatim ordered notify, cursor flow control, demotion→chunked snapshot→resume, quarantine,
protocol-violation teardown) — but its client shares the one codec, covers only `0x01`, and no
live test drives `0x02`/`0x03` over a socket. `EdgeFrameGoldenBytes` is generated FROM the codec
(`EdgeFrameGoldenBytesGenerator`) — a self-drift tripwire, not an independent oracle.
Close: the 2.6-1 client + a CI conformance job (golden vectors decoded by the foreign client;
every `0x01`+`0x02` frame round-tripped foreign↔Java against a live server; fail-closed cases).
~1 eng-week on top of the client.

**2.6-3 unknown-version/unknown-frame handling + frame fuzzing — MEETS-BAR.**
State: first-frame pin enforced (`ByteToEdgeFrameDecoder:62-69`; `EdgeFrameCodec.decode:614-630`);
unknown version→BAD_WIRE_VERSION (`:615-622`); unknown frame→FRAME_CORRUPT (`:631-637`); WATCH on
non-0x02→FRAME_CORRUPT (`:642-646`). Test: `EdgeFrameCodecPropertyTest` (round-trip,
truncation-at-every-boundary, single-bit→CRC, wrong-version-with-valid-CRC `:205`);
`EdgeFrameCodecFuzzTest` (jqwik, in CI `ci.yml:60`); V2/V3 golden cross-pin tests. (The S7
residual F-S7-FUZZ-1 is transport-layer slowloris, not frame-body fuzzing.)

**2.6-4 cross-binary downgrade interop — GAP.**
State: all downgrade checks are simulated within one codec (`decode(bytes, pin)`); no
new-binary×old-binary run exists and cannot until a first release artifact is cut
(`SnapshotWireCompatStubTest` is `@Disabled`: "no v0 snapshot fixture yet… we cannot honestly
assert backwards-compat").
Close: at the first release, check in the release's frame/snapshot vectors + a
decode-old-bytes-on-new-server CI leg (~1 day once a v0 exists); the foreign client then makes
new×old a real cross-binary matrix.

**2.6-5 wire-format freeze honesty (code vs RFC) — MEETS-BAR.**
State: five load-bearing frames spot-checked byte-for-byte against RFC §06/§02 — SUBSCRIBE
(+`acceptsFiltered` only under 0x03, `EdgeFrameCodec:297-316`), SUBSCRIBE_OK (`:318-325`),
NOTIFICATION with signed position and null-sig `-1` (`:346-369`), CURSOR_ACK (`:282,674`),
WATCH_EVENT per-shard `(gid,S)` (`:458-478`); AUTH_FAIL correctly not-a-frame
(`ErrorClose:393-398`). No divergence. Caveat: near-tautological, since the RFC is derived from
this code — it proves the doc is honest, not that the format is interoperable (that is 2.6-1/2).

---

## 2.7 Soak / endurance — 1 MEETS-BAR / 2 GAP

**The standard**: multi-day (72 h+) continuous run under load near capacity watching RSS/FD/GC/
latency drift, with fault events (elections, rotations, clock steps) injected during the run;
explicit leak pass/fail criteria; FoundationDB-scale simulated hours as the strong bar.

**2.7-1 duration at load on the release SHA — GAP.**
State: longest clean soak = **6 hours** at 300 w/s (deliberately below the ~800/s knee), on
commit `ce7d719` — not HEAD (`docs/archive/measurement/ec2-2026-06-30/04-soak.md:1-11`,
`00-environment.md:53-56`). The one 24 h attempt OOM'd at 3.45 h (box heap sizing, RR-112; the
6 h run proves the fix). Harnesses for 72h/7d/14d/30d exist (`perf/soak-72h.sh`, `burn-7d.sh`,
`longevity-30d.sh`, `shadow-14d.sh`) but have never been executed; no soak automation exists in
any workflow. The project's own `burn-in-contract.md:152-163` requires a clean 30-day window and
states bluntly (`:18-23`) that executed evidence is the 6-hour soak.
Close: ≥72 h at/near the knee on `012e213` (RSS/FD/GC/latency-drift criteria), ideally scheduled.
One long-lived box, ~$15–40.

**2.7-2 fault events during soak — GAP.**
State: the 6 h run was a steady fault-free burn — zero failover, cert rotation, log rotation, or
clock events during the window.
Close: fold periodic leader kills, one cert rotation, one log rotation, and a clock step into the
2.7-1 run. Included in that run's cost.

**2.7-3 explicit leak pass/fail criteria — MEETS-BAR.**
State: the 6 h run applied and reported concrete criteria — FD flat at 350, RSS spread 2.6%, heap
floor stable, GC growth linear 0.92%, 0/9,000 rejected — matching the
Trogdor/cassandra-stress-style "monotonic growth = failure" discipline. (Criteria exist and were
applied; the run's duration/faults are the GAPs above.)

---

## 2.8 Operability — 2 MEETS-BAR / 7 GAP

**The standard**: USE/RED metric coverage with a ratified alert set (etcd-mixin); leadership
transfer exposed (etcd move-leader); backup AND restore executed to a working cluster (etcd
snapshot restore, Postgres PITR); mixed-version rolling upgrade tested (etcd/CockroachDB);
runbooks executed via game days (SRE DiRT).

**2.8-1 every failure has a metric — GAP.**
State: covered — FanOutBuffer overflow (`fanout_buffer_dropped_total`, `FanOutBuffer.java:78`,
wired `ConfigdServer.java:1769`), ACL `load.failed`, `configd.snapshot.install.failed`
(`ConfigdMetrics.java:47`). Still SILENT (stderr-only, no counter): leader per-chunk snapshot
reject `RaftNode.java:2135-2138`; follower reassembly-cap refusal `:2484-2494` (docs say "monitor
matchIndex lag as a proxy", `known-limitations.md:369`); wire-version mismatch drop
(`known-limitations.md:384-386`); encoder drop (`:358-360,388`). A snapshot-size gauge is absent
(the burn-in contract itself asks for one, `:254`). The go/no-go's "alert on snapshot drop" still
does not exist.
Close: one server-transport drop-counter seam feeding those sites + a snapshot-size gauge +
`EdgeMetricsContractTest`/`MetricsWiringContractTest` coverage. Small; remember the full-reactor
anti-blind-dashboard gate.

**2.8-2 manual leadership transfer — MEETS-BAR.**
State: exposed via `POST /v1/admin/groups/{gid}/transfer-leadership?target=` — ADMIN-gated,
replay-protected (`AdminApiHandler.java:196-197,486-560,516,549`; core
`RaftNode.transferLeadership:672`). Test: `LeadershipTransferAdminTest`,
`DriverLeadershipAdminOwnerThreadTest`. (Corrects the stale "built but unwired" record.)

**2.8-3 leadership auto-balance across shards — GAP.**
State: no rebalancer exists (`ShardMap.java:18` epoch comment only; contract confirms manual
re-spread `:90`). The measured consequence stands: without spread leadership the sharded
aggregate collapses toward the single-group plateau (EC2 horizontal run).
Close: a periodic leader-balance driver targeting leader-count-per-node via the now-wired
transfer endpoint + a convergence test. Medium.

**2.8-4 backup/restore state-machine round-trip — MEETS-BAR.**
State/Test: `BackupRestoreRoundTripTest` — snapshot→restore into a fresh state machine,
key-for-key state-equal incl. overwrite/delete — CI-gated via `gate-6.sh:101` (`ci.yml:179`).

**2.8-5 operator restore tool executed — GAP.**
State: `ops/scripts/restore-snapshot.sh` is Kubernetes-only and has never been executed
end-to-end (the metal DR drill "could not use it… exercised the same recovery primitives
directly", `docs/archive/measurement/ec2-2026-06-30/02-dr-drills.md`).
Close: execute `restore-snapshot.sh` + `restore-conformance-check.sh` against a real snapshot
into a fresh cluster; capture evidence. Small compose/paid run.

**2.8-6 rolling / mixed-version upgrade — GAP.**
State: the Raft RPC codec has no version negotiation; a pre-chunking follower would install
chunk 0 as the whole snapshot — silent state corruption — so docs mandate "upgrade ALL nodes
together" (`known-limitations.md:361-386`, `deployer-must-know.md:159`). No test runs old+new
binaries concurrently; the repo itself states the gap (`gates/game-day-drill.sh:50-52` — "needs a
v0.2 artifact"). For a store meant to run continuously, upgrade-all-together as the only
mechanism is below the etcd/CockroachDB bar.
Close: a Hello/version-negotiation exchange on the Raft transport (the FrameCodec already carries
`WIRE_VERSION=0x02` — negotiation, not marking, is what's missing) + an old×new live
mixed-cluster snapshot-install test once a release artifact exists. Medium-large (wire change +
release-gated test).

**2.8-7 runbooks executed (game days) — GAP (breadth).**
State: 23 runbooks (8 `docs/operations/runbooks/`, 15 `ops/runbooks/`). CI-gated evidence: the
in-process `GameDayDrillTest` (gate-6) closes ONE fault→alert→runbook→recovery loop;
`e2e-compose-scenario.sh` (gate-3, CI) covers kill-leader/partition-edge/join-edge. The fuller
multi-node `game-day-drill.sh` is invoked by no workflow; no executed game-day evidence exists;
the DR drill was a one-off manual metal measurement, not a repeatable runbook gate.
Close: wire `game-day-drill.sh` (or a compose equivalent) + a scripted DR runbook into the
nightly with captured pass evidence. Medium.

**2.8-8 alert thresholds ratified — GAP.**
State: all SLO alert thresholds remain PROPOSED — "NOT load-validated"
(`ops/alerts/configd-slo-alerts.yaml:10-12`, 13 `PROPOSED:` markers). The promtool test proves
rules fire/quiet structurally; the numbers are unvalidated against real burn data.
Close: ratify from the 2.7-1 endurance run's measured distributions. Small once that data exists.

**2.8-9 health/readiness + graceful drain — GAP.**
State: `/health/ready` gates only on group-0 leadership (`ConfigdServer.java:743-748`) — at N>1 a
node that lost quorum on shards 1..N-1 still reports READY. `shutdown()` never flips readiness
before closing (`:1143-1180`); `NettyHttpApiServer.stop()` uses
`shutdownGracefully(quietPeriod=0, timeout=2s)` (`NettyHttpApiServer.java:220-223`) — no
LB-coordinated drain. Cert hot-reload exists (`:160,458`) but has no dedicated
rotate-without-drop test.
Close: shard-aware readiness + a draining flag on SIGTERM with a bounded quiet period + a
rotation-under-traffic test. Small.

---

## 2.9 Format / spec version-ability — 7 MEETS-BAR / 4 GAP

**The standard**: every persistent artifact self-identifies (Postgres pg_control version +
XLOG_PAGE_MAGIC; SQLite header; etcd WAL/snap versions; Vault keyring/barrier versions); a
newer/older reader fails loudly; the minimum holds even for "frozen" formats.

Cross-cutting: `IntegrityEnvelope.wrap()` always writes the MAC-covered
`[MAGIC:4][formatVersion:2][algId:1][reserved:1]` header — even keyless
(`IntegrityEnvelope:190-199`) — so envelope-wrapped artifacts inherit a real, tested version
marker.

| Format | Version marker | Mismatch test | Verdict |
|---|---|---|---|
| 2.9-1 WAL record | inner bare `[idx][term][cmd]` (`RaftLog.java:627-633`); versioned via envelope `WALE_MAGIC` | central `IntegrityEnvelopeTest:105` | **MEETS-BAR** (transitive) |
| 2.9-2 Snapshot on-disk | envelope `SNAP_MAGIC` + real ADR-0028 TLV trailer magic `0xC0FD7A11` (`ConfigStateMachine.java:559`, dispatch `:487`) | trailer-form dispatch tested; snapshot-specific bad-version ABSENT | **MEETS-BAR** (marker; thin per-artifact test) |
| 2.9-3 Snapshot cross-release load | — | `SnapshotWireCompatStubTest` is a `@Disabled` stub: "no v0 fixture… cannot honestly assert backwards-compat" | **GAP** — close at v0 cut: real fixture + un-disable + CI (~1 day, release-gated) |
| 2.9-4 IntegrityEnvelope | explicit `formatVersion=2` ≠ algId (`:76-90`), MAC-covered + in AAD, parse gate `:299-305` | `rolledFormatVersionThrows:105`, downgrade `:91` | **MEETS-BAR** (best in repo) |
| 2.9-5 raft.persistent_state | via envelope `STATE_MAGIC` (`DurableRaftState:157-163`) | forged term/votedFor refused | **MEETS-BAR** (transitive) |
| 2.9-6 Edge wire 0x01/02/03 | version byte at offset 4, first-frame pin | `wrongVersionWithValidCrcIsRejectedAsBadVersion:205`, cross-pin V2/V3 | **MEETS-BAR** |
| 2.9-7 Raft transport frame | `WIRE_VERSION=0x02` byte (`FrameCodec.java:67-73`) | `unknownWireVersionIsRejected:155`, `FrameCodecFuzzTest:147` | **MEETS-BAR** |
| 2.9-8 PolicySerializer (`_acl/`) | **NONE** — line-oriented text, no version/schema token (`PolicySerializer.java:31-59`) | unknown effect/cap rejected; version N/A | **GAP** — reserve `#!configd-acl v1` pragma + defined higher-version behavior; ~1–2 days, before freeze |
| 2.9-9 WatchCursor | **NONE** — no version, no shard-topology epoch (`WatchCursor.java:29-85`); resumed gids checked for membership only; cursor validity across an N change is undefined in code and RFC §02 | gid-spoof reject tested; reshard ABSENT | **GAP** — add a shard-map epoch + reject-on-mismatch + a resharding negative test; ~3–5 days (wire bump) |
| 2.9-10 KMS WrappedKey | **NONE** persisted — in-memory record; `KeyId.version` is a rotation term, not a format version (`WrappedKey.java:29`, `KeyId.java:18-28`) | ABSENT (nothing serialized) | **GAP (by absence)** — freeze a versioned on-disk envelope BEFORE any persisting KMS provider ships; ~2–3 days, gated on 2.3-6 |
| 2.9-11 CommandCodec + snapshot-chunk body | type byte is a discriminant not a version (`CommandCodec.java:17-20`); chunk body lead u64 is DATA seq, not a format version (`EdgeSnapshotCodec.java:81`) | grammar tests only | **GAP (transitively covered)** — either document "never standalone; versioned by carrier" + an assert, or add a version byte; ~1 day each, explicit decision required before freeze |

---

## 2.10 Resource bounds — 5 MEETS-BAR / 4 GAP

**The standard**: request/message sizes bounded and rejected cleanly (etcd --max-request-bytes);
state growth quota'd with an alarm; connection/stream ceilings; bounded queues with backpressure;
reassembly caps (the HTTP/2 CVE class); and behavior AT each limit tested.

**2.10-1 snapshot chunking driven at a real >4 MiB payload — GAP.**
State: chunking is real — default chunk 1 MiB, per-chunk cap 4 MiB (`RaftNode.java:119,127`),
reassembly cap 512 MiB enforced (`:145,2483`). But **every** chunking test forces
`setSnapshotChunkBytesForTest(2)` (2-byte chunks) over 10–12-byte blobs
(`ChunkedInstallSnapshotTest:504,534,577`) — the largest snapshot payload in any install test is
**12 bytes**. The 1 MiB-chunk/multi-MiB regime the feature was built for has never been driven
end-to-end; the edge analog (`BootstrapSnapshotBackpressureTest`) is also forced-small (~48 KiB).
Close: one test proposing enough real state to force a genuine multi-MiB snapshot at the default
chunk size, asserting byte-identical install across the wire, + a cap-boundary cell with real
bytes. In-process, no hardware. (Explicitly the "forced-small chunks do not qualify" case from
the charter.)

**2.10-2 reassembly memory bound — MEETS-BAR.**
State/Test: cap enforced at `RaftNode.java:2483`;
`ChunkedInstallSnapshotTest.reassemblyExceedingCapFailsClosed:221` (forced 6-byte cap — the
enforcement path is proven; realism rides 2.10-1). Heap-sizing guidance exists (PR #51).

**2.10-3 FanOutBuffer bound + overflow — MEETS-BAR.**
State/Test: 10,000-slot drop-oldest ring, evict-before-overwrite (ADR-0036), `lastEvictedSeq`
watermark, `droppedTotal` metric (`FanOutBuffer.java:17,45-96`); `FanOutBufferTest`
(`fillExactlyToCapacity:207`, `capacityOneEvictsOnEveryAppend:250`), `FanOutBufferRaceTest`,
`CommitNotificationSourceTest.overflowIncrementsDropCountAndStaleCursorGetsGap:105`.

**2.10-4 slow-consumer handling — MEETS-BAR (was a live defect; fixed and regression-tested).**
State: the spurious gap-quarantine of a caught-up edge (which blocked the first INV-S2
measurement) was root-caused and fixed on main — `30644f7` (PR #47) distinguishes a genuine
retention fall-off from a transient lock-free read race before counting a demotion.
Test: `FanOutSessionCoreGapClassificationTest`; plus `SlowConsumerQuarantineTransitionTest`,
`QuarantineReBootstrapTest`. INV-S2 then re-measured decisively GREEN (gate7-final). The lane
that flagged this as open was working from the pre-fix Gate-4 README — reconciled in §5.

**2.10-5 watch-count limits tested — GAP.**
State: bounds exist — `MAX_LIVE_WATCHES_PER_CONNECTION=1024`
(`FanOutConnectionDriver.java:154,569`), `MAX_WATCH_IDS_PER_CONNECTION=16384` (`:160,574`) — but
**no test references either constant or its error path**.
Close: two boundary tests (at-limit accepted, over-limit rejected cleanly). Small.

**2.10-6 connection / frame / request caps — MEETS-BAR.**
State/Test: Raft inbound connections capped
(`AbstractRaftTransportContract.inboundConnectionsAreCappedAndExcessIsRefused:794`); edge
sessions capped (`AbstractFanOutServerContract.connectionsBeyondMaxSessionsAreRefusedAndCounted:584`);
HTTP body 1 MiB → 413-not-buffered (`NettyHttpApiServerHardeningTest:130`); Raft frame 16 MiB
tested exactly at the limit (`FrameCodecEncoderBoundsTest:42`); snapshot blob cap tested at MAX+1
(`RaftMessageCodecTest:189`). (Caps are exercised at forced-small values where noted — the
enforcement paths are proven.)

**2.10-7 value-size at-limit + write-gate test integrity — GAP (narrow).**
State: 1 MiB value cap rejects above (`CommandCodecPropertyTest.putWithOversizedValueLengthIsRejected:117`)
but accept-at-exactly-1-MiB is untested (largest tested value 10 KiB), and the API-layer gate
`ConfigWriteService.java:238` is bypassed by a stubbed validator in its only test
(`ConfigWriteServiceTest.java:78-83`).
Close: an at-limit accept test + un-stub the validator in one real-path test. Small.

**2.10-8 write admission control default-OFF — GAP.**
State: `maxInflightProposals` defaults to 0 = disabled (`ConfigdServer.java:2088-2118`); the
measured stable-shed configuration (460→848/s under flood) requires a `-D` override; out of the
box a flood is bounded only by the 10,000/s rate limiter and the 1024 `maxPendingProposals`
queue (`gates/gate-5.sh:133-138`). No ship gate mandates enabling it.
Close: default it on (tuned) or add it to the secure-by-config runsheet gates + a default-config
flood test. Small. (Related record correction: the "FlowController docs=16 vs code=10"
discrepancy does not exist — `maxInflightAppends`=10 matches its docs and is tested at the real
default (`Rr103InflightWindowRecoveryTest:63`); the "16" was the admission-semaphore experiment
mislabeled in an archived register row; the `FlowController` class itself is orphan dead code.)

**2.10-9 end-to-end backpressure memory bound — MEETS-BAR.**
State/Test: bounded on every hop — subscriber outbound queue property-tested
(`SubscriberQueueBoundTest`: unacked NOTIFY ≤ queueFrames), CURSOR_ACK flow control exercised in
the live contract, proposal queue bounded (1024), FanOutBuffer drop-oldest.

---

## 3. The complete GAP list — 36 items, grouped by the work they actually require

### Class A — frozen-format / durability-kernel work (must land before any format freeze)
| # | Gap | Size |
|---|-----|------|
| A1 | 2.3-2 No truncation/rollback anchor in the permanent envelope format — durable authenticated high-water mark + anti-rollback recovery gate + crash-matrix tests | ~150–300 LOC on the persist-before-ack path; highest risk item |
| A2 | 2.3-4 Key rotation unreachable (boot hardcodes term=1); documented rotation path destroys encrypted data | ~120–200 LOC + 1 e2e test |
| A3 | 2.9-8 PolicySerializer unversioned (frozen operator grammar) | ~1–2 days |
| A4 | 2.9-9 WatchCursor has no shard-topology epoch; cursor validity across N change undefined | ~3–5 days (wire bump) |
| A5 | 2.9-10 WrappedKey persisted format unspecified — must be frozen versioned before any real KMS provider ships | ~2–3 days, gated on B4 |
| A6 | 2.9-11 CommandCodec / chunk-body self-versioning — explicit decision (document-carrier-versioned + assert, or add byte) | ~1 day each |

### Class B — missing features vs the bar
| # | Gap | Size |
|---|-----|------|
| B1 | 2.8-6 Rolling upgrade impossible (no Raft transport negotiation; chunk-0 corruption case) | medium-large; wire change + release-gated test |
| B2 | 2.8-3 Leadership auto-balance absent (sharded aggregate collapses without manual spread) | medium |
| B3 | 2.4-3 LIST capability has no enforcement point (ship endpoint or remove) | small |
| B4 | 2.3-6 No real KMS provider; default custody = key co-derivation, not an unseal boundary | ~1 module, ~300 LOC + KMS-emulator test |
| B5 | 2.4-7 Not secure-by-default (auth OFF + 0.0.0.0 bind out of the box) | small code + docs |
| B6 | 2.10-8 Write admission control default-OFF | small |
| B7 | 2.8-9 Readiness shard-blind at N>1; no drain-on-SIGTERM | small |

### Class C — the protocol is unproven
| # | Gap | Size |
|---|-----|------|
| C1 | 2.6-1 Zero independent conforming clients; 0x02 WATCH surface has no client at all | ~1.5–2.5 eng-weeks (0x01) + ~1 week (0x02) |
| C2 | 2.6-2 No conformance suite against a live server | ~1 eng-week atop C1 |
| C3 | §2.5 cells 18–20: 0x03 filtered path and mixed-version rejects never run over a live socket | ~2–3 days on the existing harness |
| C4 | 2.6-4 Cross-binary downgrade untestable until a v0 artifact exists | ~1 day, release-gated |

### Class D — tests that must exist (no design risk)
| # | Gap | Size |
|---|-----|------|
| D1 | 2.3-7 Encryption-ON composed test (collapses §2.5 cells 1–4, 6–8) | 1 test |
| D2 | §2.5 cell 12: N>1 × leader failover never tested (multi-node groups) | medium (harness assumes single-node groups) |
| D3 | 2.10-1 Real >4 MiB chunked snapshot end-to-end (current max tested payload: 12 bytes) | 1–2 tests |
| D4 | 2.1-6 fsyncgate: WAL-fsync-throw against a live node + fail-closed policy | 1 test file + small policy change |
| D5 | 2.10-5 Watch-count limits untested | 2 boundary tests |
| D6 | 2.10-7 Value-cap at-limit accept + un-stubbed write-gate test | small |
| D7 | 2.9-3 Snapshot cross-release fixture (disabled stub) | ~1 day, release-gated |
| D8 | 2.1-7 fsync validation on target hardware (runsheet probe) | small |

### Class E — paid measurement / endurance
| # | Gap | Size |
|---|-----|------|
| E1 | 2.2-4 No faulted-linz on HEAD `012e213` — **✅ CLOSED by E1 (2026-07-10) on `299ba14`; standing CI job now runs the real matrix** | done |
| E2 | 2.2-2 Nemesis breadth (clock skew, pauses, combined faults) — **✅ CLOSED by E1** (asymmetric/partial partitions = recorded netns follow-up) | done |
| E3 | 2.2-3 15-second smoke vs Jepsen-grade hours — **✅ CLOSED by E1** (adversarial matrix, N=3/5 × postures, many seeds) | done |
| E4 | 2.2-5 No linearizability check at N>1 shards — **✅ CLOSED by E1** (multi-shard posture; per-key check = per-shard linz) | done |
| E5 | 2.7-1 Soak 6 h vs ≥72 h at-load on release SHA | ~$15–40, days of wall-clock |
| E6 | 2.7-2 No fault events during soak | folded into E5 |
| E7 | 2.8-5 Operator restore tool never executed | small compose/paid run |
| E8 | 2.8-7 Game-day/DR runbooks lack repeatable executed evidence | medium |
| E9 | 2.8-8 All alert thresholds still PROPOSED | small, after E5 |

### Class F — observability and docs
| # | Gap | Size |
|---|-----|------|
| F1 | 2.8-1 Silent drop paths: snapshot chunk reject, reassembly-cap refuse, wire-version drop, encoder drop; no snapshot-size gauge | small |
| F2 | 2.4-1 Threat model stale (no KMS/confidentiality, no policy plane, no edge-tier boundary) | ~1 day docs |
| F3 | 2.3-5 Zeroization platform boundary (no mlock/core-dump control) — deploy controls + tested boundary | small + docs |
| F4 | Already-owed doc fixes: `docs/v2-backlog.md:7` truncation overclaim; `known-limitations.md` §1 trailing-truncation bullet | trivial |

---

## 4. Reading the totals honestly

- The **30 MEETS-BAR** items are real: they have named tests at file:line, most CI-gated, several
  adversarial (red-proofs, differential fuzz oracles, golden fixtures). This is not a
  green-wash core — the durability and authorization kernels genuinely meet the cited bars.
- The **36 GAPs** are not uniform. Six (Class A) are frozen-format kernel items where "ship then
  fix" is structurally impossible — the format freezes with or without them. Two (A1, A2) are the
  highest-stakes items in the repo. Roughly a dozen (Classes D, F) are cheap tests/metrics that
  exist as designs already. The remainder are real engineering (protocol client, rolling
  upgrade, auto-balance) or paid time (Jepsen-grade runs, 72 h soak).
- The charter's named suspicions all confirmed: encryption-ON × everything = zero test
  intersection; the protocol has zero independent clients; soak is 6 h against a 30-day
  contract; the >4 MiB snapshot fix was validated only with 2-byte forced chunks; and the
  faulted-linearizability evidence does not cover the shipped bytes.
- Two genuinely new findings beyond the charter's list: **N>1 has never been tested through a
  leader failover** (every multi-shard test uses single-node groups), and **the documented key
  rotation procedure would destroy an encrypted node's data** (term hardcoded at boot; rotateTo
  dead code).

## 5. Coordinator reconciliation notes (lane claims corrected against source)

1. **Slow-consumer governor**: the reliability lane reported the spurious gap-quarantine as an
   open live finding (from the Gate-4 README). Corrected: fixed on main in `30644f7` (PR #47)
   with `FanOutSessionCoreGapClassificationTest`; INV-S2 re-measured GREEN in gate7-final.
   Recorded as MEETS-BAR (2.10-4); the SHA-staleness residual is counted once in 2.2-4.
2. **fsync-failure**: the reliability lane said `failNextSyncs` is never used; the inventory lane
   found it used for the snapshot fsync-LIE detection test. Both stand: the snapshot-lie path is
   tested; the WAL group-commit fsync-throw path and the fail-closed policy are not (2.1-6,
   narrowed).
3. **FlowController "docs=16 vs code=10"** (carried in prior session notes as an unfixed
   discrepancy): does not exist. `maxInflightAppends`=10 matches its docs and is tested at the
   real default; the "16" was the `maxInflightProposals` admission experiment mislabeled in an
   archived register row; `FlowController` is orphan dead code.
4. **Stale prior records corrected by this assessment**: `transferLeadership` is now wired to an
   ADMIN-gated endpoint and tested (was "built but unwired"); legacy SUBSCRIBE is now authorized
   with tests (the drive-to-green recommendation was executed); the AclService "no call sites"
   javadoc (`AclService.java:654,724`) is stale — the watch authorizer is live.

## 6. STOP — for the operator

This assessment surfaces the standard-vs-state truth; it does not decide to ship, defer, or
re-scope. The decisions only the operator can make:

1. Whether the two frozen-format kernel gaps (A1 truncation anchor, A2 rotation reachability)
   block any tag that freezes the envelope format — under this charter's rules they do, because
   the format is permanent.
2. Whether the protocol ships as an internal implementation detail (edge-node only, RFC
   descriptive) or as a claimed public protocol — the latter requires Class C.
3. How much of Class E (paid Jepsen-grade + 72 h soak + executed drills) constitutes sufficient
   empirical evidence, and on which SHA the release measurement set must be re-captured.
4. Sequencing: Classes A + D1–D6 + F are box-local and immediately actionable; E requires paid
   hardware; B1/C1 are the long poles.

*Assessment complete. Read-only except this file. Dev box left up per charter.*
