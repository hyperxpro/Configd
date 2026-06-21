# H-3 Adversarial Review — owner-published monitor snapshot

**Reviewer:** independent adversarial verifier (tried to BREAK the claim).
**Commit under review:** `61a6bf1` ("Phase 0 B: H-3 CLOSED — owner-published monitor snapshot"), branch `phase0-B-rethreading`.
**Method:** static analysis of the whole repo + RED/GREEN break-it experiments + a live jcstress run of the H-3 classes. All experiments reverted; `git diff --stat` clean afterward.

---

## VERDICT: **CLOSED WITH CAVEATS**

The mechanism is sound, the live-reader set is exactly as the design claims, the guards neither over- nor under-fire, and the tests are non-vacuous (proven RED when the safety code is removed; the jcstress control empirically tears while the gated test never does). I could **not** break the safety claim. The caveats below are *test-durability / latent-sharp-edge* observations, not live defects:

- **C1 (test seam, not a live bug):** No test exercises the *real* `RaftMetrics` / `monitorView` field for the no-tear property. The jcstress test pins a hand-rolled mirror; the macro test uses the real type but is structurally incapable of surfacing a tear (single owner builds each immutable snapshot atomically). A future regression making `RaftMetrics` mutable or `monitorView` non-volatile would pass **both** tests.
- **C2 (latent JMM sharp edge, dormant today):** `monitorView` is a **non-final** volatile field seeded in the constructor. "Never null" holds **only because** the `RaftNode` is itself safely published (constructor → `addGroup` → executor-submit happens-before the scrape). The guarantee is contingent on safe publication of the node, not intrinsic to the field. When Workstream B introduces genuine cross-thread sharing of nodes, this must stay true or a racing reader could observe `monitorView == null` and the scrape would NPE on `view.commitIndex()`.
- **C3 (forward-looking, out of H-3 scope but adjacent):** `MultiRaftDriver.groups` is a plain `HashMap` and `getGroup()` is an unsynchronised `groups.get()` (`MultiRaftDriver.java:53,174`); the class javadoc says "must be accessed from a single thread only." H-3 retargets the scrape's *node-state* read to `monitorView()`, but the scrape still obtains the `RaftNode` **reference** via `driver.getGroup()` from the `tickExecutor` thread (`ConfigdServer.java:789`). Under the future pool this `getGroup` is itself an off-owner read of a non-thread-safe map. This is a `MultiRaftDriver` re-threading task (not yet done) and not part of the H-3 monitoring-read surface, but it sits on the same code path and must be closed before the pool lands.

---

## Mandate item 1 — Missed readers (completeness): **PASS** (design claim verified)

**Checked:** every production caller (`configd-*/src/main`) of `currentTerm/votedFor/log/transferTarget/clusterConfig/metrics/monitorView/role/leaderId`, every holder of `RaftNode`/`MultiRaftDriver`, and which modules even reference the types.

**Evidence:**
- Modules that reference `RaftNode` in `src/main`: consensus-core (defines), replication-engine (driver), server (wires), testkit + jcstress (test harnesses), transport, observability. The transport (`TcpRaftTransport.java:47,78`) and observability (`ConfigdMetrics.java:130`) "hits" are **javadoc comments only** — no field/var/call. edge/fanout/distribution/control-plane modules: **zero** `RaftNode`/`MultiRaftDriver` references.
- The ONLY production reader of `RaftNode` state is `ConfigdServer.java`:
  - Scrape loop `ConfigdServer.java:789–793` — retargeted to `tickNode.monitorView()`; the only "guarded-accessor-shaped" read is `view.currentTerm()` where `view` is the immutable `RaftMetrics` snapshot, **not** `tickNode.currentTerm()`. Safe.
  - Write-path sites `ConfigdServer.java:1229,1264` — call `node.whenCommitOutcome(...)` / `node.cancelCommitOutcome(...)` inside `raftExecutor.execute(...)`. These are **guarded mutators** (`RaftNode.java:934,955` carry `assertOwnerThread()`), not monitoring reads — out of H-3 scope (a Workstream-B owner-binding concern for the command path).
  - `leaderId()` reads on HTTP threads (`ConfigdServer.java:549,580,714`; `HttpApiServer.java:332,370,450,454`) — these are **S-set** reads. `leaderId` and `role` are confirmed `volatile` (`RaftNode.java:56–57`), so a single-reference off-thread read is JMM-safe and is *intentionally* left unguarded by design §1.4/§3. NOT H-3 holes.
