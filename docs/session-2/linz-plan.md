# B5 — Linearizability Workstream Plan (Session 2, `session-2-correctness`)

**Status:** PLAN + offline verification. **No execution-round actions taken here** (no Maven, no
cluster, no iptables — the RR-002 agent owns those right now). This document is the blueprint the
B5 execution round follows once RR-002's live network-fault drills finish.

**Closes (on execution):** RR-016 (contract §7 + CI wiring + tracked-artifact policy),
RR-028 (discrimination gate ii RED + seed matrix + gate iv reproducibility), and converts
CM-135/CM-137/CM-165 EXISTS-UNVERIFIED → VERIFIED. Governed by ADR-0032 (harness/tooling) and
ADR-0033 (200 ⇒ committed, the ack-model flip B5 must reflect).

Offline evidence produced this round: `docs/session-2/captures/linz-selftests-offline.txt`.

---

## 1. Inventory & merge-state

### 1.1 What lives in `configd-linz` (all tracked — 28 files in git)

The module is **present on this branch** (it descends from the A3-B work via
`session-a3-linearizability`); `git ls-files configd-linz` → 28 files. Layout:

| Area | Files | Role |
|---|---|---|
| Runner | `runner/HarnessMain.java`, `HarnessArgs.java`, `LostWriteScenario.java`, `StaleReadScenario.java` | gate (iii)/(iv) faulted run + the two gate (ii) discrimination scenarios |
| Checker (Go) | `src/main/go/porcupine-check/{main.go,go.mod,go.sum}` | the ~150-line trusted checker; pins `anishathalye/porcupine v1.2.0` |
| Checker (Java bridge) | `check/PorcupineChecker.java`, `check/Verdict.java` | shells the Go binary, maps exit code → verdict |
| History | `history/{Op,HistoryRecorder,PorcupineHistoryWriter}.java` | recorder + the load-bearing `ack≠commit`/timeout encoding |
| Client | `client/ConfigClient.java` | JDK `HttpClient`; HTTP status → `{ok,info,fail}` (the 200-mapping site, §3) |
| Cluster | `cluster/{Cluster,ClusterNode}.java` | separate-JVM nodes over the real `TcpRaftTransport` |
| Fault | `fault/FaultInjector.java` | `iptables --dport REJECT` partitions + `kill -9` (sudo -n), tracked + healed |
| Schedule | `schedule/{Schedule,ScheduleJson}.java` | seeded fault+workload plan → reproducible `schedule-<seed>.json` |
| Discrimination patches | `discrimination/lost-acked-write.patch`, `stale-read.patch` | gate (ii) seeded bugs |
| Scenario configs / drivers | `scripts/{build-porcupine.sh,run-discrimination.sh,run-gate.sh}` | gate (i) build, gate (ii), gate (iii)+(iv) |
| Tests | `src/test/.../CheckerSelfTest.java` (gate i, `PORCUPINE_BIN`-gated), `HistoryWriterUnitTest.java` (pure Java) | |

### 1.2 What "merge it" still means per RR-016

RR-016's text predates this branch's CI work; **two of its three complaints are already closed
here, one half-closed:**

- **CI wiring — LARGELY DONE on this branch.** `ci.yml` already (a) sets up Go via
  `actions/setup-go@v5` keyed off `go.mod`/`go.sum` (`ci.yml:95-101`), and (b) runs gate-1 step (b)
  which builds the checker from Go sources and asserts `CheckerSelfTest` 6/6 / 0-skips
  (`gate-1.sh:107-143`). The Session-1 register row (`grep -i linz ci.yml → 0`,
  `PORCUPINE_BIN never set`) is **stale** — verify and annotate RR-016 accordingly.
  **Still owed:** a *faulted multi-node* linz run in CI-or-nightly (gate-2 — see §4); self-tests
  alone don't exercise 91% of the harness (the TF-10 9%-coverage complaint).
- **Contract §7 reference — OPEN.** `consistency-contract.md:194` still names a fictional
  "Wing & Gong" `LinearizabilityTest`. Fixed by §5 (rides the RR-031/RR-016 consolidated contract pass).
- **Tracked-artifact policy — OPEN.** See §1.3.

