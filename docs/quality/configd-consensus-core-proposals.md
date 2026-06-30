# configd-consensus-core — Idiomatic-Java Quality Pass (REVIEW & PROPOSE ONLY)

**Outcome: ZERO edits applied.** Every one of the 28 source files in this module is on the §2
NO-TOUCH list (the Raft consensus state machine, the serialized RPC message types, the heartbeat
coalescing, and the on-disk/wire formats). The module is essentially all of §2, exactly as the
assignment anticipated. The code is already pristine, idiomatic Java 25 and carries
measured/proven properties (election/heartbeat timing, single-owner/lock-free invariants, frozen
WAL/snapshot/state byte layouts, a 70 %-floor PIT mutation gate) that the EC2 measurement must run
on unchanged.

This document records the review so a divergence-analyst and a future maintainer can see what was
examined and why nothing was changed. **None of the items below are recommended for application in
this pass** — they are either deliberate design, or cosmetic changes that would be formatting-only
churn inside frozen consensus/wire files (forbidden by the brief).

Scope of negative findings (verified absent across the whole module): no unused imports, no missing
`@Override`, no raw types / unchecked warnings, no redundant casts, no `printStackTrace`, no empty or
over-broad swallowing catches, no `size()==0`, no boxing constructors, no resource leaks
(`DurableRaftState`/`RaftLog` own no `Closeable` — durability goes through the injected `Storage`).

---

## A. Reviewed and sound — NO action (deliberate, correct design)

These looked like candidates a mechanical idiom-sweep might "fix"; each is intentional and must be
left exactly as-is. Listed so a divergence-analyst does not re-flag them.

1. **`catch (Throwable t)` callback isolation** — `RaftNode` lines ~972, ~1129, ~1816. Catching
   `Throwable` around user-supplied callbacks (`readReady`, `commitOutcome`) is the correct pattern:
   a buggy listener must never kill the single owner/tick thread. Not an over-broad-catch defect.

2. **Zero-copy `byte[]` in value types** — `LogEntry.command()`, `SnapshotState.data()`,
   `InstallSnapshotRequest.data`/`clusterConfigData`. No defensive array copy on input or accessor.
   This is a deliberate allocation-profile choice on the consensus hot path; the arrays are treated
   as immutable by convention. Correctness of value semantics is preserved where it matters — each
   such record overrides `equals`/`hashCode`/`toString` with `Arrays.equals`/`Arrays.hashCode`
   (records' default array identity-equality would be a Log-Matching bug). Adding defensive copies
   would change the measured allocation profile → must not.

3. **`ClusterConfig.peersCache` (lazy `HashMap` via `computeIfAbsent`)** — a mutable cache on a type
   whose `equals`/`hashCode` drive consensus/quorum decisions. The threading hazard is *known and
   contained*: `RaftNode.clusterConfig()` (line ~1243) is explicitly owner-thread-only **because**
   of this cache, and off-owner readers use the published immutable `monitorView()` snapshot
   instead. Changing it to a concurrent map (or eager compute) would alter allocation/behavior and
   touch a consensus-decision type → must not.

4. **`appendNoSync` + single trailing `syncWal()` (group commit), persist-before-truncate, and the
   `durableIndex` commit-quorum gate** — `RaftLog.appendEntries`/`compact`/`persistSnapshot`,
   `RaftNode.maybeAdvanceCommitIndex`/`flushDurable`. These encode the durability/crash-safety
   ordering (RR-003, S7.5). The ordering *is* the correctness; no idiomatic rewrite is safe here.

5. **`System.err.println` diagnostics** are intentional given the module's dependency set — see §C.1.

---

## B. Cosmetic style nits — observed, **NOT recommended to apply** (§2 + formatting-only churn)

All are byte-identical at the `.class` level (an unused-vs-used import / FQN-vs-simple-name choice
does not change emitted bytecode), but every one sits inside a §2-listed file and is precisely the
"formatting-only churn" the brief forbids. Recorded only so the inconsistency is visible; value ≈ 0.

| Location | Observation | Idiomatic form |
|---|---|---|
| `RaftNode.java:321` | `new java.util.HashSet<>(config.peers())` uses the FQN even though `import java.util.HashSet;` is present (line 8) and the simple name is used elsewhere (lines 750, 1379). | `new HashSet<>(config.peers())` |
| `ClusterConfig.java:105, 147` | `new java.util.HashSet<>(...)` written fully-qualified; `HashSet` is not imported (other `java.util.*` types are). | add `import java.util.HashSet;`, use `HashSet` |
| `RaftLog.java:144` | `java.util.Objects.requireNonNull(integrity, "integrity")` — `Objects` is not imported and is used only here. | add `import java.util.Objects;`, use `Objects.requireNonNull` |
| `RaftNode.java` (189, 1009, 1125; 273, 292; 1263, 1301, 1324) | Inline FQNs `java.util.function.Consumer`, `io.configd.common.IntegrityEnvelope`, `java.nio.ByteBuffer`/`BufferUnderflowException` instead of imports. | import the types (cosmetic only) |
| `RaftLog.java:752` | `catch (RuntimeException e)` — `e` is unused (intentional defensive parse of legacy bytes; tamper already propagated upstream via `IntegrityException`). | `catch (RuntimeException ignored)` for intent |

**Recommendation:** do **not** apply any of B. If a future, non-frozen cleanup ever touches these
files for a substantive reason, fold these in opportunistically; never as a standalone diff (it would
re-touch frozen WAL/wire/consensus sources and risk the PIT mutation baseline for no behavioral gain).

---

## C. Deferred — design discussion, out of scope for a quality pass (NOT byte-identical)

1. **Consensus-core has no logging seam; diagnostics go to `System.err.println`** (5 sites:
   `RaftNode` ~974, ~1131, ~1817 callback-threw; ~2024, ~2075 codec-rejected drop). This is a
   deliberate consequence of the module's dependency set (pom: only `configd-common`,
   `configd-transport`, `agrona`, `jctools` — no SLF4J/logger). Converting to a logger would (a)
   add a dependency and (b) change the output sink — **not** byte-identical, so out of scope for a
   quality pass. If desired later, the idiomatic fit here is the project's own **metrics-sink SAM +
   `NOOP` sentinel** pattern (a tiny `RaftDiagnostics` interface the server wires, mirroring
   `StateMachineMetrics`/`FanOutMetrics`) rather than dragging an observability dep into a leaf
   consensus module. Design change — propose to the module owner, do not land in a quality pass.

2. **Cross-module duplicated constant** — `RaftNode.propose()` (line ~582) hardcodes
   `MAX_COMMAND_LEN = 1 * 1024 * 1024` with a comment that it **"MUST equal
   RaftMessageCodec.MAX_COMMAND_LEN"** (which lives in `configd-server`, a module this one cannot
   import). The code itself flags this as an "iter-4 cleanup." It is a real maintainability smell (a
   silent wire-limit skew if one side changes), but resolving it is a cross-module API/ownership
   decision (shared constant home / SPI), not an idiomatic nit — out of scope here.

---

## D. Verification

- `./mvnw -q -pl configd-consensus-core -am test-compile` → **PASS** (exit 0).
- No edits applied, so the named oracle gates (`SnapshotWireCompatStubTest`, `WalWireCompatStubTest`,
  `RaftNodeTest`, `InvariantCallSiteTest`) are unchanged from the clean checkout and were not re-run
  (the brief: compile suffices when nothing is applied; the 2-vCPU box must not run the heavy
  sim/property suite needlessly). They remain the gates any future edit to this module must clear.
