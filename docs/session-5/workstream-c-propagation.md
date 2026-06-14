# Workstream C — Propagation / Edge-Staleness (CT-02) — Session 5 Performance Validation

> **Owner:** `load-test-engineer`. **Branch:** `session-5-performance`.
> **Binding methodology:** `docs/session-5/methodology.md` (signed off). Every number below cites
> its CO treatment (§3c), its LOCAL-VERIFIED / ENV-BLOCKED label (§1), and reports the
> HdrHistogram p50/p99/p999/p9999 + tail-bin sample count (§4 / F1).
> **Collector:** all runs under **generational ZGC** (ADR-0041). **JDK:** 25 Corretto
> (`25+36-LTS`). **Box:** AWS t3a.large, 2 vCPU / 7.7 GB, burstable (CPU-credit throttling real).
> **Contract:** `docs/consistency-contract.md` §2 — CT-02 / INV-S1 / INV-S2.

---

## 0. Honesty split up front (what this box can and cannot prove)

| §0.1 / CT-02 target | This doc's result | Label |
|---|---|---|
| Edge propagation / staleness **p99 < 500 ms, p9999 < 2 s, global** | local fan-out staleness component measured (SIM + live Compose); the global target = this local component **+ modeled WAN leg** | **LOCAL-VERIFIED (local fan-out component) + ENV-BLOCKED (WAN leg) → manifest M-2** |
| The **owed INV-S2 p99-distribution** (S3 deferred it) | produced: SIM deterministic distribution + live multi-edge distribution, both HdrHistogram | **CLOSED at the local-component level** (the mechanism number S3 owed; the global SLO stays M-2) |

The global p99 < 500 ms target is **never** marked fully VERIFIED on this single-region single-host
box. Per methodology §1, a target whose name contains "global"/"cross-region" cannot be green on one
box — that is the exact dishonesty Session 1 was built to catch. The local fan-out component is
measured and reported; the WAN leg (1–3 Plumtree hops from the §2 RTT matrix) is modeled and
ENV-BLOCKED (manifest **M-2**).

### The measured local staleness, in one place

| Run | Scope | p50 | p99 | p999 | p9999 | max | samples | label |
|---|---|---|---|---|---|---|---|---|
| **SIM** (deterministic mechanism) | local fan-out, 3 edges | 0 ms | **1 ms** | 1 ms | 1 ms | 1 ms | 4317 | LOCAL-VERIFIED |
| **LIVE Compose** 3 CP + 3 edge | all-edges aggregate | 124 ms | **255 ms** | 395 ms | 398 ms | 398 ms | 1140 | LOCAL-VERIFIED (local component) |
| **LIVE in-process** 1 CP + 1 edge | edge, real wire path | 12 ms | **76 ms** | 96 ms | 96 ms | 96 ms | 500 | LOCAL-VERIFIED (smaller boundary) |

All three are **inside** the CT-02 contract targets (p99 < 500 ms, p9999 < 2 s) **for the local
component**. Global = local + WAN (M-2), reported `PENDING real-hardware confirmation`.

---

## 1. What CT-02 / INV-S2 is, and what was owed

The consistency contract §2 defines the staleness measure and bound (ADR-0035 / ADR-0039 frontier):

```
INV-S1: staleness(e,t) := wall_now(e,t) − commit_ts(last_applied_notification(e,t))
        where commit_ts is the leader-assigned commit-notification timestamp (ADR-0035).
INV-S2 (no partition):  P(staleness > 500ms) < 0.01 (p99);  P(staleness > 2s) < 0.0001 (p9999)
```

**The debt.** The contract §7 row and `contract:214` record that `StalenessUpperBoundTest` /
`StalenessTracker` today assert only the state-machine THRESHOLD transitions
(CURRENT→STALE→DEGRADED→DISCONNECTED), **not a measured p99 distribution**. The
contract-test-map (`docs/session-3/contract-test-map.md` CT-02) sanctioned the deferral: "mechanism
delivered (S3), Session 5 sets and measures the numbers." **This doc is that deliverable** — the
actual INV-S2 distribution against leader-assigned commit timestamps. The mechanism (probe both
modes, ADR-0039 frontier) was already built; S5 owed the SLO numbers.

---

## 2. Coordinated-omission discipline (methodology §3c — stated for the record)