### 1.3 Untracked `runs/` and the recommended tracked-artifact policy

`configd-linz/runs/` = **59 run dirs / ~8.1 MB / 834 files**, all from the 2026-06-07 A3-B session
(per-seed `history-<seed>-n{3,5}.json` + `schedule-<seed>-n{3,5}.json` for seeds 1,2,3,5,7,8,13,21,42,99
on n3 and 2001–2004 on n3+n5; `cluster-*` node data-dirs + logs; `lostwrite-*`/`staleread-*` discrimination
dirs incl. `try1–try3`). `runs/`, `bin/`, `target/`, `.jqwik-database` are **already gitignored**
(`configd-linz/.gitignore`).

**Recommendation (one line):** keep `runs/` gitignored (it is a scratch/data-dir tree, not
evidence); commit only *curated* gate evidence — the small `schedule-<seed>.json` + checker
stdout + a one-line verdict per gate — as text captures under `docs/session-2/captures/`
(self-tests already there), exactly as every other Session-2 fix does its proof.

**Worth preserving from the A3-B `runs/` seed:** none of the bulky `cluster-*` data-dirs or raw
`history-*.json`; but the **gate-(iv) schedule pairs** `schedule-200X-n3.json` / `-n5.json` are the
artifact that *proves* byte-identical reproducibility, and the `staleread-…-try1/2/3` dirs are the
provenance of the ledger's "rare anomaly" residual. The execution round should **regenerate** these
at HEAD (they are cheap) and capture the curated subset; do not bulk-commit the old `runs/`.

---

## 2. Offline verification — DONE this round

Built the Porcupine checker fresh from `configd-linz/src/main/go/porcupine-check` with the
user-local Go 1.26.4 (`~/sdk/go`), **offline** (`GOTOOLCHAIN=local GOPROXY=off` — proves the module
cache is self-sufficient; no network). Ran the checker against the `PorcupineHistoryWriter`-encoded
form of every `CheckerSelfTest` case.

**Result: 8/8 checker-decided verdicts correct** (= `CheckerSelfTest` 6/6 — tests 3/4/5 are
GREEN/RED pairs, so 8 checker invocations; the decisive `info↔fail` and `info↔ok` flips all behave).
`CheckerSelfTest #6` is a pure-Java recorder precondition (duplicate-token → `IllegalStateException`),
re-verified structurally in `HistoryRecorder`. RED diagnostic and empty-history edge captured too.

This re-verifies the **checker-correctness half** of CM-135/CM-137 at HEAD. Full capture:
`docs/session-2/captures/linz-selftests-offline.txt`. The execution round should *additionally*
run the real `CheckerSelfTest` via Maven (`PORCUPINE_BIN=… ./mvnw -pl configd-linz test
-Dtest=CheckerSelfTest -Dsurefire.failIfNoSpecifiedTests=false`) and `HistoryWriterUnitTest` 4/4 to
re-verify the Java recorder→encoder glue on the same binary — those need the JVM/Surefire path this
round forbids.

---

## 3. Discrimination-gate plan (CM-137, RR-028) — incl. the ADR-0033 200⇒`:ok` change

### 3.1 Do the discrimination patches still apply post-RR-004? **YES — both apply cleanly at HEAD, and both still reproduce their intended bug class.**

`git apply --check` (and `git apply --check -3`) at HEAD (`6cbeb21`):

```
git apply --check configd-linz/discrimination/lost-acked-write.patch   -> exit 0 (clean)
git apply --check configd-linz/discrimination/stale-read.patch         -> exit 0 (clean)
```

Why they survive RR-004 (which rewrote the *propose/commit/ack* path, not these regions):

- **`stale-read.patch`** edits `RaftNode.readIndex()` / `isReadReady()` — the **read** path. RR-004
  touched `propose`/`whenCommitOutcome`/`applyCommitted`, not these. The current guards
  (`RaftNode.java:484-486`, `:512-514`) are byte-identical to the patch's `-` context; the patch
  deletes the leader gate so a lagging/isolated follower serves a superseded value as linearizable →
  RED. **Bug class intact.**