- **`AdminService` is provably unwired:** `grep "new AdminService"` over all `src/main` → none; **zero** `implements ClusterStateProvider` anywhere in the repo (src/main OR src/test); `AdminService` referenced nowhere in `src/main` outside its own file. The latent-site claim (§1.2) is accurate.
- `PrometheusExporter.java:106` `snapshot.metrics()` is `MetricsRegistry.MetricsSnapshot.metrics()` (a `Map<String,Metric>`), **not** `RaftNode.metrics()` — a grep false-positive; it never touches a `RaftNode`.
- Only two scheduled loops exist in the server: `ConfigdServer.java:779` (scrape, uses `monitorView()`) and `:831` (`tlsReloadExecutor`, no RaftNode).

**Conclusion:** the design's "only live prod reader is the ConfigdServer scrape; AdminService is unwired" claim is **confirmed**. No missed off-owner reader of the five non-volatile accessors.

## Mandate item 2 — Mechanism is actually safe: **PASS**

- **Immutable?** `RaftMetrics` (`RaftMetrics.java:23`) is a `record` whose fields are `long`/`int` scalars, `RaftRole` (enum — immutable), and two `NodeId` (a `record NodeId(int id)` — deeply immutable, `NodeId.java`). **No mutable collection/array is leaked.** Genuinely immutable. Claim holds.
- **Can `monitorView()` return null?** No, in the wired server. Seed store `this.monitorView = buildMetrics()` is the **last** statement of the primary constructor (`RaftNode.java:368`); the other two constructors delegate to it. Wiring order: node constructed (`ConfigdServer.java:342`) → `driver.addGroup` (`:348`) → `tickExecutor.scheduleAtFixedRate` (`:779`), all on the bootstrap thread, so the seed happens-before the first scrape. The scrape also only calls `monitorView()` after `driver.tick()` republished on the same thread. (Caveat C2: the field is non-final, so the guarantee is contingent on safe publication of the node — fine today.)
- **Torn / partial / stale-beyond-one-tick?** Cannot tear/partial: one immutable record published by one volatile store ⇒ JMM happens-before, all fields mutually coherent. Pinned empirically by jcstress below. Bounded staleness ≤1 tick by publish-every-`tick()` (`RaftNode.java:456`).
- **"Wrong, not just stale" between ticks?** I specifically attacked the two live consumers:
  - *Elections counter* (`ConfigdServer.java:794`): `if (term > lastSeenTerm) increment(term - lastSeenTerm)`. `lastSeenTerm` persists across ticks and Raft terms are monotonic, so a ≤1-tick-stale term only *delays* the increment; the delta is still counted correctly on the next tick. **No lost increment.**
  - *Apply-backlog gauge* (`ConfigdServer.java:792`): `view.commitIndex() - view.lastApplied()` — both from the **same** snapshot, hence mutually consistent. This is strictly **more** correct than the pre-change code, which read `tnLog.commitIndex()` then `tnLog.lastApplied()` as two separate off-owner non-volatile reads (mutually torn-able). The snapshot fixes a real latent inconsistency, not just a visibility hazard.
- `buildMetrics()` (`RaftNode.java:1326`) calls `clusterConfig.peersOf(self)` (line 1330) **on the owner only**, pre-computing `replicationLagMax` into the snapshot; monitors read that scalar and never call `peersOf()`.

## Mandate item 3 — Guards don't over/under-fire: **PASS**

- **No internal self-calls to the guarded accessor *methods*.** Grep for `clusterConfig()/log()/currentTerm()/votedFor()/transferTarget()` invocations inside `RaftNode.java` returns only a javadoc line (1366). RaftNode uses the **fields** (`log`, `currentTerm`, …) internally, never its own accessor methods — so guarding the methods cannot break an internal on-owner caller.
- **The 5 accessors read non-volatile state** (`currentTerm` line 50, `votedFor` 51, `transferTarget` 88 all non-volatile; `log`/`clusterConfig` return live mutable objects) — correctly chosen for guarding; `role`/`leaderId` are `volatile` (56–57) and correctly left in the S-set.
- **Bound test contexts checked** (the guard is inert until `bindOwnerThread()`; only 4 prod/test contexts bind): `RaftNodeConcurrencyStressTest`, `RaftMonitorViewConcurrencyTest`, `AdversarialSim`, `ConsistencyPropertyTests`.
  - `AdversarialSim`: the only guarded-accessor read is `currentTerm()` at `AdversarialSim.java:228`, inside `tick()` (212–233) run from `run()` (202–209) on the **single drive thread** that also calls `bindOwnersIfNeeded()` (216). On-owner. `findLeader()` reads only `role()`. `SimInvariants.java:107` reads `currentTerm()` from `invariants.checkAll()` (AdversarialSim:232) — same drive thread.
  - `ConsistencyPropertyTests`: three `currentTerm()` reads (1710/1744/1782); the file has **zero** thread spawns / executors (single-threaded), so all reads share the drive thread with the bind. On-owner.
