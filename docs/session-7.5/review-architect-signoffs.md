# Session 7.5 — review-architect independent sign-offs

> INDEPENDENT, ADVERSARIAL verification of methodology + throughput baseline attribution.
> Reviewer: `review-architect` (opus). Date: 2026-06-20. Read-only on source/captures.
> Scope: `methodology-note.md`, `throughput-baseline.md`, `run-log.md §6.1/§7.0`,
> `captures/throughput/*`, `OpenLoopWriteDriver.java`. Verdicts are per the four charter honesty
> rails. Overall: **APPROVE** (4/4 items pass; methodology-note sign-off flipped to SIGNED).

---

## Item 1 — Coordinated-omission correction — VERDICT: **APPROVE**

The CO correction is genuinely applied in `OpenLoopWriteDriver.atRate` and would NOT let a 16-vCPU
stall hide:

- **Open-loop schedule, decoupled from completions.** The submitter computes
  `scheduled = start + i·intervalNanos` and releases request *i* at that wall-clock instant via
  `pool.execute(...)` (`OpenLoopWriteDriver.java:206-217`). The for-loop over `i` never waits on any
  completion; it sleeps/spins only until the *next scheduled slot*
  (`:208-212`, `Thread.sleep` coarse + `Thread.onSpinWait()` fine). A slow/stalled server therefore
  cannot throttle the offered cadence — the classic CO trap is structurally absent.
- **Latency measured against SCHEDULED time, not send time.** Both the success and the exception
  paths compute `(System.nanoTime() − fScheduled)/1000` (`:221`, `:228`). A request whose slot fell
  inside a stall accrues latency from when it *should* have been sent. This is the Tene/HdrHistogram
  intended-time correction done at the source clock (no need for `recordValueWithExpectedInterval`:
  every intended slot is explicitly timed, which is the stronger form).
- **Backpressure recorded at intended-time, never dropped.** Bounded `ThreadPoolExecutor`
  (`concurrency` threads + `LinkedBlockingQueue(concurrency*8)`, `:190-192`); on
  `RejectedExecutionException` the driver STILL records `(now − fScheduled)` into the histogram and
  increments `rejected` (`:231-236`). A full queue thus surfaces as tail latency + a shed count, not
  as a silently-faster run. The captures bear this out: phase-3 histogram `count=700000` == `intended`
  (every scheduled slot is in the histogram), with `rejected_backpressure=549835` recorded, not lost.
- **Calibration precondition (F4) honored.** `calibrate` mode finds the closed-loop ceiling first
  (380/s); the at-rate phases (10k, 100k offered) sit far above that ceiling, so the runs are
  correctly read as *saturation/overload findings*, and the doc explicitly does NOT use the phase-3/4
  histograms as latency numbers (`throughput-baseline.md` "Method rails"). The unloaded latency SLO
  uses phase-2 only.

**Could a stall be hidden at 16 vCPU?** No. The only way CO re-enters is if the schedule itself
blocks; it does not — the schedule loop only times-gates on the clock, and the blocking quorum write
runs inside pool workers whose exhaustion is counted as `rejected` (intended-time recorded), not
back-pressured onto the schedule. More cores would let the *generator* keep up more easily, which is
the safe direction (the hazard is the generator falling behind silently; here it cannot).
*Minor, non-blocking:* a single `synchronized(hLock)` guards every `recordValue` (`:256-258`); under
512 workers this lock is a generator-side serialization point, but it can only *understate* achieved
rate / *inflate* recorded latency — it biases toward honesty, never toward hiding a stall. No fix
required.

cite: `OpenLoopWriteDriver.java:206-212` (open-loop schedule), `:221`/`:228` (latency vs scheduled),
`:231-236` (rejection recorded at intended-time), `:190-192` (bounded pool), `:194`+`:246-252`
(HdrHistogram, µs, intended-time label).

## Item 2 — Baseline fsync attribution (NOT disk / NOT CPU / IS implementation) — VERDICT: **APPROVE**

Re-derived independently from the captures and the code; the attribution holds.

**"Disk idle" — supported.** `phase3.iostat.txt` (72 samples, nvme1n1):
- `f/s = 0.00` in **every** sample (col 20). `w_await = 0.03 ms` in 67/72 samples (max 0.15);
  `w/s` 1.4k–21.4k at ~33–38% merge.
