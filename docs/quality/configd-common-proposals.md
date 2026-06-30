# configd-common — idiomatic-Java quality pass: REVIEW & PROPOSE notes

Conservative idiomatic-Java quality pass (pre-EC2-measurement cleanup). Every landed change must be
byte-identical / behavior-preserving. This document holds **PROPOSE-ONLY** observations: items that are
either inside a §2 NO-TOUCH zone, sit under an explicit "proven / left byte-for-byte unchanged" comment,
or would change observable behavior. **None of these were applied.**

Outcome of the editable surface (`Clock`, `SystemClock`, `Storage`, `InMemoryStorage`,
`IntegrityException`, non-serialization parts of `FileStorage`): **nothing applied** — the code is
already clean, idiomatic Java 25 (`@Override` complete, fields `final`, no raw types / unchecked
warnings / redundant casts, no unused imports, all `Closeable`/channel sites use try-with-resources,
all `IOException`s wrapped with cause preserved, `computeIfAbsent` + defensive `clone()` already used).
Applying anything would be churn against the brief's "prefer leaving code alone" rule.

---

## A. Behavior-preserving but deferred (effectively §2 by comment — do NOT apply without sign-off)

### A1. `FileStorage.appendToLog` duplicates the framing logic that `frame(byte[])` already encapsulates
- **Where:** `FileStorage.java` `appendToLog` (~L122–135) re-inlines the exact CRC32 + length-prefix
  frame build that the private `static ByteBuffer frame(byte[])` helper (~L216–226) already produces
  for the no-sync path.
- **Proposal:** `appendToLog` could build its frame via `ByteBuffer frame = frame(data);` instead of the
  inlined block. The produced bytes are provably identical (same `allocate(4 + data.length + 4)`, same
  `putInt(len)/put(data)/putInt(crc)/flip()`, same Castagnoli-free `CRC32`), so the on-disk frame and the
  durability model (open + write-loop + `force(true)` + close per call) are unchanged.