Staleness is `wall_now(edge) − frontier`, sampled **at the edge on a FIXED wall-clock cadence** whose
clock does **not** pause when the data plane stalls. A stalled propagation therefore shows up as a
**growing staleness sample**, never a dropped one — the signal we want. The **write** side is driven
**open-loop** (intended-time schedule in SIM; a background open-loop writer to the Raft leader live).
This is the §3c treatment verbatim. Single-host Compose ≈ shared clock, so the inter-host clock-skew
term is ≈ 0 here; the cross-host skew term is ENV-BLOCKED (it is the ±50 ms NTP residual the contract
names, exercised in SIM by the leader's skewed commit clock).

**The CO trap we hit and fixed (a real finding).** The pre-S5 live `--mode edge` probe drove ALL
writes to completion and *then* drained the edge cursor (serial drive-then-watch). That folded the
entire write-drive duration into every early sample — it is exactly the coordinated-omission failure
mode in reverse, and it is why the gate-3 capture reported a (honest-but-misleading) **p99 = 4871 ms**
on a loaded box. The §3c-correct sampler runs the edge watcher **concurrently** with the open-loop
write drive. Before/after on the **same 500 writes**, same box, ZGC, 1 CP + 1 edge in-process, real
wire path (`docs/session-5/captures/wsC-live-edge-1cp1edge.txt`):

| Sampling | p50 | p99 | p999 | max |
|---|---|---|---|---|
| serial drive-then-watch (pre-S5 **artifact**) | 2223 ms | **5099 ms** | 5243 ms | 5243 ms |
| concurrent fixed-cadence (§3c, **honest**) | 12 ms | **76 ms** | 96 ms | 96 ms |

~67× difference, entirely a harness artifact. The conclusion: **the local commit→edge propagation on
this loaded box is tens of ms, not seconds.** The S3-recorded "p99=4871ms" was a measurement-of-the-
generator, not of the system — corrected here (`--concurrent-watch true`, now the default).

---

## 3. Deliverable 1 — SIM staleness distribution (the deterministic mechanism number)

**Harness:** `configd-testkit/src/test/java/io/configd/testkit/EdgeStalenessDistributionLoadSimTest.java`
(new). It runs the `EdgeFanOutSim` (3 CP + 3 edges) under a **sustained write load** over a no-fault
schedule, driving the production C2 path (`C1StreamDriver` → `FanOutSessionCore` → `EdgeClientCore`,
ADR-0039 frontier), and feeds a `PropagationProbe` publish→visible samples: publish ts = the leader's
commit timestamp (`CommitNotification.commitTimestampMillis()`, ADR-0035 §2); visible ts = the edge's
logical apply time. The staleness sample is `visibleTs − publishTs` — exactly INV-S1.

**Load.** 3000 ops over 12000 ticks (the sim advances 1 ms/tick); ops spread uniformly across
`[ticks/10, ticks·0.95]` (~10.2 s of sim time); 60% PUTs → ~1800 commits → **≈175 commits/s of sim
logical time** — comparable to the box-bound ceiling Workstream B measured (~125–172 commits/s), so
this is an honest "under load" propagation number, not a single-write microbench. The CP→edge sim
network adds 1–10 ms/hop; the leader commit clock carries the ±50 ms NTP-skew error term.

**Result** (`docs/session-5/captures/wsC-sim-load-dist.txt`, deterministic, ZGC):

```
STALENESS-LOAD-DIST: scope=local-fanout edges=3 cp=3 ops=3000 ticks=12000
  samples=4317 p50=0ms p99=1ms p999=1ms p9999=1ms max=1ms
  tailCnt[>=p99]=51 tailCnt[>=p999]=51 tailCnt[>=p9999]=51
```

| Scope | count | p50 | p99 | p999 | p9999 | max |
|---|---|---|---|---|---|---|
| edge-100 | 1454 | 0 | 0 | 1 | 1 | 1 ms |
| edge-101 | 1402 | 0 | 0 | 0 | 0 | 0 ms |
| edge-102 | 1461 | 0 | 1 | 1 | 1 | 1 ms |
| **global** | **4317** | **0** | **1** | **1** | **1** | **1 ms** |

The test **asserts** INV-S2 for the local component (p99 < 500 ms, p9999 < 2 s) and passes with vast
margin. Tail-bin count (F1): ~51 samples sit in the 1 ms bucket; 99 % of samples are 0 ms. **Reading:
in the deterministic model the local fan-out staleness is sub-millisecond-to-1 ms — the local budget
is nowhere near the 500 ms constraint.** This is the mechanism number, and it is the floor the WAN leg
adds onto.

**Invocation:**
```
flock /tmp/configd-mvn.lock ./mvnw -o -pl configd-testkit -am test \
  -Dtest=EdgeStalenessDistributionLoadSimTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine="-XX:+UseZGC --enable-preview"
```

CO note (§3a-style): there is no fixed-cadence arrival schedule whose skipped slots vanish; a stalled
delivery enlarges the one `visibleTs − publishTs` sample (the sim's logical clock does not pause), so
CO is structurally absent here — same argument the methodology makes for the in-process harnesses.

---

## 4. Deliverable 2 — LIVE multi-edge staleness (the real-but-not-WAN number)

**Compose env brought up cleanly and stably** on the 2-vCPU box: `deploy/compose/compose.yaml`,
**3 control-plane + 3 edge containers**, full mTLS Raft + signed fan-out chain (images rebuilt from
the freshly-built shaded jars, mind the shaded-jar trap). All 3 CP reached `/health/ready` in 11 s
(leader elected = cp2); all 3 edges reached ready (first verified sync) in ~1 s thereafter. **The full
multi-edge live env is NOT ENV-BLOCKED — it ran.**

**Measure.** Each edge exposes `edge_staleness_ms` (the live `wall_now − frontier` gauge, ADR-0039 /
contract §2 INV-S1; `EdgeNodeMetrics` line 141). A **fixed wall-clock cadence sampler** scrapes that
gauge from every edge (`docker exec … curl /metrics`, partition-proof and clock-independent — §3c)
while an **open-loop writer** drives committed writes to the Raft leader. Samples are reduced with
HdrHistogram via the new `io.configd.probe.StalenessSampleHistogram`.

**Window:** 180 s. **Achieved write rate:** **3.8 commits/s** (681 committed writes; box-sustainable
— `docker exec` scraping on the 2-vCPU box with 6 JVMs competes hard for the two cores, which throttles
both writer and sampler; the rate is real and honest, and per §3c the staleness measure is
**independent** of it). **Edge count:** 3. **Samples:** ~380/edge, 1140 aggregate.

**Result** (`docs/session-5/captures/wsC-live-compose-3cp3edge.txt`, raw samples committed as
`wsC-live-compose-edge{1,2,3}.samples`):

| Scope | count | p50 | p90 | p99 | p999 | p9999 | max | tail≥p99 | tail≥p9999 |
|---|---|---|---|---|---|---|---|---|---|
| edge1 | 383 | 124 | — | 252 | 335 | 335 | 335 ms | 4 | 1 |
| edge2 | 379 | 127 | — | 256 | 386 | 386 | 386 ms | 5 | 1 |
| edge3 | 378 | 121 | — | 256 | 398 | 398 | 398 ms | 4 | 1 |
| **all-edges** | **1140** | **124** | **227** | **255** | **395** | **398** | **398 ms** | 13 | 1 |

**Verdict:** the live multi-edge local fan-out staleness is **p99 = 255 ms, p999 = 395 ms,
p9999 = 398 ms** — comfortably **inside** the CT-02 targets (p99 < 500 ms, p9999 < 2 s) **for the
local component**.

**Two honesty flags on this number:**
1. **Tail confidence (F1).** p99 is backed by 13 aggregate samples (moderate); p999 by 2 and p9999 by
   1 (**low-confidence**). The 180 s window at the box-throttled 3.8 commits/s could not populate the
   extreme tail densely. The p99 is credible; **p9999 = 398 ms is reported low-confidence**, not
   VERIFIED-tail. A denser tail needs a longer/faster run on hardware that is not docker-exec-bound —
   which is the same M-2 hardware. The SIM distribution (§3) is the dense-tail complement.
2. **What the ~120 ms p50 / ~250 ms p99 floor actually is.** It is **not** propagation delay — the
   in-process probe (§5) shows commit→edge propagation is ~12–76 ms. It is the **frontier-refresh
   interval**: the frontier advances on a commit-apply OR a cursor-matched HEARTBEAT (250 ms
   `heartbeatMs`), so between advances `wall_now − frontier` grows up to ~one heartbeat interval — at a
   low 3.8 commits/s write rate, most samples land mid-interval. Both are legitimate components of the
   `edge_staleness_ms` gauge; the floor is the heartbeat cadence, not a slow data plane. (This is the
   ADR-0039 design working: an idle-but-heartbeating edge stays CURRENT — it does not march to
   DISCONNECTED — and the staleness reads the heartbeat-bounded frontier age.)

**Invocations** (env up → sample → reduce):
```
# bring up 3 CP + 3 edge (no bootstrap profile)
docker compose --project-directory deploy/compose -f deploy/compose/compose.yaml up -d
# open-loop writer + fixed-cadence (per-edge) edge_staleness_ms sampler, 180s window
#   (writer: PUT to leader, retry-across-churn; sampler: docker exec curl /metrics | awk edge_staleness_ms)
# reduce per edge + aggregate to HdrHistogram:
cat <edge>.samples | java --enable-preview -cp configd-testkit/target/benchmarks.jar \
  io.configd.probe.StalenessSampleHistogram <scope> <cadence_ms>
docker compose --project-directory deploy/compose -f deploy/compose/compose.yaml down -v
```

---

## 5. Deliverable 2b — LIVE in-process boundary (1 CP + 1 edge, the real wire path)

The smaller live boundary measures **commit→edge-visibility wall time through the real wire path**
(server fan-out endpoint → socket → Ed25519 verify → apply → edge applied cursor) on one box, isolating
the propagation latency from the heartbeat-floor that dominates the multi-edge gauge.

**Harness:** `LivePropagationProbeMain --mode edge` (now with `--concurrent-watch`, §2). 500 writes,
ZGC, achieved write rate **76.7 commits/s** (`docs/session-5/captures/wsC-live-edge-concurrent-500.txt`
and `wsC-live-edge-1cp1edge.txt`):

| Run | p50 | p99 | p999 | p9999 | max | rate | samples |
|---|---|---|---|---|---|---|---|
| concurrent §3c, 500 writes | 12 ms | **76 ms** | 96 ms | 96 ms | 96 ms | 76.7 c/s | 500 |

This confirms the **propagation component proper is ~12–76 ms p99** on the loaded box — the real
commit→edge wire latency. (The serial-watch artifact figures in §2 are retained in the same capture as
the explicit before/after.)

---

## 6. CT-02 disposition (recommendation — lead applies to the register/contract row)

**The contract row to quote** (`docs/consistency-contract.md` §7 / INV-S1-S2; `contract:214`):

> | INV-S1/S2 | `StalenessUpperBoundTest` | … | Today asserts **threshold transitions** against the
> proxy idle-time clock (CM-049), NOT a p99 staleness distribution. The p99-distribution test
> (INV-S2) is **owed to Session 3** … only then can the distribution be measured against real data
> age. |

and the contract-test-map row (`docs/session-3/contract-test-map.md` CT-02): currently
**PARTIAL(unit)**, residual = "the real p99 < 500 ms distribution over real propagation latency,
Session 5's."

**Recommended disposition: PARTIAL → VERIFIED (local fan-out component) + ENV-BLOCKED (WAN leg, M-2).**

- The **owed INV-S2 p99-distribution is now produced** at the local level, three ways (SIM
  deterministic p99=1 ms; live Compose 3-edge p99=255 ms; live in-process p99=76 ms), all HdrHistogram,
  all **inside** the contract targets (p99 < 500 ms, p9999 < 2 s) for the local component. The S3 debt
  is discharged at the local-component level.
- The **global** p99 < 500 ms / p9999 < 2 s target stays **ENV-BLOCKED (manifest M-2)**: global =
  local fan-out component (measured here) + modeled WAN leg (1–3 Plumtree hops, §2 RTT matrix). It is
  **never** marked fully VERIFIED on this single-region box. The modeled total is `PENDING real-
  hardware confirmation`; the exact infra is M-2 (3 CP us-east-1 + edges in eu-west-1 / ap-northeast-1 /
  ap-southeast-1 / us-west-2; ~7–8 instances × ~3–4 h; ~$3–4).
- **Two flags carried on the live number:** (a) the multi-edge tail is thin (p9999 backed by 1 sample
  — low-confidence/F1; the SIM distribution is the dense-tail complement); (b) the ~120 ms floor is
  the 250 ms heartbeat frontier-refresh interval, not propagation delay (the in-process probe shows
  propagation is ~12–76 ms).

**No register edit performed** (the lead owns the register, ci.yml, and gate scripts). This doc is the
citation.

---

## 7. Reproducibility / artifacts (methodology §4)

| Artifact | Path |
|---|---|
| SIM load-distribution harness (new) | `configd-testkit/src/test/java/io/configd/testkit/EdgeStalenessDistributionLoadSimTest.java` |
| SIM raw capture | `docs/session-5/captures/wsC-sim-load-dist.txt` |
| Probe tail-count accessor (new) | `PropagationProbe.globalCountAtOrAbove` (`configd-testkit/src/main/java/io/configd/probe/PropagationProbe.java`) |
| Live edge probe §3c concurrent watcher (changed) | `configd-testkit/src/main/java/io/configd/probe/LivePropagationProbeMain.java` (`--concurrent-watch`) |
| Live-sample HdrHistogram reducer (new) | `configd-testkit/src/main/java/io/configd/probe/StalenessSampleHistogram.java` |
| Live in-process 1 CP + 1 edge capture (incl. before/after artifact) | `docs/session-5/captures/wsC-live-edge-1cp1edge.txt`, `wsC-live-edge-concurrent-500.txt` |
| Live Compose 3 CP + 3 edge capture + raw samples | `docs/session-5/captures/wsC-live-compose-3cp3edge.txt`, `wsC-live-compose-edge{1,2,3}.samples`, `wsC-live-compose-writes.log` |

**Regression check (methodology rule 4):** the existing safety/mechanism tests stay green with the
probe changes — `EdgeStalenessDistributionSimTest` (1), `EdgeStalenessFrontierSimTest` (2),
`ProbeMechanismTest` (4 — the determinism digest is unchanged with/without the probe attached),
`EdgeStalenessDistributionLoadSimTest` (1). All under ZGC + `--enable-preview`.