- **`lost-acked-write.patch`** edits `FileStorage.appendToLog()` (`:89`) to a durability no-op. The
  WAL is never persisted, but the in-memory log still commits+applies, so the write **does** earn a
  200/`Committed` and is confirmed by a linearizable read-back; a full-cluster `kill -9` + restart
  loses it (404) → RED. RR-004 makes the 200 *stronger* (now a real commit), which **sharpens** this
  scenario, not weakens it. RR-003's durable-snapshot fix is orthogonal (this mutates the WAL append,
  not snapshot persistence). **Bug class intact.** No patch rewrite is required.

> Sharpness note (not a blocker): `LostWriteScenario` records the PUT with `put.status()` which the
> client still maps to `INFO`; its RED comes from the *confirming read* (`linReadConfirm`) pinning
> the write as "happened", then the post-restart 404 contradicting it — so the discrimination RED is
> robust to the 200-mapping value. The 200⇒`:ok` change in §3.3 is needed for the **RR-004 flip
> proof** (the *unmodified* faulted run staying LINEARIZABLE with 200⇒`:ok`), not for the gate-(ii) RED.

### 3.2 Execution-round command sequence (gate ii) — run **after** the RR-002 agent releases the box

The driver `configd-linz/scripts/run-discrimination.sh` already does control→patch→rebuild→RED→revert
per bug with 3× retry. Exact sequence:

```bash
# preconditions: clean tree, sudo -n iptables works, ports clear, RR-002 agent done
export PORCUPINE_BIN="$PWD/configd-linz/bin/porcupine-check"   # built in §2 (or build-porcupine.sh)
./mvnw -q -pl configd-server -am package -DskipTests           # the jar the scenarios launch
./mvnw -q -pl configd-linz -am test-compile -Dsurefire.failIfNoSpecifiedTests=false  # scenario classes
bash configd-linz/scripts/run-discrimination.sh both
#   per bug: control (unmutated jar) GREEN(exit 0) -> git apply patch -> rebuild scratch jar
#            -> mutated MUST be NON-LINEARIZABLE/RED(exit 1) -> git apply -R -> rebuild clean
#   expected: "DISCRIMINATION PASS: both seeds turn the checker RED; controls GREEN."
```

Capture: control + mutated checker stdout (incl. `PORCUPINE_DUMP=1` on the RED), the `VERDICT` lines,
and the `git apply -R` cleanliness check → `docs/session-2/captures/linz-discrimination.txt`.
Per ADR-0032, if a seeded bug does **not** go RED the harness is blind — STOP and fix the harness,
do not proceed to the green gate.

### 3.3 The 200⇒`:ok` mapping change required by ADR-0033 — exact location

**File:line:** `configd-linz/src/main/java/io/configd/linz/client/ConfigClient.java:87-89`
(method `write(...)`):

```java
if (code == 200) {
    suspectedLeaderId = node.id();
    return new OpResult(Op.Status.INFO, value, call, System.nanoTime()); // ack != commit   <-- pre-RR-004
}
```

