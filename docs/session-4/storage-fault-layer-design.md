# B1 — Fault-injecting storage layer: design, fidelity, and oracle catalogue

Workstream B. This is the storage-fault foundation the durability oracles lean on. Per the
matrix-before-execution rule, it declares the **expected behavior (oracle) for every fault
class before any cell runs**. Cells are executed against these oracles in the kill matrix
(`kill-matrix.md`) and recorded as EXP-NNN.

## 1. Mechanism and why in-JVM

The `io.configd.common.Storage` interface (`put/get/appendToLog/readLog/truncateLog/
renameLog/sync`) is the single durable seam Raft uses (currentTerm, votedFor, WAL frames,
snapshot blob). Two in-JVM injectors wrap it:

- **`CrashStorage`** (existing, consensus-core test) — the faithful **crash/durability**
  model: self-durable writes (`put`/`appendToLog`, which `FileStorage` `force(true)`s
  before returning) vs rename-style ops (`truncateLog`/`renameLog`, durable only after the
  following `sync()` directory-fsync). `crash()` discards un-fsynced rename-style mutations;
  `recoveredView()` models the restart. Crashes are armed at semantic points
  (`crashBeforeKeyPut`, `crashAfterKeyDurable`, `crashBeforeWalDelete`, `armCrashAfterWrites`).
  **Extend this for fsync-lie and torn-write — do not fork it** (it alone faithfully models
  the directory-fsync-pending window that a `FileStorage` wrapper cannot see — the RR-086 gap).
- **`FaultInjectingStorage`** (new, `configd-testkit`) — a decorator over any delegate
  `Storage` injecting the *operational* faults that **throw or delay or truncate**:
  write-failure, ENOSPC, fsync-failure, short-read, latency. These do not need the crash
  durability model; they exercise how the *callers* react to a storage operation that
  fails/lies in-place.

**Why in-JVM (charter-sanctioned):** a real block device / firmware harness is out of scope
here. The in-JVM wrapper has full control of the `Storage` contract and is deterministic, so
it can place a fault at an exact operation. Its fidelity limits are §4.

## 2. Fault catalogue and the ORACLE for each (declared before injection)

| Fault | Injector | Models | ORACLE (expected, cited) |
|---|---|---|---|
| **Crash at lifecycle point** | CrashStorage arming | power loss between durable steps | On restart from `recoveredView()`, recovered state == committed state; `durable_prefix_no_gap` holds (RR-003); a complete prefix (snapshot@S + WAL (S..last]) is always reconstructable. Covered by `SnapshotCrashRecoveryTest`. |
| **Torn write (partial final WAL record)** | CrashStorage torn-tail | a half-written append at power loss | Recovery DETECTS the torn tail and discards it if uncommitted; it must NEVER feed a partial record to the state machine. If discarding would drop a *committed* entry → fail loudly (refuse to boot), never silent. `arch §6`/RR-003. `SnapshotCrashRecoveryTest` torn-tail cell. |
| **fsync-lie (report success, drop data)** | CrashStorage `lieOnSync` (new) | disk firmware that ACKs fsync then loses the write on power cut | A node that "synced" then lost data must, on restart, DETECT the resulting gap and **refuse to start / fail loudly** (`durable_prefix_no_gap`, or a WAL-contiguity check) — never silently serve missing committed state. If undetectable at the WAL level, that is a DOC/ADR finding (the contract must state the detection boundary). |
| **Write failure / IOException mid-op** | FaultInjectingStorage `failNextWrites` | transient device error on append/put | The caller must SURFACE it (structured log + metric + appropriate degradation), NOT swallow it. A follower whose apply-path write throws must not become a silent mute zombie — **RR-008** (this is the load-bearing one; see EXP-003). |
| **ENOSPC (no space)** | FaultInjectingStorage `enospcAfterBytes` | disk full during WAL append / snapshot write | DEFINED DEGRADATION: write rejection / load-shedding per `arch §11`, NOT a crash-loop and NOT silent loss. Leader sheds writes (503); follower surfaces and (ideally) steps out of quorum cleanly. |
| **fsync failure** | FaultInjectingStorage `failNextSyncs` | `sync()` throws | The pending rename-style mutation must NOT be treated as durable; the caller must surface the failure (same anti-swallow oracle as write-failure). |
| **Short read (truncated readLog)** | FaultInjectingStorage `shortReadLog` | a read returns fewer frames than written | Recovery must detect the truncation (index/contiguity check) and fail loudly, not silently boot with a short log. |
| **Latency injection (slow disk)** | FaultInjectingStorage `latencyHook` | fsync/append latency spikes | Consensus deadlines + backpressure respond per `arch §6` (fsync >1s → voluntary step-down) and `§11`, NOT unbounded queueing; a slow-disk follower must NOT drag the leader (B3, pending). |

## 3. ENVIRONMENT-BLOCKED (cannot be validated here — staging recipe recorded)

- **True power loss / write reordering at the block layer.** The in-JVM model assumes the
  `FileStorage` durability contract (force-before-return for self-durable writes; dir-fsync
  for renames). It cannot validate that the *real* filesystem/device honors `fsync`.
  **Staging recipe:** on a staging node with a real disk, run the kill matrix under
  `dm-flakey` / `dm-delay` (device-mapper fault injection) or a power-cuttable VM; assert the
  same oracles. Record device + FS + mount flags.
- **Disk firmware that lies about fsync (real).** Modelled by `lieOnSync`, but only a real
  device with a volatile write cache + power cut proves the detection boundary.
  **Staging recipe:** disable the device write-cache barrier (`hdparm -W1` + no `fua`), power-cut.
- **Bit-rot / silent sector corruption.** Not modelled (the WAL has no per-record checksum
  audited here — see RR-064 / wire-format work). **Staging recipe:** inject with `dm-corrupt`.

## 4. Fidelity limits

- Deterministic and single-threaded (matches R-01); no real wall-clock latency — "latency"
  is an accounting hook, so B3's "slow disk doesn't drag the leader" is tested via the
  injected-deadline path, not real sleeps.
- `FaultInjectingStorage` over `InMemoryStorage` cannot see the directory-fsync-pending
  window (RR-086); torn-write / fsync-lie / crash cells MUST use `CrashStorage`.
- The injector throws `java.io.UncheckedIOException` (the `Storage` contract is
  unchecked) to model device errors; callers that only catch narrower types are a finding.
