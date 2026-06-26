# Multi-Raft Workstream C — the re-threaded SINGLE-GROUP throughput ceiling (decides v1-vs-v2 sharding)

> **Measured 2026-06-26** on m6id.4xlarge (the SAME instance type as the §7.5 ~800/s baseline), on-demand,
> ap-south-1, instance-store NVMe. Branch `multiraft-workstream-c` (off `main` @ 5d9e374: Phase 0
> re-threading + coalesced heartbeats + Netty M1–M4 + the Phase-V Epoll-default, all merged). Harness
> `perf/wsC-ladder.sh` (derived from the §7.5 harness `perf/s75-throughput.sh` — same 3-node co-located
> launch, same CO-corrected `OpenLoopWriteDriver`, same iostat/mpstat/pidstat rails). Captures under
> `docs/multiraft/captures/wsC-ladder/`. This is a **single-node, 3-co-located-nodes-on-one-box** figure
> (labelled honestly in §6), not a dedicated-host/cross-region absolute.

## TL;DR — the answer to the question that gates the sharding decision

> **Does the re-threaded + coalesced-heartbeat single group still cap near ~800/s, or did Phase 0 raise
> the ceiling?**

**It still caps at ~800/s — essentially identical to the §7.5 pre-Phase-0 baseline. Phase 0 did NOT raise
the single-group ceiling, and structurally could not: at N=1 a single Raft group binds to exactly ONE owner
thread (`configd-raft-owner-0`).** The §7.5 root cause — the single-threaded consensus path starving its own
heartbeat under load — is **reproduced unchanged**: the cluster is stable to ~800 writes/s (0 elections, 0
failures) and collapses into leadership churn at ≥1000/s (elections multiply, throughput inverts, `503
NotLeader` dominates) **while ~14 of 16 vCPUs sit idle and the NVMe is free**.

This is the **expected and correct** result, and it is decisive for the sharding question:

