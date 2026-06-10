# Review — RR-003 fix (restart-after-compaction silent data loss) + second-agent verification

- **Reviewer:** review-architect (Session 2)
- **Date:** 2026-06-10
- **Scope:** independent review of the RR-003 (P0) fix — commits `e3a1e98` (crash-recovery matrix
  + `CrashStorage`/`KvStateMachine` harness + pre-fix capture), `5c2ca55` (fix), `1513bef`
  (register/proof + cross-notes).
- **Authoritative artifacts:** `docs/decisions/adr-0028-snapshot-on-disk-format.md`,
  `docs/session-2/captures/rr-003-prefix-failure.txt`, readiness-register RR-003 row.

---

## Verdict: **APPROVE.** Nothing blocks the RESOLVED flip.

The durable-prefix invariant is implemented correctly (persist-before-truncate on both the
leader `triggerSnapshot` and follower `handleInstallSnapshot` paths; atomic blob write; loud
gap-detection at both fire sites). The crash matrix is strong and non-vacuous, the storage
fidelity model is faithful to (and in one narrow place stricter than) real `FileStorage`, and
the discriminating suite re-runs green. Three non-blocking NOTES below (N-1 first-create
dir-fsync; N-2 RR-064 envelope scope; N-3 cosmetic).

---

## Finding 2 (the load-bearing question): does production `FileStorage` dir-fsync where `CrashStorage` assumes it?

**Answer: YES on the path that matters — `CrashStorage` is faithful, and in one narrow place
deliberately stricter than reality (acceptable). No new production durability bug on the
snapshot-persist path.**

Verified against `configd-common/src/main/java/io/configd/common/FileStorage.java`:

| Op | FileStorage actual | CrashStorage model | Verdict |
|---|---|---|---|
| `put` (snapshot blob, meta) | temp → `force(true)` → atomic `Files.move` → **`sync()` dir-fsync** (line 70) | self-durable immediately | **faithful** — the blob IS on the platter when `persistSnapshot` returns |
| `appendToLog` (WAL frame) | `force(true)` on the file channel; **no dir-fsync** | self-durable immediately | faithful for the steady state; **stricter than reality for the first-ever WAL-file create** (see N-1) |
| `truncateLog` | `Files.deleteIfExists`; **no fsync, no dir-sync** | rename-style, pending until `sync()` | **faithful** |
| `renameLog` | `Files.move(ATOMIC_MOVE)`; **no dir-fsync** | rename-style, pending until `sync()` | **faithful** |

The headline question — *does `FileStorage.put` fsync the directory after the rename* — is **yes**
(`FileStorage.java:70`). So the snapshot blob persisted by `RaftLog.persistSnapshot`
(`storage.put(SNAPSHOT_BLOB_KEY, …)`) is genuinely durable on return, exactly as the model and
ADR-0028 claim. There is **no missing-dir-fsync durability bug on the snapshot-persist path** —
this is case (a), CrashStorage matching reality, not case (b).

`CrashStorage`'s rename-style modeling of `truncateLog`/`renameLog` (durable only after the next
`sync()`) is also exactly right: `RaftLog.compact` calls `rewriteWal()` then `storage.put(meta)`
then **`storage.sync()`** (`RaftLog.java:516-521`), and `truncateFrom` calls `sync()` after
`rewriteWal()` (`:402-408`) — those `sync()` calls are what make the rename durable, which is the
RR-086 hazard the harness is built to expose. Deleting them is observable in this harness and
silent in a FileStorage wrapper / in-memory map (an atomic rename is visible to a same-dir reopen
regardless of dir-fsync). The model's reason-for-existence is sound.

## Finding 1 — the crash matrix: vacuity + per-cell COMMITTED-equality

**Non-vacuous.** 4 `CrashPoint`s × 60 seeds (`configd.rr003.seeds`, default 60) = 240 cells, plus
the 3 named single-cell tests + torn-tail + gap-detection = 6 `@Test`s. Each cell asserts
`expected.equals(actual)` where `expected` is the **full** committed key/value map accumulated
across every `commitPut` (first batch 1–8 writes + post-snapshot suffix 1–5 writes) and `actual`
is `KvStateMachine.snapshotState()` after a genuine restart+re-election. This is exact semantic
equality on the **reconstructed** state machine, not a counter or a superset — a dropped prefix
key fails the cell (the pre-fix `expected={k0,k1,post0} actual={post0}` evidence confirms the
assertion bites).