- **Why PROPOSE, not apply:** the class carries an explicit, load-bearing comment that the durable
  single-append `appendToLog` path is *"deliberately left untouched … keep their exact, proven
  semantics"* and *"left byte-for-byte unchanged so crash/fault-injection wrappers that delegate to it
  keep their proven semantics"* (class javadoc ~L41–42 and block comment ~L148–149). Per the brief's
  HARD RULE 3 ("anything under an explicit do-not-modify / proven / byte-identical comment is
  propose-only"), this is propose-only even though the result is byte-identical. Low value, real
  re-verification cost — recommend leaving as-is unless a maintainer wants the DRY cleanup under review.

---

## B. §2 NO-TOUCH zone observations (review only — owners decide)

### B1. `BuggifyRuntime`: mutable static RNG is non-`volatile` and the generator is not thread-safe
- **Where:** `BuggifyRuntime.java` L15 `private static RandomGenerator random = …` (reassigned in
  `enableSimulationMode`/`disableSimulationMode`); read + advanced in the static `shouldFire(...)`
  (`random.nextDouble()`).
- **Observation:** `simulationMode` is `volatile` but `random` is not, so a reassignment in
  `enableSimulationMode(seed)` is published without a happens-before to a concurrent `shouldFire` reader.
  Separately, `L64X128MixRandom` is not a thread-safe generator: concurrent `nextDouble()` calls race on
  its internal state. For a *seed-deterministic* fault-injection harness this matters — concurrent draws
  would make the per-run enable/fire decisions non-reproducible.
- **Why PROPOSE, not apply:** this file is determinism-sensitive §2 ("changing logic shifts simulation
  seeds"). Adding `volatile`, switching to a thread-local/split generator, or synchronizing draws would
  alter the draw sequence and could shift seeds. The correct resolution depends on the simulation
  driver's threading contract (is `shouldFire` only ever called from the single sim thread?), which lives
  outside this module. **Action for owners:** confirm/document the single-threaded-call contract, or make
  the RNG access deterministic-under-concurrency in a seed-preserving way. Not a change to land in a
  byte-identity pass.

### B2. (minor) `Buggify` annotation `@Target` includes `LOCAL_VARIABLE` with `RUNTIME` retention
- **Where:** `Buggify.java` L16–17.
- **Observation:** annotations on local variables are never available reflectively regardless of
  retention policy (the JVM does not retain them in the class file's runtime-visible tables), so the
  `LOCAL_VARIABLE` target combined with `RUNTIME` retention is effectively inert for any reflective
  `BuggifyRuntime` lookup. Harmless; purely informational.
- **Why PROPOSE, not apply:** narrowing `@Target` is a public-annotation/API change and out of scope.

### B3. (minor, no action) Packing logic is duplicated across `HybridClock` and `HybridTimestamp`
- **Where:** `HybridTimestamp.packed()`/`fromPacked()` vs `HybridClock.encode()/physicalOf()/logicalOf()`
  — both implement the same `(physical << 16) | (logical & 0xFFFF)` layout.
- **Why no action:** both are §2 (allocation-free hot path + ordering-driving value type, byte/ordering
  proven by `HybridClockAllocationTest`, `HybridClockTest`, `HybridTimestampTest`). The duplication is
  benign and intentional (the hot path must stay primitive-only). Recorded for awareness only.

### B4. (no action) `IntegrityEnvelope.computeMac` allocates a small header `ByteBuffer` per call
- **Where:** `IntegrityEnvelope.java` `computeMac` (~L291) allocates an 8-byte header buffer on each
  `wrap`/`unwrap`.
- **Why no action:** this is a boundary path (Raft durability artifacts), not the measured <1 B/op hot
  path, and the byte layout / MAC input is security-proven (ADR-0042, PA-2021) and pinned by
  `IntegrityEnvelopeTest`. Deliberately left exactly as written; documented only so a future reviewer
  does not mistake it for an oversight.

---

## C. Deferred — would change observable behavior (do NOT apply in a byte-identity pass)

### C1. `InMemoryStorage.renameLog` vs `FileStorage.renameLog` diverge when the source log is absent
- **Where:** `InMemoryStorage.java` `renameLog` (L55–60) silently no-ops when `fromLogName` is absent
  (`remove` returns null → target untouched). `FileStorage.renameLog` (L340–354) calls `Files.move`,
  which throws `NoSuchFileException` → `UncheckedIOException` for an absent source.
- **Observation:** the two `Storage` implementations therefore differ in their thrown-exception contract
  for the missing-source case, and neither matches a documented `Storage.renameLog` precondition (the
  interface javadoc only specifies target-replacement semantics).
- **Why PROPOSE, not apply:** aligning them changes a thrown exception (in-memory) or relaxes one
  (file) — a semantic change, not byte-identical, and the in-memory impl is the deterministic-simulation
  backend (ADR-0007) where a behavior shift could move sim outcomes. Flag for a future correctness pass
  with its own test; out of scope here.

---

## D. Items explicitly considered and rejected as unsafe churn
- Replacing inline fully-qualified names (`java.util.concurrent.ConcurrentHashMap`, `java.util.Set`,
  `java.nio.file.StandardCopyOption` in `FileStorage`; `java.util.List`/`java.nio.file.Path` in the
  `Storage` interface) with imports — formatting-only churn forbidden by HARD RULE 2; the mixed style is
  the established convention here.
- Adding `Objects.requireNonNull(...)` for the "non-null" javadoc contracts on `Storage`/`InMemoryStorage`
  — would change the exact exception thrown for a null argument (currently a deeper NPE at `.clone()` /
  buffer wrap), i.e. an observable-behavior change. Rejected.
- Returning `Collections.unmodifiableList`/`List.copyOf` from `readLog` — callers (Raft WAL replay) may
  rely on a mutable result; would risk a semantic change. Rejected.
- Marking method parameters `final` — no file in this module does so; would be restyle churn. Rejected.