- `%util` min 1.67, median ≈13.6, q75 ≈27, max 62.2 (one transient 80.3 at one sample). The doc's
  "mostly 5–17%, peaks <60%" slightly understates the spread (q75 ≈27%, several 40–60% samples), but
  the load-bearing claim — *the device has large headroom and is not the bottleneck* — is firmly
  supported (even peak util leaves headroom and `w_await` never degrades from 0.03 ms). **Disk is not
  the ceiling.**
  *Note on f/s=0 while code calls `force(true)`:* expected for EC2 instance-store NVMe — the durable
  barrier is folded into the write path (FUA/no separate cache-flush op), so the cost appears as
  synchronous-call stall time and in w/s, not as a device `f/s` counter. This does not weaken "disk
  not bottleneck"; it strengthens it (the device dispatches the writes trivially — the serialization
  is upstream on the calling thread).

**"CPU idle" — supported.** `phase3.mpstat.txt` (71 all-core samples over the ~70 s window):
all-core `%idle` min 57.06, median ≈80, bulk 73–88%. The doc's "72–88% throughout (≈3–4 of 16 vCPU
busy)" is right at the median; one sample dipped to 57% idle but no sample approaches saturation.
`phase3.pidstat.txt`: each of the 3 node JVMs uses ~10–230% of *one* core (of 1600% available), never
core-bound. **CPU is not the ceiling.**

**"Implementation-bound via fsync-on-tick-thread → leadership churn" — this IS the honest reading.**
The causal chain is confirmed structurally in code, and the simpler alternatives are refuted:
- Single tick thread: `ConfigdServer.java:350` `Executors.newSingleThreadScheduledExecutor`. Per R-01
  (`ConfigdServer.java:510`) ALL `propose()` calls are marshalled onto that same single thread, which
  ALSO runs `tickHeartbeat()` and `tickElection()` (`RaftNode.java:1209`, `:1225-1279`).
- Per-entry synchronous fsync on that thread: `propose` → `log.append(entry)` (`RaftNode.java:400`) →
  `RaftLog.append` → `FileStorage.appendToLog` → `channel.force(true)` + open/close **per entry**
  (`FileStorage.java:91-110`). No group commit (confirmed `run-log.md §7.0`; `appendAll`/`appendEntries`
  loop single appends).
- CheckQuorum step-down: `RaftNode.java:33` + `:1234` `becomeFollower(currentTerm)` when a heartbeat
  round is not completed within the election window. Synchronous per-entry fsyncs monopolize the one
  tick thread, starving `tickHeartbeat` → leader self-demotes / followers election-timeout →
  `WriteResult.NotLeader`/`Lost` → HTTP **503** with leader hint
  (`ConfigWriteService.java:233-234`). That is the 121,019× 503.
- **Alternatives refuted by the driver's own counters:** the 121,019 503s are NOT a driver/HTTP-client
  artifact — `retargets=55` and `exceptions=55` (four orders of magnitude smaller); the 503s are
  server-emitted `NotLeader`/`Lost`, not client retarget churn or socket errors. Backpressure
  (`rejected=549835`) is the *generator's* bounded-queue shedding the offered 10k/s the cluster can't
  absorb — a separate, correctly-labeled count — not the cause of the leadership collapse.

**Is calling it case-(a) "implementation finding" (vs a true system ceiling) defensible?** Yes. Disk
headroom (util ≤~60%, w_await 0.03 ms, well under the ~8.3k–14.3k fdatasync band) + CPU headroom
(~80% idle) + a single-thread serialization point that couples durability to liveness is the textbook
signature of case-(a) "batching off/undersized — fixable here," not case-(c) "genuine architectural
ceiling with group commit on and headroom exhausted." The §7.0(a) precondition ("CPU has spare
headroom") is met. The mandated fix (group commit → one fsync per batch, channel kept open) is the
correct remediation and is deferred to PART 2 (before/after on this box). The doc correctly supersedes
the S5 a-priori "815k/s mechanism, host-capacity-limited" hypothesis with the measured implementation
reason.