I specifically hunted the silent-pass risk in `takeSnapshotCrashingAt` (the SeedSweepTest
failure mode): for `BEFORE_PERSIST`/`AFTER_PERSIST_BEFORE_TRUNCATE` the crash is armed
semantically and then `triggerSnapshot()` is called, returning `storage.didCrash()`. **Post-fix
these triggers genuinely fire** — `persistSnapshot` calls `storage.put(SNAPSHOT_KEY,…)`, so
`crashBeforeKeyPut(SNAPSHOT_KEY)` / `crashAfterKeyDurable(SNAPSHOT_KEY)` both hit `fireArmedCrash`
(confirmed by reading `CrashStorage.put`). Even if a trigger did NOT fire, the code falls through
to a second batch + clean restart and STILL asserts full recovery — so a non-firing trigger
degrades to a weaker-but-still-real assertion, never a no-assert pass. `AFTER_TRUNCATE`/`NONE`
arm unconditionally. `matrixHoldsAcrossSeeds` accumulates every violation and `fail`s with the
count, so a single losing cell fails the suite.

Each cell asserts recovered-SM == pre-crash COMMITTED state: `runMatrixSeedCollecting` builds
`expected` only from accepted `commitPut`s (single-node propose commits immediately), captures
`preSnapshotApplied >= firstBatch` (line 293, guards against a no-op snapshot), then compares
after `recoveredView()` + re-election (the production recovery path). Correct.

## Finding 3 — gap-detection cannot false-fire; both sites are loud-in-test / metric+log-in-prod

`RaftNode` ctor seeds `lastApplied = log.snapshotIndex()` **only inside `if (log.snapshotIndex() >
0)`** and **after** the `durable_prefix_no_gap` check. Walking the four recovery paths:
- **Clean boot** (no snapshot): `snapshotIndex == 0` → block skipped, `lastApplied` stays 0,
  no fire, no false seed.
- **Boot-with-snapshot** (full compaction): `recovered != null` (blob accepted because
  `blob.lastIncludedIndex == snapshotIndex && snapshotIndex > 0`) → check passes → restore SM,
  seed `lastApplied = snapshotIndex`. No fire.
- **Boot-with-snapshot+suffix**: same, plus `applyCommitted` replays `(snapshotIndex,
  commitIndex]`. Because `lastApplied` starts at `snapshotIndex`, indices `≤ snapshotIndex` are
  never walked, so `entryAt` returning null for a compacted-and-restored index **never reaches**
  the applyCommitted fire site — the precise reason it cannot false-fire on a healthy node.
- **InstallSnapshot-then-restart**: follower persists the installed blob before compaction, so on
  restart the boundary has matching bytes → recovered != null → no fire.

The fire only triggers on a genuine hole: a boundary with no bytes (ctor) or a committed index
with no entry and no covering snapshot (applyCommitted). Both call
`invariantChecker.check("durable_prefix_no_gap", …)`; with the test's `THROWING` checker they
throw (verified green via `gapDetectionFiresWhenSnapshotBlobUnrecoverable`), and per the register
they route through `InvariantMonitor` to a metric + SEVERE log under prod's fail-open monitor.
The applyCommitted site `break`s without advancing `lastApplied` — killing the original
silent-skip amplifier (it does not pretend the entry applied).

## Finding 4 — persist-before-truncate ordering + atomic blob + old-prefix-not-destroyed-early

- **`triggerSnapshot`** (`RaftNode.java:436-438`): `persistSnapshot` (durable on return) → set RAM
  field → `compact` (which `rewriteWal`-deletes the prefix). Persist precedes truncate. ✓
- **Follower `handleInstallSnapshot`** (`:1849-1864`): `restoreSnapshot` → `persistSnapshot` →
  `compact`. Same ordering on the follower path (the path the ADR review flagged as equally
  exposed). ✓
- **fsync before rename / atomicity:** the blob goes through `FileStorage.put` (temp →
  `force(true)` → atomic rename → dir-`sync()`), so a crash mid-persist leaves either the OLD
  complete blob or the NEW complete blob — never a torn one. `readSnapshotBlob` additionally
  treats any short/structurally-invalid blob as absent (WAL stays authoritative). ✓
- **Old prefix not destroyed before the new blob is durable:** `persistSnapshot` returns only
  once the new blob is fully fsynced; `compact`/`rewriteWal` (the WAL-prefix delete) runs strictly
  after. And `rewriteWal` itself writes to `WAL_TMP` then atomic-renames (never deletes the live
  WAL first), so there is no instant where neither the WAL prefix nor a complete blob covers
  `[1..S]`. The recovery rule (accept blob iff `blob.lastIncludedIndex == snapshotIndex`)
  correctly ignores an ahead-of-WAL blob from a crash *between* persist and rewrite, letting the
  intact full WAL replay. ✓

## Finding 5 — pre-fix capture internal consistency