- The vast majority of the 126 `currentTerm()` / 84 chained `log().` / 58 `clusterConfig()` / 28 `votedFor()` test call-sites are in plain unit tests that **never bind** → guard inert → unaffected. Confirmed empirically: `RaftNodeConcurrencyStressTest` stayed 2/2 GREEN throughout.

## Mandate item 4 — Tests non-vacuous (RED/GREEN, actually run): **PASS**

GREEN baseline (unmodified): `RaftMonitorViewConcurrencyTest` 2/2, `RaftNodeConcurrencyStressTest` 2/2.

**Experiment A — remove the guard on `currentTerm()`** (`public long currentTerm() { return currentTerm; }`):
`RaftMonitorViewConcurrencyTest` → **1 FAILURE**. The red:
```
h3AccessorsTripOffOwnerWhileMonitorViewAndSSetStaySafe
org.opentest4j.AssertionFailedError: H-3 accessor currentTerm() read off-owner must trip the
  owner-thread tripwire ==> Expected java.lang.AssertionError to be thrown, but nothing was thrown.
  at RaftMonitorViewConcurrencyTest.assertTripsOffOwner(...:190)
  at RaftMonitorViewConcurrencyTest.h3AccessorsTripOffOwnerWhileMonitorViewAndSSetStaySafe(...:168)
```
⇒ the net-coverage assertion genuinely **requires** the guard; not a tautology. Reverted.

**Experiment B — make `publishMonitorView()` a no-op** (view frozen at the constructor seed):
`RaftMonitorViewConcurrencyTest` → **1 FAILURE**. The red:
```
monitorViewIsCoherentAndNeverBlocksUnderConcurrentPublish
org.opentest4j.AssertionFailedError: vacuous: commitIndex never advanced (no consensus work)
  ==> expected: <true> but was: <false>
```
⇒ the macro test genuinely observes **live end-of-tick publication** (the frozen seed has commitIndex 0 despite 4000 proposals; the `distinctCommits>1` guard has the same root cause). Reverted; GREEN restored; tree clean.

**jcstress control soundness — ran the H-3 classes live** (`configd-jcstress/target/jcstress.jar -t RaftMonitorViewPublicationTest`):
- `PublishedSnapshotNeverTears` (GATED): outcome `1` 100.00% Acceptable across every fork; **0 TORN, 0 FORBIDDEN, 0 failed** over tens of millions of samples. The volatile-published-immutable primitive never tore.
- `PerFieldPublishCanTear` (CONTROL): outcome `99 (TORN)` **observed in every fork** at 0.06%–1.08% (e.g. 223,545 torn samples in one fork). The control is **sound and live** — per-field publication genuinely tears under jcstress on this host, which is exactly the hazard the immutable-snapshot discipline prevents. (The control uses `long` fields, whose writes are individually atomic on 64-bit, so the tear is purely cross-field splicing — the correct hazard to model for `RaftMetrics`.)

## Mandate item 5 — Design integrity: **PASS** (with C1–C3 caveats)

- **Staleness ≤1 tick honored & adequate:** publish at end of every `tick()` (`RaftNode.java:456`); both live consumers tolerate ≤1-tick lag (item 2). Adequate for every consumer found in item 1 (the only ones are the gauge and the counter; AdminService is unwired).
- **`clusterConfig` lazy-`peersCache` race argument is CORRECT** — verified against source: `ClusterConfig.java:40` `private final Map<NodeId,Set<NodeId>> peersCache = new HashMap<>()`, mutated lazily by `computeIfAbsent` in `peersOf()` (`:145`). `computeIfAbsent` structurally mutates a plain `HashMap`; concurrent owner+monitor `peersOf()` is a real data race, so value-immutability is genuinely "not enough" and guarding `clusterConfig()` is the right call.
- **Constructor seeding creates no inconsistency:** the seed runs pre-bind on the wiring thread (guards inert), reads the same fully-recovered state the surrounding durable-recovery code reads, and is the last constructor statement. Sound.

---

## Things I tried and could NOT break
- Find an off-owner reader of the 5 non-volatile accessors anywhere in prod → none (item 1).
- Make `monitorView()` return null/stale/torn in the wired server → cannot (item 2; safe publication + volatile immutable).
- Find an on-owner caller (main/test/sim) broken by the new guards → none (item 3).
- Make either test pass while the safety code is removed → both go RED (item 4).
- Show the jcstress control is a no-op that would never fire → it fires every fork (item 4).