Pre-RR-004, 200 meant *local append before quorum* (a lie), so the harness had to record it `:info`
(may or may not have committed). **Post-ADR-0033, `HttpApiServer` returns 200 `Committed: seq=S`
ONLY after quorum commit + apply** (ADR-0033 §4: "200 is returned only after quorum commit + local
apply"). So a 200 is now a *definite commit* and MUST map to `OK`/`:ok`:

```java
if (code == 200) {
    suspectedLeaderId = node.id();
    return new OpResult(Op.Status.OK, value, call, System.nanoTime()); // ADR-0033: 200 == committed
}
```

Supporting changes the execution round must also make (do NOT leave the doc/comment lying):

- Update `ConfigClient`'s class Javadoc bullet (`:21`) "PUT/DELETE 200 Accepted -> INFO (ack != commit…)"
  → "200 `Committed: seq=S` -> OK (ADR-0033: committed)". The **timeout / conn-refused / 5xx-other**
  branch (`:107`) stays `INFO` (genuinely indeterminate — may have committed). The **503/4xx/429**
  branch (`:101,:104`) stays `FAIL` (definite reject; ADR-0033 maps Lost/NotLeader→503, Overloaded→429,
  Indeterminate→**504** — note 504 currently lands in the `:104` FAIL path; the execution round should
  add a 504⇒`INFO` case, since Indeterminate "MAY still commit later" per ADR-0033 §3).
- `HistoryRecorder.recordPut` Javadoc (`:24`) says "INFO when accepted (ack != commit)" — update to
  reflect OK-on-200. Encoding logic in `PorcupineHistoryWriter` needs **no change**: an OK PUT is a
  kept write whose float is confirm-bound exactly like an INFO PUT; OK is strictly a *stronger* claim
  and the register model already treats every kept write as a legal write. (`CheckerSelfTest` only
  feeds PUTs as INFO/FAIL today, so an OK-PUT path is new surface — add a self-test case: a 200-OK PUT
  observed by a later read stays GREEN, and a 200-OK PUT whose value is *never* re-observed and then
  contradicted by a confirmed different value goes RED.)

### 3.4 The RR-004 flip proof (the deliverable RR-004's RESOLVED flip waits on)

After 3.3, run one **unmodified** faulted multi-node run with 200⇒`:ok` and confirm it stays
LINEARIZABLE — this is the handoff's required "linz reruns with 200⇒`:ok` (not `:info`) and stays
LINEARIZABLE" proof and the register's "Lead flips → RESOLVED after … B5's linz re-run (200⇒`:ok`)":

```bash
java --enable-preview -cp configd-linz/target/classes io.configd.linz.runner.HarnessMain \
  --seed 4242 --nodes 3 --clients 4 --keys 8 --duration 15000 \
  --base-raft 11400 --base-api 10400 \
  --jar configd-server/target/configd-server-0.1.0-SNAPSHOT.jar --out configd-linz/runs
# expect: VERDICT: LINEARIZABLE, exit 0, faults active (2x kill-9+restart, 2x iptables REJECT)
```

Capture → `docs/session-2/captures/linz-rr004-200-ok-flip.txt`; cross-link from RR-004's row.

---

## 4. CI/gate wiring plan (RR-016) — gate-2

### 4.1 What gate-2 should run

- **Always (every CI run), already wired in gate-1 step (b):** build the checker from Go sources +
  `CheckerSelfTest` 6/6 / 0-skips. ~15 s. gate-2 *inherits* this; do not duplicate it — reference it.
- **gate-2 adds (gate-sized, grounded in recorded runtimes):** **one** unmodified faulted multi-node
  run — `HarnessMain --seed <fixed> --nodes 3 --duration 15000` → MUST be LINEARIZABLE
  (the audit's seed-4242 run was ~25 s wall incl. 2× kill-9+restart + 2× iptables REJECT). Budget
  **~15–30 s/seed**. One seed in the per-PR gate keeps it cheap; the multi-seed/5-node matrix
  (§6) goes **nightly**, not per-PR.
- **gate-2 size recommendation:** gate (i) self-tests (inherited, ~15 s) + **1** faulted 3-node run
  (1 fixed seed, ~30 s) per PR; **nightly** job runs the §6 matrix (seeds × {3,5} nodes) +
  discrimination (§3.2) + gate-(iv) reproducibility. Rationale: the faulted run needs passwordless
  sudo for iptables/kill-9 — fine on the self-hosted/audit box, **not** on stock GitHub runners
  (see 4.2), so the per-PR faulted run is **opt-in / self-hosted-or-nightly**, never a hard
  ubuntu-latest gate.

### 4.2 What `ci.yml` needs (Go availability on runners)

- **Already present:** `actions/setup-go@v5` with `go-version-file: …/go.mod` +
  `cache-dependency-path: …/go.sum` (`ci.yml:95-101`). gate-1 builds with `GOTOOLCHAIN=local` so the
  installed toolchain must satisfy the `go.mod` `go` directive — the file-driven version keeps them in
  sync. **No change needed for the self-test path.**
- **Go-absent behavior (what gate-1 step (b) does):** if `PORCUPINE_BIN` is unset *and* no Go is on
  `PATH`/`~/sdk/go`, `step_linz` **FAILs loudly** (`gate-1.sh:116-121`) — it deliberately does **not**
  silently install a toolchain (a strict gate must not). The escape hatch is `GATE1_SKIP_LINZ=1`
  (reported LOUDLY as SKIPPED). **Recommendation:** keep this. For the **faulted gate-2 run**, the
  blocker is not Go but **sudo iptables** — so gate-2's faulted job must run on a runner with
  passwordless sudo (self-hosted) or as the nightly job; on ubuntu-latest, gate-2 runs self-tests
  only and marks the faulted run SKIPPED-loudly (mirror the `GATE1_SKIP_LINZ` idiom with a
  `GATE2_SKIP_FAULTED` knob).

---

## 5. Contract §7 patch plan (consolidated contract pass, rides RR-031/RR-016)

**File:** `docs/consistency-contract.md:194` (the INV-L1 row of the §7 invariant→test table).

**Current (fictional):**

```
| INV-L1 | `LinearizabilityTest` | Verify linearizable writes via concurrent client operations and linearizability checker | Run concurrent writes + reads against simulated cluster; verify history is linearizable using Wing & Gong algorithm |
```

**Replacement text (names real files/commands; maps INV-L1 to the real checker + the sim history path):**

```
| INV-L1 | `configd-linz` harness + Porcupine checker (ADR-0032) | Drive a real separate-JVM cluster (shaded `configd-server` over the real `TcpRaftTransport`) under OS-level faults (`iptables --dport REJECT` + `kill -9`), record a per-key checker-neutral op-history (`ack≠commit` ⇒ writes float/confirm-bound; ADR-0033 ⇒ 200 `Committed` = `:ok`), and check each key as an independent linearizable register with the trusted `anishathalye/porcupine` checker — NOT a hand-rolled "Wing & Gong" checker. | Real binary: `configd-linz/runner/HarnessMain` → `configd-linz/src/main/go/porcupine-check`; gate (i) self-test `CheckerSelfTest` (6/6, `PORCUPINE_BIN`-gated, run by `gates/gate-1.sh` step b); gate (ii) discrimination `scripts/run-discrimination.sh`; gate (iii)/(iv) `scripts/run-gate.sh`. Cheaper replayable complement: the deterministic-sim history source emitting the identical op-history format (`docs/session-2/adversarial-sim-design.md §6`) feeding the same Porcupine checker. |
```

Also fix the prose claim "using Wing & Gong algorithm" wherever §7 repeats it, and add a pointer to
ADR-0032/0033. (Per ADR-0033 §"Consequences", §7's fixes "ride RR-031/RR-016" — do this in that
consolidated pass, not piecemeal.)

---

## 6. Seed-matrix plan (RR-028 residuals): the owed re-runs + gate (iv)

### 6.1 A3-B's six gates, and what is verified at HEAD vs owed

Per the A3-B ledger entry (`READINESS-LEDGER.md:123`) and ADR-0032 / the two driver scripts, the six
gates and their HEAD status:

| Gate | Definition | Artifact that proves it | Status at HEAD |
|---|---|---|---|
| (i) | self-test 6/6 (incl. timeout→info-never-fail flip) + 4/4 unit | `CheckerSelfTest` 6/6, `HistoryWriterUnitTest` 4/4 | **RE-VERIFIED (checker half) offline this round** (§2); Maven 6/6+4/4 owed in execution round |
| (ii) | discrimination: both seeded bugs RED, controls GREEN | `run-discrimination.sh both` → "DISCRIMINATION PASS" + RED `VERDICT` capture | **OWED** — patches apply (§3.1); not re-run since A3-B (needs box) |
| (iii) | LINEARIZABLE across seeds on 3- AND 5-node, faults active | per-seed `history-<seed>-n{3,5}.json` + `VERDICT: LINEARIZABLE`, faults>0 | **PARTIAL** — audit re-ran one seed (4242, n3); full matrix + n5 OWED |
| (iv) | same seed → byte-identical `schedule-<seed>.json` | two `--schedule-only` runs + `diff -q`/`sha256sum` equal | **OWED** — A3-B artifact pairs consistent w/ it; not re-proven at HEAD |
| (v) | `./mvnw -fae test` BUILD SUCCESS | full-suite green (self-test auto-skips w/o `PORCUPINE_BIN`) | superseded by gate-1 step (a); re-run in execution round |
| (vi) | independent reviewer confirms (real multi-process, info-not-fail sound, discrimination genuinely RED) | reviewer sign-off | satisfied at A3-B; B5 execution round is the re-confirmation |

### 6.2 Minimal execution-round plan to re-verify gates (ii) and (iv)

**Gate (ii):** §3.2 (`run-discrimination.sh both`) — artifact: "DISCRIMINATION PASS" + the two RED
`PORCUPINE_DUMP` captures + `git apply -R` clean. This is the single artifact that proves (ii).

**Gate (iv) — byte-identical schedule (cheap, no cluster, no faults fired):**

```bash
rm -rf /tmp/repro-a /tmp/repro-b
for d in repro-a repro-b; do
  java --enable-preview -cp configd-linz/target/classes io.configd.linz.runner.HarnessMain \
    --seed 777 --nodes 3 --clients 4 --keys 8 --duration 15000 \
    --schedule-only true --jar configd-server/target/configd-server-0.1.0-SNAPSHOT.jar --out /tmp/$d
done
diff -q /tmp/repro-a/schedule-777-n3.json /tmp/repro-b/schedule-777-n3.json   # MUST be identical
sha256sum /tmp/repro-a/schedule-777-n3.json /tmp/repro-b/schedule-777-n3.json # MUST match
```

(`run-gate.sh` already wraps exactly this as its gate-(iv) block.) Artifact: the matching `sha256sum`
pair + line/event count → `docs/session-2/captures/linz-gate-iv-repro.txt`.

**Gate (iii) full matrix (nightly, not per-PR):** `bash configd-linz/scripts/run-gate.sh "2001 2002 2003 2004"`
runs all four seeds on **both** 3- and 5-node (the script loops `for n in 3 5`) → each MUST be
LINEARIZABLE with faults>0. Plus the owed **anomaly soak** (the ledger's one early non-reproducing
non-linearizable history): a longer multi-seed soak; record its disposition (reproduce-and-explain or
formally close as a recorder artifact) — this is the last RR-028 residual and the most likely thing to
make B5 bigger than expected (see below).

---

## Execution-round headline & "bigger than expected" risks

**Headline command plan (in order, post-RR-002):** build jar + checker → `CheckerSelfTest` 6/6 +
`HistoryWriterUnitTest` 4/4 via Maven → apply the `ConfigClient.java:89` 200⇒`:ok` change (+504⇒INFO,
+ docs) → `run-discrimination.sh both` (gate ii RED) → unmodified seed-4242 faulted run with 200⇒`:ok`
LINEARIZABLE (RR-004 flip proof) → gate-(iv) `--schedule-only` diff → nightly seed matrix + anomaly soak
→ contract §7 text + RR-016/RR-028 register rows with captures.

**Things that could enlarge B5:**

1. **The 200⇒`:ok` change adds new checker surface** — an OK-PUT path that `CheckerSelfTest` never
   exercised. Needs a new self-test pair (OK-PUT observed → GREEN; OK-PUT superseded-then-contradicted
   → RED) **and** an audit that a *committed* (now `:ok`) write that genuinely vanishes on the
   RR-002/RR-004-adjacent failover surfaces as RED rather than being silently dropped. This is real
   work, not a one-line edit.
2. **The 504/Indeterminate mapping** (ADR-0033's new outcome) currently falls into `ConfigClient`'s
   `FAIL` branch — wrong (Indeterminate may commit later). Fixing it touches the discrimination encoding
   assumptions; must re-verify gate (ii) still REDs after.
3. **The owed anomaly soak** (RR-028 / the ledger's "unresolved rare anomaly") is open-ended — a soak
   that reproduces it could surface a *real* latent bug (or confirm a recorder artifact); budget a
   dedicated soak slot, do not assume it closes trivially.
4. **The faulted gate-2 run cannot be a hard ubuntu-latest gate** (needs sudo iptables) — wiring it
   nightly/self-hosted is extra CI plumbing beyond the already-wired self-test step.
5. **RR-002 coupling:** B5's live faulted runs must use iptables **REJECT** (not DROP) until RR-002
   lands — DROP stalls the leader via the timeout-less connect (that is the RR-002 bug). If RR-002
   lands first, re-test that a DROP partition no longer stalls (a free regression for RR-002).