- Phase 0's throughput value is the **aggregate** ceiling — *N* parallel groups each on their own owner
  thread. That is realized **only by sharding** (multi-Raft Phase 1). A *single* group's ceiling is the
  single-owner-thread ceiling, which Phase 0 deliberately left at ~800/s (it proved the heartbeat *property*
  is flat in *N*; it never claimed to raise one group's rate — that is "Workstream C on hardware", this doc).
- Therefore **the only lever that lifts sustained durable throughput above ~800/s is sharding.** Timeout
  tuning does not (§7.5 §F, negative result); admission control does not raise the ceiling either — it makes
  the ~800/s **stable under overload** instead of collapsing (§4 below).

**Recommendation (the operator makes the call):** the measured ~800/s single-group ceiling is **comfortable
for the actual config workload** (Cloudflare Quicksilver runs ~347/s *globally*; config writes are rare and
bursty; the legacy 10k/s was modeled, never validated, against a single-group design — `adr-throughput-target.md`).
So **v1 can ship the fast single group plus admission control** (graceful burst absorption, validated below),
and **multi-Raft Phase 1 (sharding) is v2-deferrable** as "horizontal scale beyond one node's ~800/s." **If,
and only if, the operator holds §0.1's 10k/s *sustained* as a hard v1 contract, sharding becomes v1-necessary**
and this measured ~800/s is the **per-shard envelope** for sizing *N* (≈16–18 at efficiency 0.7–0.8). Either
way the number is measured, attributed, and reproduced; the v1/v2 scope is the operator's to set from it.

## 0. Configuration measured (production defaults — apples-to-apples with §7.5)

| knob | value | why |
|---|---|---|
| `configd.raft.ownerPoolSize` | **1** (default) | one owner thread; the single group is owner[0] |
| `configd.write.maxInflightProposals` | **0 = OFF** (default) | matches §7.5's no-admission ladder exactly |
| `configd.groupCommit.enabled` | **true** (default) | same as the §7.5 ladder |
| `configd.netty.transport` | **epoll (forced + verified `[tier=epoll]`)** | removes the io_uring confound (Phase V: io_uring ~2× worse for consensus); matches the post-Phase-V production default |

**Comparability:** same instance *type* as §7.5 (m6id.4xlarge, 16 vCPU, instance-store NVMe) holds hardware
constant, so the throughput delta vs §7.5 is attributable to **software** (Phase 0 re-threading), not the box.
Secondary software delta vs §7.5: the consensus wire is now Netty-Epoll (§7.5 was JDK NIO). The consensus wire
was independently measured **transport-neutral** (`docs/jdk-vs-netty/`, M4 gc-proof ~0 B/op on the event loop),
and the result below (a knee identical to §7.5's) is itself evidence the transport is not the mover.

**fsync baseline** (fio, fdatasync after every 4 KiB write, on /mnt/nvme): **16,986 fdatasync IOPS, mean 56 µs,
p99 148 µs** — fsync is ~free on this instance-store NVMe (≥ §7.5's 8,300–14,300), so per-op fsync is *not* the
cost here, exactly as §7.5 established.

## 1. The rate ladder — the knee (open-loop, CO-corrected, 15 s/rate, c=256, 512 B, FRESH cluster per rate)

`elections` = Δ`configd_raft_elections_total` (max across nodes) over the rate's run — the direct
heartbeat-starvation signal.

| offered/s | achieved/s | elections | 200 | 503 | 504 | rej_bp | p50 µs | p99 µs | state |
|---|---|---|---|---|---|---|---|---|---|
| 200  | 200 | **0** | 3000  | 0     | 0   | 0     | 2389   | 8815    | **stable** |
| 400  | 400 | **0** | 6000  | 0     | 0   | 0     | 2651   | 53407   | **stable** |
| 600  | 600 | **0** | 9000  | 0     | 0   | 0     | 4339   | 111295  | **stable** |
| 800  | 799 | **0** | 12000 | 0     | 0   | 0     | 7571   | 160895  | **stable** |
| 1000 | 637 | 15 | 9634  | 5233  | 133 | 0     | 120063 | 1151999 | collapsed |
| 1200 | 640 | 16 | 9658  | 6712  | 190 | 1440  | 239743 | 5111807 | collapsed |
| 2000 | 466 | 27 | 7090  | 14101 | 168 | 8641  | 137599 | 4089855 | collapsed |
| 4000 | 425 | 30 | 7914  | 20935 | 189 | 30962 | 10     | 4341759 | collapsed |
| 8000 | 392 | 27 | 5880  | 40117 | 188 | 73815 | 4      | 3211263 | collapsed |

**The stable knee is ~800/s; the collapse sets in across a metastable 1000–1200/s band** (independently
confirmed — §5). Up to 800/s: 0 elections, 0 failures, full achievement — a healthy cluster. By 1200/s and
above: elections multiply, achieved throughput *inverts* (more offered → less committed), and `503 NotLeader`
dominates (no stable leader). The 1000/s point is metastable — across passes it ranged 562–932/s (§ variance
below) — so the onset is a band, not a single cliff; but the *sustainable* ceiling is firmly ~800/s. Elections
scale monotonically with offered load — the heartbeat-starvation signature.

### Side-by-side with §7.5 (same box, pre-Phase-0) — the comparison the whole decision hinges on

| offered/s | §7.5 achieved / elections | **WS-C achieved / elections** |
|---|---|---|
| 800  | 799 / 1  | **799 / 0** |
| 1000 | 607 / 15 | **637 / 15** |
| 1200 | 560 / 20 | **640 / 16** |
| 2000 | 490 / 28 | **466 / 27** |
| 4000 | 405 / 34 | **425 / 30** |
| 8000 | 409 / 32 | **392 / 27** |

**The two ladders are the same curve.** Phase 0's re-threading moved the single-group knee by ~0.

### Variance (800 + 1000, three fresh passes each)

| rate | pass 1 | pass 2 | pass 3 |
|---|---|---|---|
| 800  | 799/0 elec (stable) | 799/0 (stable) | 774/2 (marginal) |
| 1000 | 632/18 (collapsed) | 562/18 (collapsed) | 749/11 (collapsed) |

800/s is reliably at/near the top of the stable band (occasionally a marginal wobble); 1000/s **always**
collapses (11–18 elections every pass). The ~800/s knee is stable across repetition, not a single-run artifact.

## 2. Attribution — §7.5 case (c), single-threaded consensus path, REPRODUCED

The bottleneck is **not** fsync/disk, **not** aggregate CPU, **not** host capacity — it is consensus work
serialized on one owner thread per group, exactly as §7.5 found.

- **NOT fsync/disk:** iostat `nvme1n1` at 800/s — device flush `f/s = 0.00`, `w_await ≈ 0.04 ms`, `%util` 9–26 %.
  The WAL writes happen (~4 k w/s) and complete in ~40 µs; there is no flush penalty to amortize.
- **NOT aggregate CPU:** mpstat all-core `%idle` is **66–77 % even at the 1000/s collapse** (≈12 of 16 vCPU idle).
  The box has enormous headroom while throughput is inverting.
- **No JVM is core-bound:** pidstat at 1000/s — the leader JVM uses ~1.4–2.2 of 16 cores; followers ~1 core each.
- **The hot thread (thread-level `top -bH` of the leader at ~840/s):** the consensus work concentrates in
  **1–2 `configd-*` threads at ~80 % CPU each** (the group's owner thread `configd-raft-owner-0` plus the
  group-commit/flush thread); the next-busiest thread is the C2 compiler at 12 %, everything else (Netty event
  loops, GC, ForkJoin) is <7 %. The work cannot spread — one group is one owner thread — so it saturates 1–2
  cores while 14 sit idle.  *(independently re-derived + named by the 2nd-agent reproduction — §5.)*
- **What the owner thread spends time on (sampled):** a thread-dump sample caught `configd-raft-owner-0`
  RUNNABLE inside Ed25519 field arithmetic (`IntegerPolynomial25519.square`) — i.e. **per-entry signature
  crypto runs on the owner thread, on the critical path** (one sample, not a profile). This is a *potential
  future single-group lever* distinct from sharding (offload/batch entry signing off the owner thread); it is
  out of scope here but worth a follow-up profile, because anything that cuts owner-thread per-entry work would
  raise the single-group knee directly.
- **The smoking gun:** elections rise monotonically with offered load (0 → 15 → 16 → 27 → 30) and `503
  NotLeader` dominates above the knee. Under load the per-proposal work on the lone owner thread delays the
  scheduled heartbeat past the election timeout → a follower elects → in-flight writes get `503` → clients
  retry → thundering herd → self-reinforcing churn, while CPU and disk sit idle.

**Why Phase 0 did not (and could not) move the N=1 knee:** the owner-executor pool parallelizes consensus
*across groups* (`ownerExecutor(gid)=pool[gid mod N]`) — at N=1 there is one group, hence one owner thread,
hence the same single-thread serialization the §7.5 ceiling is made of. Coalesced heartbeats coalesce the
*idle* empty-AppendEntries (and the wire is unchanged at N=1); they reduce per-group heartbeat cost as *N*
grows, which is a flat-in-*N* aggregate property, not a single-group ceiling lift. Phase 0 proved those
properties (Workstream B) and explicitly deferred "the throughput NUMBER" to this measurement.

## 3. What this means for v1-vs-v2 (the only lever past ~800/s is sharding)

- **Sustained durable commit above ~800/s requires multiple groups (sharding).** There is no single-group
  knob that raises it: Phase 0 re-threading does not (this doc); longer election timeouts do not (§7.5 §F,
  negative result — they *halve* throughput); admission control does not raise the ceiling (§4 — it stabilizes
  ~800/s under overload, it does not push a single group to 2000/s).
- So the aggregate model in `adr-throughput-target.md` holds with a **measured** per-shard knee:
  `sustained_aggregate ≈ per_shard_knee(~800/s) × N × efficiency(~0.7–0.8)`. To hit 10k/s sustained:
  `N ≈ 10000 / (800 × 0.75) ≈ 17` shards.
- **The N×800 aggregate is NOT unbounded on one box — it is bounded by total cores.** Each active group leader
  costs ~1.4 cores (pidstat, §2), so a single 16-vCPU node saturates at roughly **⌊16 / 1.4⌋ ≈ 10–11 active
  group-leaders ≈ ~8k/s per node** before its own cores (not any one thread) become the limit. Realizing a true
  `N×800` therefore requires spreading shard *leaders* across multiple hosts (the multi-box deployment the
  topology ADR assumes), not packing all N onto one node. And the aggregate assumes load **spreads** across
  shards: a single **hot key/shard** is unsplittable and still caps at ~800/s. These bound how far sharding
  scales and are sizing inputs for N and host count, not contradictions of the thesis.

## 4. Admission control — the v1 burst lever, §7.5 §G REPRODUCED on the re-threaded code

A churning rate (2000/s offered), production binary, `-Dconfigd.write.maxInflightProposals` toggled:

| config | achieved/s | elections | HTTP outcome (15 s) | state |
|---|---|---|---|---|
| **off** (default) | 460 | 27 | 200=7459, **503=20173** | collapsed (churn) |
| **`=16`** | **848** | **1** | 200=12745, **429=17245**, 503=**10** | **stable** |

At `=16` the leader stays stable (1 election vs 27), commits at its ~800/s capacity *continuously* (848/s vs a
churning 460/s — nearly 2×), and the dominant outcome flips from `503 NotLeader` churn-collapse to clean `429
Overloaded` shedding. This **reproduces §7.5 §G exactly** (§7.5: 432 → 864 at `=16`, 1 election) on the
re-threaded + coalesced-heartbeat + Netty code. **Admission control does not raise the ~800/s ceiling — it
makes the single group reliably *deliver* its ceiling under a flood instead of collapsing**, which is precisely
the graceful burst-absorption a v1 single group needs.

## 5. Independent 2nd-agent reproduction (on-box, before teardown)

An independent agent re-derived everything with its own commands on the same instance and returned
**"~800/s knee, attribution confirmed."** It verified the box *provenance* via IMDS rather than trusting it
(`instance-id i-057c799846cf1c55c`, `m6id.4xlarge`, `nproc=16`, Corretto 25.0.3, NVMe xfs), and confirmed
`[tier=epoll]` on its own cluster.

- **Knee (CONFIRM):** its own ladder gave 600→600/0elec, 800→**799**/0elec (stable, 0×503), 1000→932/2elec
  (past the knee — 503s appear, p50 jumps 8.7→102 ms), 1200→**676**/15elec (collapsed; achieved inverts below
  799). **Sharpening:** its 1000/s point did not hard-invert (932, softer than my 637), so the collapse *onset*
  is a **metastable 1000–1200/s band, not a sharp 1000/s cliff** — consistent with the run-to-run variance in
  §1 (1000/s spanned 562–932 across passes). The *stable* knee is firmly ~800/s in both runs.
- **Attribution (CONFIRM):** box %idle 65–78 % at 800–1200/s; `f/s`=0 and `w_await`≈0.04 ms at every rate;
  leader JVM ~1.4 cores; elections monotonic with load. **Sharpening:** the busiest *core* migrated rate-to-rate
  (#11, #7, #11, #10) — the hot work is **one un-pinnable serialized thread**, not a saturated core (which is
  why per-core stats alone can't isolate it and the thread dump was needed).
- **Hot thread (CONFIRM, named):** via `jcmd <pid> Thread.print`, the three hottest threads on the box are all
  **`configd-raft-owner-0`** (each JVM has exactly one owner thread ⇒ pool size 1), the leader's at ~68 % while
  the box sat ~78 % idle; the busiest Netty event-loop thread was ~1 % (wire I/O is cheap — the cost is on the
  owner). **Honest nuance it flagged:** it is *one owner thread per node* (so three are loaded across the 3
  co-located nodes — followers also append/apply on their single owner under write load); within any one node,
  consensus is serialized on exactly one thread. Its capture window landed in the *soft* 1000/s regime (owners
  ~68 %, not pegged at 100 % mid-collapse), so the saturation→heartbeat-starvation link is inferred from the
  trend (owners climbing + elections 2→17 + box idle), not from catching the owner pinned at 100 %.

Net: the headline and attribution are independently reproduced. The verifier's nuances (metastable collapse
band; un-pinnable migrating thread; one owner-0 per node) are folded into §1/§2 above and strengthen, not
weaken, the conclusion.

## 6. Honest axis labelling

- The ~800/s figure is a **single-node** number: 3 co-located Raft nodes + a 256-thread load driver sharing one
  16-vCPU box. The **same co-location confound as §7.5**, so the *delta* (≈0) is attributable to Phase 0; the
  *absolute* knee on a dedicated one-node-per-host cluster may differ (plausibly higher — less scheduler
  contention for the lone owner thread). The qualitative finding (single-thread heartbeat-starvation churn,
  unchanged by Phase 0 at N=1; sharding is the only lever past it) is hardware-robust; the precise dedicated-host
  knee remains a deferral, as in §7.5.
- Throughput is sustained **durable quorum-committed writes** (each `PUT /v1/config` blocks to commit), not
  raw RPS. Latencies are CO-corrected (intended-time) HdrHistogram.

## 7. References (for the operator's v1/v2 call)

| reference | value | bearing |
|---|---|---|
| §7.5 as-built single group (this box, pre-Phase-0) | **~800/s** | Phase 0 did not move it — the core comparison |
| **Re-threaded single group (this measurement)** | **~800/s** | the measured per-shard envelope floor |
| Cloudflare Quicksilver (real config plane) | **~347/s global** | the real-world config write rate — ~800/s single-group exceeds it 2× |
| etcd single group + batching | **~10k/s** | the tuned single-group *envelope ceiling* on dedicated hardware — headroom exists, not yet pursued |
| Config-rollout burst | spike, not sustained | handled by admission control (§4): absorb + shed `429`, no collapse |
| §0.1 legacy target | 10k/s sustained | modeled, never validated, mis-specified vs a single-group design (`adr-throughput-target.md`) |

## 8. Recommendation (operator decides scope; this is the evidence-based lean)

1. **Default lean — ship v1 on the fast single group; defer sharding to v2.** ~800/s sustained durable writes
   comfortably exceeds the real config workload (~347/s global, bursty). Make **admission control default-on**
   (a small bound, e.g. 16, tuned per host) so bursts shed gracefully as `429 + Retry-After` instead of
   collapsing leadership. Multi-Raft Phase 1 becomes v2 "scale beyond one node," not a v1 blocker.
2. **Conditional — if 10k/s *sustained* is a hard v1 contract, sharding is v1-necessary.** The measured ~800/s
   is then the per-shard envelope; size `N ≈ 17` (10000 / (800 × 0.75)), and re-verify the rehoming mechanism
   on activation (Phase 0 D-016 caveat) and the heartbeat amplification guard as *N* scales.
3. **Independent of the above — pursue the per-shard knee on a dedicated host before freezing *N*.** The
   co-location confound (shared with §7.5) means a dedicated one-node-per-host knee could be materially higher
   than ~800/s, which would lower the *N* needed. The etcd ~10k/s single-group envelope says the headroom is
   real (batching/pipelining levers untried here).

**The decision the operator owns:** is sustained >800/s a genuine v1 requirement, or is the real workload
(bursty, ~hundreds/s) served by the fast single group + admission control with sharding deferred to v2? The
measurement says the single group caps at ~800/s and only sharding goes past it; the *need* is the operator's
to declare.

## 9. Run metadata (cost, provenance, verified teardown)

- **Instance:** `i-057c799846cf1c55c`, m6id.4xlarge, ap-south-1a, on-demand. IMDS-verified by the 2nd agent.
- **Cost:** billed duration **1905 s (~31.8 min) ≈ $0.59** (m6id.4xlarge @ ~$1.1132/hr) — within the ~$0.50–0.65
  estimate and far under the $5 charter ceiling. Dry-run on the free dev box preceded any spend.
- **Verified teardown (AWS API):** instance `terminated`; EBS `vol-012711f140edd94b5` GONE (DeleteOnTermination);
  security group `sg-059ec992b982bea04` GONE; key-pair `wsc-44561-key` GONE; local `.pem` deleted. Region sweep
  confirmed no stray wsc-* instances/SGs/keys. **Nothing left billing.**
- **Reproduce:** `docs/multiraft/scripts/` (provision/run/teardown, m6id+NVMe, $5-guardrailed) + the harness
  `perf/wsC-ladder.sh` (derived from §7.5 `perf/s75-throughput.sh`). Captures: `docs/multiraft/captures/wsC-ladder/`.
