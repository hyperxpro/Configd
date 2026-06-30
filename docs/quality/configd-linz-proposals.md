# configd-linz — §2 NO-TOUCH proposals (REVIEW ONLY — not applied)

Idiomatic-Java quality pass, conservative/behavior-preserving. The items below live in this
module's §2 NO-TOUCH zone (the linearizability **verdict semantics** and the **history output
format**, an external Go-`porcupine` contract), so they are **proposed, not applied**. Each notes
why it is behavior-preserving and why it was nonetheless held back.

§2 files for this module:
`check/PorcupineChecker.java`, `check/Verdict.java`,
`history/PorcupineHistoryWriter.java`, `history/HistoryRecorder.java`, `history/Op.java`.

---

## P1 (recommended) — `PorcupineChecker.checkFile`: unclosed subprocess streams (FD hygiene) + latent sequential-read deadlock

`check/PorcupineChecker.java`, `checkFile(Path)`:

```java
Process proc = new ProcessBuilder(binary.toString(), historyJson.toString())
        .redirectErrorStream(false)
        .start();
byte[] out = proc.getInputStream().readAllBytes();   // never closed
byte[] err = proc.getErrorStream().readAllBytes();   // never closed
```

Two observations, in priority order:

1. **Unclosed process streams (FD leak).** `getInputStream()`/`getErrorStream()` are read to EOF but
   never closed; the pipe read-end FDs are released only when the `Process` is GC'd. This is the exact
   leak class fixed in the editable `fault/FaultInjector.run()` during this pass (there: wrap the read
   in try-with-resources). `check()` calls `checkFile` once per harness run / once per self-test case,
   so accumulation is bounded, but the fix is free and idiomatic.

2. **Latent sequential-`readAllBytes` deadlock.** With `redirectErrorStream(false)`, the parent drains
   stdout fully *before* touching stderr. If `porcupine-check` ever emitted more than one OS pipe
   buffer (~64 KiB) to **stderr** before closing stdout, the child would block writing stderr while the
   parent blocks reading stdout → hang until the 5-minute `waitFor` watchdog fires `destroyForcibly()`
   and (wrongly) reports `INDETERMINATE`. Benign **today** because the checker's output is a short
   verdict/counterexample, but it is a real robustness footgun for a correctness oracle.

**Minimal behavior-preserving fix** (stream hygiene only — verdict unchanged): close both streams,
e.g.

```java
byte[] out;
byte[] err;
try (var stdout = proc.getInputStream(); var stderr = proc.getErrorStream()) {
    out = stdout.readAllBytes();
    err = stderr.readAllBytes();
}
```

**Robustness fix** (removes the latent deadlock): either drain stderr on a separate thread, or — since
this class already merges streams elsewhere — set `.redirectErrorStream(true)` and read one stream.
NOTE this **changes observable output**: the `Result.stderr()` field would fold into `stdout()`, which
the gate/self-test print as evidence. That is a contract-adjacent change in a §2 file, so it must be an
explicit owner decision, **not** an idiomatic auto-fix. Recommendation: apply the minimal hygiene fix
(close streams) now; treat the redirect/drain change as a separate, owner-approved robustness task.

**Why not applied here:** `PorcupineChecker` is §2 (verdict-path). Even the stream-close form sits in a
no-touch file, so per the brief it is proposed, not landed.

---

## P2 (optional, low value) — `PorcupineHistoryWriter` cosmetics

`history/PorcupineHistoryWriter.java` is the **byte-output contract** (the external `porcupine`
consumes it; pinned by `HistoryWriterUnitTest` + `CheckerSelfTest`). The serialized bytes must not
change. Two purely cosmetic, behavior-preserving observations — listed for completeness, **not
recommended** given the file's frozen-output status (any churn here invites a divergence re-check for
zero functional gain):

- `java.util.Map` / `java.util.HashMap` are referenced via fully-qualified names (`firstObserved`
  construction); they could be imported. Cosmetic only.
- `firstObserved` (`new java.util.HashMap<>()`) could be presized to `kept.size()`. This is an
  **offline check path** (runs once per history, not a measured hot loop), so it is not a measured
  allocation budget and offers no meaningful GC win — explicitly **not** a perf hypothesis worth
  pursuing.

No change recommended.

---

## Confirmed clean (no proposal)

- `history/Op.java` — record with a compact-ctor null guard on `value`; correct and idiomatic.
- `history/HistoryRecorder.java` — `CopyOnWriteArrayList` + `ConcurrentHashMap.newKeySet()` for
  concurrent appends; `ops()` already returns a defensive `new ArrayList<>(ops)`; unique-token
  precondition enforced. No safe nit.
- `check/Verdict.java` — trivial enum; nothing to do.