Consistent. `rr-003-prefix-failure.txt` claims 180/240 cells lose data; the 60
AFTER_PERSIST_BEFORE_TRUNCATE cells pass pre-fix (the un-truncated WAL is the safety net) →
3 × 60 = 180 losing cells — arithmetic checks out. The named-cell excerpt shows 3 of 4 fail
(AFTER_PERSIST passes, by WAL safety net), matching the `Tests run: 4, Failures: 3` line. The
**NONE** cell failing (a clean snapshot + restart with NO crash, `expected={k0,post0}
actual={post0}`) is the purest proof that persistence was simply absent pre-fix — exactly the
defect (RAM-only snapshot, never restored, applyCommitted silently skips the compacted prefix).
The pre-fix tree `ccce38d` (RR-004/RR-010 present, RR-003 absent) matches the commit graph.

## Finding 6 — mutation-revert pairs

All three named tests exist at HEAD and read as Appendix B claims:
- (i) persist-AFTER-compact ordering revert → `recoversWhenCrashedBeforeSnapshotPersist` (and
  `matrixHoldsAcrossSeeds`): with compact deleting the WAL first, a crash-before-blob loses the
  prefix.
- (ii) delete the `persistSnapshot` call → `recoversAfterSnapshotAndWalTruncate`: snapshot never
  durable, AFTER_TRUNCATE recovers an empty store.
- (iii) replace the gap check with a silent advance →
  `gapDetectionFiresWhenSnapshotBlobUnrecoverable` asserts `thrown != null` and the message
  contains `durable_prefix_no_gap` — a silent skip yields no throw → test fails.
These are genuine discriminators (each ties to a distinct fix element), not cosmetic.

## Finding 8 — ADR-0028 layering + RR-064 wire-compat trigger

**Layering claim is SOUND.** ADR-0028 owns the **state-machine-internal** snapshot byte format
(sequence-counter + length-prefixed entries + TLV-with-magic trailer) — the `data` field of
`SnapshotState`, produced/consumed by `ConfigStateMachine.snapshot()/restoreSnapshot()`. The
RR-003 fix adds a **new RaftLog-level at-rest envelope** (`serializeSnapshot`, key
`raft-log.snapshot`) framing `[index][term][dataLen][data][cfgLen][cfg]`. I confirmed
`RaftLog.serializeSnapshot` treats `s.data()` as an **opaque block** (`putInt(len); put(data)`) —
it never parses ADR-0028's format. Clean two-layer separation; the in-code comment
(`RaftLog.java:565-574`) states it correctly.

**N-2 (note, not a blocker):** the fix DOES introduce a genuinely new durable on-disk format (the
`raft-log.snapshot` envelope). RR-064's `SnapshotWireCompatStubTest` is still correctly
`@Disabled` — its trigger is "first version bump," and since no release has been cut there is no
N-1 reader to be compatible with yet, so the trigger is **not** live. But RR-064's *scope* should
now explicitly cover the new RaftLog envelope (not only the SM-internal ADR-0028 format), so that
when the first version bump lands, both layers get a golden-bytes fixture. This is a scope note on
the (P3) RR-064 row, not an RR-003 defect.

## Re-run evidence (second-agent verification)

After `install -DskipTests` of consensus-core + deps and `clean` on the module under test (per the
known stale-artifact false-green trap); concurrent transport/server agent active, so runs kept
targeted:
- `SnapshotCrashRecoveryTest` (configd-consensus-core, full 240-cell matrix + 5 edge cells):
  **Tests run: 6, Failures: 0, 1.845 s**
- `CommitOutcomeSeamTest` (configd-consensus-core — the A1-seam interaction I flagged as F-5 in
  the RR-004 review): **Tests run: 5, Failures: 0, 1.187 s.** Independently confirmed the
  interaction is benign: `decideCommitOutcome` resolves a post-snapshot index with no recorded
  seq to `indeterminateLocally`, and `appliedSeqByIndex` is a fresh per-instance map (empty after
  restart), so its non-pruning-by-compaction cannot leak a stale seq across recovery.

## Non-blocking notes

- **N-1 (pre-existing, narrow, NOT introduced by RR-003):** `FileStorage.appendToLog` `force(true)`s
  the file channel but does NOT dir-fsync, so the **first-ever** creation of `raft-log.wal` has a
  fs-dependent window where the appended bytes are durable but the directory entry may be lost on
  a crash before the next `sync()`. The normal `RaftLog.append` path issues no following `sync()`.
  `CrashStorage` models `appendToLog` as fully self-durable, i.e. **stricter than reality** here —
  acceptable (it can only miss a first-create loss, never false-pass a prefix loss). Config writes
  go through `put` (which dir-fsyncs), so the steady state is covered. Worth a low-severity
  durability ticket against `FileStorage` independent of RR-003; not a P1, not a flip blocker.
- **N-2:** see Finding 8 — extend RR-064 scope to the new `raft-log.snapshot` envelope.
- **N-3 (cosmetic):** none material; the matrix/harness are clean.

---

## Register action

RR-003 → **RESOLVED**, second-agent verification appended to Resolution-evidence. (Per the row,
the formal SnapshotInstallSpec twin remains owned downstream; the runtime fix is verified.)