**Overclaim to note (non-blocking, wording only):** the iostat summary "`%util` mostly **5–17%**" is
optimistic — q75 is ~27% and a handful of samples reach 40–62% (one 80%). The *conclusion* (disk not
bottleneck) is unaffected, but the honest phrasing is "median ~14%, q75 ~27%, peaks to ~60% (one
transient 80%), `w_await` flat at 0.03 ms." Recommend (do not require) softening "5–17%" to a
median+range. This does not change the attribution and is not a blocker.

## Item 3 — WAN split method (three numbers; 68 ms citation) — VERDICT: **APPROVE**

- **68 ms citation EXISTS and is correctly attributed.** `docs/session-5/methodology.md §2`:
  the RTT matrix row `us-east-1 ↔ eu-west-1 = 68` (line 88), and the explicit derivation (lines
  110-112): for a 5-voter global group led from us-east-1 with followers {us-west-2 57, eu-west-1 68,
  ap-northeast-1 148, ap-southeast-1 220}, majority of 5 = 3 = leader + 2 acks → commit gates on the
  **2nd-fastest** follower RTT = **68 ms** (one round trip). The methodology-note's
  `RTT(2nd-fastest follower)=68ms` matches exactly.
- **Three-number split correctly specified.** The note states: local component = VERIFIED-at-scale on
  this box (HdrHistogram); modeled total = `local_commit_component + 68 ms` (LABELED a model input,
  not measured here); combined target = flagged **PENDING multi-box confirmation** (M-1/M-2). This is
  the canonical `LOCAL-VERIFIED (local) + ENV-BLOCKED (WAN) → PENDING` form from `methodology.md §1`.
- **No loopback-as-cross-region claim.** The note explicitly says "No cross-region target is VERIFIED
  on one box (loopback is sub-ms; real inter-region is tens of ms)" and keeps the matrix a *declared
  model input*. The lower-bound discipline (F5) — non-overlapping serial leader-side terms
  (batch window, proposal serialization, fsync) kept in `local_commit_component` so the WAN term
  cannot make the model falsely optimistic — is correctly carried over (`methodology.md §2`, lines
  104-116). The source is the declared median dataset (cloudping), with p99/jitter ENV-BLOCKED.

## Item 4 — Honesty-rail scan — VERDICT: **APPROVE**

No number presented as measured is actually modeled/extrapolated:
- The ~380 commits/s ceiling, phase-3/4 status counts, phase-2 p99=5.51 ms are all MEASURED on this
  box with captures present and named.
- The 68 ms WAN term and the modeled commit total are explicitly LABELED model/PENDING, never VERIFIED.
- The fsync ceiling band (6.5k–14.3k) is fio-measured on this box (`run-log.md §6.1`) with invocation
  recorded; the per-node ≈ceiling/3 for 3-on-1-disk is a stated derivation, not presented as measured.
- Box spec (`run-log.md §6.1`, m6id.4xlarge / 16 vCPU / NVMe), harness invocation
  (`perf/s75-throughput.sh`, driver modes), HdrHistogram (intended-time), CO method, and fsync
  attribution (case-a, with iostat/mpstat/pidstat evidence) are all recorded together for the reported
  number. The phase-3/4 histograms are correctly excluded from latency claims as overload artifacts.
- fsync attribution names exactly one cause (case-a, implementation/fsync-on-tick-thread) with the
  disk + CPU evidence, per rail (4). PASS.

Single residual (cosmetic, non-blocking): the iostat "5–17%" phrasing in `throughput-baseline.md §7.0`
is optimistic vs the q75≈27%/peak≈60% reality (Item 2). It does not alter any verdict and is logged as
a recommended wording softening, not a required correction.

---

## OVERALL VERDICT: **APPROVE** (4/4 items pass)

Items 1-4 all pass. Methodology-note `methodology-note.md` sign-off line flipped
`☐ PENDING` → `☑ SIGNED (review-architect, 2026-06-20)`. Bulk §9 latency numbers may be accepted on
this methodology.

Most important issue (non-blocking): tighten the iostat `%util` wording from "mostly 5–17%" to a
median+range (median ~14%, q75 ~27%, peaks to ~60%) so the disk-headroom claim is stated exactly; the
attribution conclusion is unchanged.
