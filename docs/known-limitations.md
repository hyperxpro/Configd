# Known Limitations — v1

> **⚠️ Current v1 known limitations (updated 2026-06-27; §3–§4 reconciled to the two EC2 runs 2026-07-01).** This section is the
> authoritative, current statement of what v1 does and does not do. The live, audited per-subsystem
> status (132 items, ✅/🟡/❌/⛔/🔬) is `docs/readiness/production-readiness-register.md`; this section
> surfaces the user- and operator-facing limitations. The dated **iter-3 note further below
> (2026-04-25)** is preserved as historical record and is **superseded by this section and the register**
> wherever they differ.

### 1. No encryption at rest (RR-098) — do NOT store secrets

Configd does **not** encrypt data at rest in v1. All config values — **including `secure/` (strong-read)
keys** — are stored as **plaintext** bytes in the control-plane HAMT / WAL / snapshot, protected by an
**integrity** envelope only (HMAC-SHA-256, ADR-0042), which detects tampering but provides **no
confidentiality**. (At edge nodes, `secure/` values are kept **in-memory only**, never written to disk —
the RR-098 mitigation — which bounds, but does not remove, the exposure.)

- **The `secure/` namespace is a *freshness* guarantee, not a security/encryption one.** `secure/` (the
  strong-read key class, ADR-0030 INV-1) means a key is **always read fresh** — linearizable, fail-closed,
  never served stale — for security-*critical decisions* like ACL/auth revocations, kill-switches, and
  legal gates. It does **not** mean the value is encrypted or confidential. Naming a key `secure/...` buys
  read **freshness**, not **secrecy**.
- **Do not store secret material** (passwords, tokens, private keys, PII) in Configd. Use a dedicated
  secret manager (e.g. Vault, a cloud KMS / secret store) and keep only non-secret references in Configd.
- **At-rest encryption is a deferred v2 item (RR-098, OPEN).** It is a *registered gap*, not a decided
  non-goal: if a v1 deployment must store sensitive data or meets a compliance bar, this is a **blocker** —
  raise it before deploying.

### 2. Client-facing watches: the RFC §2 protocol is implemented server-side (N=1); drivers + N>1 are next

v1 now implements the **server side of the RFC §2 driver-protocol watch surface** on the edge endpoint
(`--edge-port`): the `0x02` edge wire (the `WATCH_*` frames + the per-shard cursor vector), the
multiplex/filter veneer, the **whole-target authorization gate** (`READ ∧ WATCH`, reject-not-filter,
fail-closed), per-watch target-filtered delivery + catch-up snapshots, and **bounded revocation under
live ACL reload** (W7-7). It is **N=1** (single Raft group); an **N>1 multi-shard** watch is **v3** (the
watch protocol layered on top of the separately-deferred v2 sharded edge data plane) — the edge endpoint
fail-closes at N>1 unless an operator opts into the primary-shard-only partial view. The
legacy in-process `WatchService` is unrelated server-internal plumbing (register §4.8).

- **No shipped client driver yet.** The protocol is server-ready, but a **conforming client driver**
  (RFC §1+§2+§3) is the **next deliverable** — until one ships, watches are not consumable out-of-the-box.
  The legacy v1 pull pattern — `GET /v1/config/{key}` (optionally linearizable) + the edge
  bounded-staleness read path with version cursors — remains the supported read path.
- **Guarantees (rely on exactly these):** per-key and per-shard order ✅ (never cross-shard / global ❌);
  batch-atomic per shard-commit ✅; at-least-once with **`(gid, S)` dedup** (the driver drops
  `S ≤ cursor[gid]`); bounded-staleness (edge-served, **ordered not linearizable** — use the strong-read
  path for read-after-write); bounded revocation latency (≤ the edge idle-poll interval after an `_acl/`
  reload).
- **Security model a deployer MUST know** (from the Gate-1 security review — the watch path is internally
  sound; these are system-boundary/deployment conditions):
  - **Co-resident legacy SUBSCRIBE.** The same `--edge-port` also serves the pre-existing whole-store
    SUBSCRIBE fan-out, which has **no per-key ACL** (the trusted server↔edge backbone, ADR-0038). A cert
    that completes the mTLS handshake can obtain the whole store via SUBSCRIBE, bypassing the watch gate.
    The watch ACL therefore only constrains clients that **cannot** reach the legacy SUBSCRIBE path —
    deploy watch clients **segregated** from edge-cache subscribers (separate trust anchor, or the intended
    server↔edge↔client topology). NOT reachable in the default config (only `root`, which already holds
    whole-store). Hardening (gate SUBSCRIBE / segregate trust anchors) is a tracked follow-up before any
    non-root watch grant on a shared port.
  - **mTLS + explicit grant required.** A watch needs a verified cert-DN **and** an explicit `READ ∧ WATCH`
    grant to that DN; plaintext ⇒ all watches rejected (fail-closed). The default config grants watch only
    to `root`, so out-of-the-box no edge cert can watch until the operator adds an `_acl/` grant.
  - **Single-scope keyspace at N=1.** `scope` carries **no** authorization isolation (gate, store keys,
    read path, and filter are uniformly scope-blind over the flat keyspace); it is forward-compat metadata.
  - **Reserved-prefix (`_acl/`, `_system/`) ADMIN** is enforced at the HTTP boundary, not yet mirrored in
    the watch gate. A KEY/PREFIX watch cannot *name* a reserved key (the watch path grammar requires a
    leading `/`, disjoint from the flat `_acl/`/`_system/` keys); a FULL / `full_chain_verify` watch *does*
    span them, but is gated by the **root-only full-scope grant** (only `root` holds the root grant, and
    `root` is ADMIN) — so no non-root principal can observe reserved keys via any watch kind. A follow-up
    should still move the reserved-prefix rule into `AclService` so every gate inherits it before any
    non-root watch grant.
- **v1 boundaries:** per-connection **shared drain** (W8-6) — all watches on a connection share one cursor,
  one ack, and one backpressure fate (a slow watch can demote its siblings; per-watch fairness is v2); a
  connection-level catch-up snapshot maps to the drain-owning (first) watch (single-snapshotting-watch).
- **Deferred:** N>1 multi-shard watch (v3); per-watch flow-control (W10-8); the `prev_value` /
  leader-served / long-poll-gateway named extensions (W10-2/4/7); the SUBSCRIBE-co-residence +
  reserved-prefix hardening; a conforming client driver + a shared conformance suite (the next arc).

### 3. Sharding: v1 ships single-group (N=1); multi-shard is built, server-wired, and metal-proven

v1 runs a **single Raft group by default** (N=1; ADR-0030, ADR-0023). The multi-Raft sharding layer is
built, sim-verified, and **server-wired on main** (`StaticShardMap` + `shardFor` routing); at N=1 it is
byte-identical to a non-sharded build. **Reconciled 2026-07-01:** the N>1 aggregate throughput is no
longer unmeasured — it was **measured on real hardware** and is **near-linear ~2.45× on 3 machines**
(656→1075→1607 committed w/s;
`docs/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md`; register §2.11/§9.2 ✅).

- **Measured v1 write throughput:** the single-group write knee is **~800 writes/s** (register §9.1 ✅,
  m6id.4xlarge; **leadership-churn-bound**, not CPU/disk). This is **below the original §0.1 10k/s baseline**.
- The 10k/s baseline is a **sharded-aggregate** target (~535 w/s per leader-machine cross-machine ⇒
  ~17–19 machines), now evidenced as a real near-linear path — **but contingent on the leadership-balancing
  operability follow-up**: horizontal scale is **operator-managed** (one leader per box, not auto-balanced;
  see [`deployer-must-know.md` item 5](deployer-must-know.md) + go/no-go §3.2). No literal sustained 10k/s
  has been run (single-cluster max = 1607 w/s).

### 4. Empirical validation: validated on metal (2026-06-30 / 07-01), with bounded residuals

**Reconciled 2026-07-01.** The empirical claims are no longer deferred — two paid EC2 runs measured them
GREEN against a `main`-identical server (register §11.12 ✅). The honest residuals are precisely bounded:

- **Soak — 6 h clean, NOT 24 h.** The clean-code soak reached the full **6 h** flat (FD 350→350, RSS 2.6 %
  spread, heap floor stable, GC 0.92 %, 0 rejected; `docs/measurement/ec2-2026-06-30/04-soak.md`), past the
  prior 3.45 h attempt that OOM'd on **box capacity** (RR-112 — **not** a Configd leak). **Residual:** no
  full 24 h / 72 h soak has completed (register §9.7 ✅@6h). The first-30-days posture is the
  [burn-in contract](burn-in-contract.md) (go/no-go condition **C4**).
- **DR drills — executed on metal.** Leader-loss under load, WAL-replay restart, and wipe+InstallSnapshot
  were run: **372 ms** failover (1 bounded election, no storm), **0/1000** committed-write loss across all
  three modes, RTO **4.2 s** (WAL) / **5.9 s** (snapshot) (`docs/measurement/ec2-2026-06-30/02-dr-drills.md`;
  register §7.5 ✅). **Caveat:** single-box 3-co-located topology — cross-machine failover adds network RTT,
  but the correctness (no loss, bounded election) is topology-independent.
- **Residuals** (burn-in / v2): no literal sustained **10 k/s** or **100 k burst** (single-cluster max
  1607 w/s), no **cross-region / WAN** measurement (single-region by design), and the edge-staleness
  distribution at scale (INV-S2) is still owed (`consistency-contract.md` §2). See the register §11
  empirical-validation rows and the [burn-in contract](burn-in-contract.md).

---

## Historical record — iter-3 code-level pass (2026-04-25)

> The note below is the **original 2026-04-25 (iter-3)** known-limitations document, retained as a
> historical record of the v0.1 code-level GA framing. Several specifics have since drifted (e.g. the wire
> format has since bumped to v2; sessions S1–S7 + multi-Raft followed). **For current status, defer to the
> §"Current v1 known limitations" section above and to `docs/readiness/production-readiness-register.md`.**

**Authored:** 2026-04-25 (iter-3 code-level production-readiness pass).
**Companion to:** `docs/production-readiness-code-level.md`,
`docs/automation-prerequisites.md`, `docs/ga-approval.md`,
`docs/ga-review.md`.

> **Read this if you are about to sign `docs/ga-approval.md`.**
> v0.1 is **code-level** production-ready, not **empirically**
> production-ready. The user explicitly chose to defer load / soak /
> chaos / burn-in validation to production observation. This file
> states what that deferral means, concretely.

---

## What was validated

- **Code-level correctness** via 8 rounds of adversarial review
  on iter-3 (the wire-protocol v1 changes), terminating in two
  consecutive clean passes at the S0/S1 level.
- **Reactor green** at 21,394 tests, 0 failures, 0 errors,
  ~57 s wall-clock on JDK 25 + `--enable-preview`. Surefire
  evidence under `*/target/surefire-reports/TEST-*.xml`.
- **Property-based fuzzing** of `FrameCodec` (11 properties × 50–500
  tries) and `RaftMessageCodec` (12 properties × 100–200 tries).
- **Wire-format byte-equality** against checked-in fixtures pinned
  by an externally-verified CRC32C reference vector.
- **Formal model checking** (TLC) of Consensus, ReadIndex,
  SnapshotInstall — pre-existing, unchanged by iter-3.
- **CI guardrail** for fixture-bump without version-bump (added in
  pass-6, polished in passes 7–8).

## What was NOT validated

These are the empirical gates the user accepted as production-observation
items rather than pre-GA gates:

### Performance under sustained load

- 72-hour soak: not run (C1 YELLOW per `docs/ga-review.md`).
- 7-day burn-in with periodic chaos: not run (C2 YELLOW).
- 14-day shadow traffic: not run (C4 YELLOW).
- 30-day longevity: not run (C3 YELLOW).
- JMH benchmarks of the new encode/decode path with CRC32C: not run.
- Allocation profiling on hot paths under realistic traffic: not run.

**What this means in production:**

- Performance regressions surface in front of users, not before.
- The CRC32C compute cost is theoretically bounded
  (~0.5 ns/byte hardware-accelerated on x86 / ARMv8) but unmeasured
  on your traffic mix.
- Fan-out amplification, GC behaviour, and lock contention patterns
  are unmeasured until production.

### Fault recovery under real failure modes

- Real network partitions: not exercised.
- Real disk-full / fsync-stall events: not exercised (network-only
  chaos in `SimulatedNetwork`; `C-104, C-108, C-111, C-112, C-113`
  RED in `docs/ga-review.md`).
- Real cert rotation under load: not exercised (S5 YELLOW —
  production rebuild not landed; chaos-only negative path).
- Real leader-loss recovery against an SLA: not exercised
  (Runbook-conformance YELLOW; zero drills executed).
- Real restore-from-snapshot drill: not exercised
  (`ops/dr-drills/results/` empty).

**What this means in production:**

- Concurrency bugs that only manifest under sustained load surface
  during real incidents.
- The first follower-bootstrap from snapshot in production is
  the actual chaos-engineering exercise.

### Snapshot size cap (NEW iter-3 limitation)

- `MAX_SNAPSHOT_BLOB_LEN = 4 MiB` (`RaftMessageCodec`). If
  `ConfigStateMachine.snapshot()` exceeds this, the affected
  follower **cannot** bootstrap from snapshot — must catch up via
  AppendEntries from the leader's oldest retained entry. If the
  leader has compacted past entries the follower needs, that
  follower is permanently behind.
- v0.2 will implement chunked InstallSnapshot via the `offset` /
  `done` fields (already in the wire format, currently ignored by
  the leader). v0.1 ships with the cap.
- **Mitigation in v0.1:** tune snapshot policy so state stays
  under 4 MiB at snapshot time. Monitor per-follower `matchIndex`
  lag against leader `commitIndex` as a proxy for "follower is
  stuck because snapshot install rejected".
- **Operator-visible signal:** stderr line
  `"Dropping InstallSnapshot to ... (codec rejected — snapshot too
  large for v1 wire)"`. No Prometheus metric exports this yet
  (W5 carryover).

### Wire-version mismatch alerting

- v1 is the first version, so no v0/v1 mixed traffic exists today.
- v2 will land with a Hello handshake (ADR-0030+, not yet authored)
  to negotiate version. Until then, mixed-version traffic terminates
  the connection with `UnsupportedWireVersionException`.
- **Operator-visible signal:** stderr line
  `"Inbound wire-version mismatch (observed=0xNN); dropping
  connection"`. No metric.

### Encoder-drop observability

- `RaftTransportAdapter.send` re-throws `IllegalArgumentException`
  from the encoder; `RaftNode.sendAppendEntries` /
  `sendInstallSnapshot` catch it, log to stderr, skip the
  `inflightCount` increment, and return.
- This prevents the cluster-wide outage that pass-3 / pass-4
  identified, but **no metric counter exports drop frequency** —
  it's stderr only. Operators must scrape logs.
- **W5 carryover** wires `ConfigdMetrics.raftOutboundDrop({type, reason})`
  in a separate observability pass.

### Test-coverage instrumentation

- Line / branch coverage on safety-critical modules: not measured
  this pass. Reactor pass-rate is 100 %, but jacoco was not run.
- Mutation-testing score on consensus-core / config-store: not
  measured.

---

## What "production-ready code-level" specifically means here

When `docs/production-readiness-code-level.md` certifies the iter-3
diff as production-ready, that statement is bounded to:

- Every public symbol introduced by iter-3 has Javadoc, a negative
  test, and an explicit boundary check at every untrusted-input
  edge.
- The §3 phase-4 adversarial-review protocol terminated cleanly:
  two consecutive passes by independent reviewers found 0 S0 and
  0 S1 issues.
- The build produces verifiable evidence (surefire reports, fixture
  files, ADR document).
- Failure modes are documented (ADR-0029, this file) such that an
  operator can correlate a production symptom to its root cause
  without reverse-engineering the code.

It does NOT mean:

- The system has been observed performing under realistic load.
- Failure modes have been observed actually firing in production.
- Recovery procedures have been observed working in production.
- The first 30 days are not the actual burn-in.

---

## First 30 days = the burn-in

Per the prompt's `<the_tradeoff_being_made>`: **"the first 30 days
of production are de facto burn-in, with associated incident risk."**

The operator's job during that window is documented in
`docs/production-readiness-code-level.md` §7. In summary:

- Heightened alerting on the stderr substrings listed in §7 item 1.
- Daily error-budget review.
- No concurrent feature deploys.
- Predefined rollback triggers (4 specific ones in §7 item 4).
- Named on-call rotation per `docs/operator-runsheet.md` step 7
  must be in place before the burn-in starts.

If the cluster passes the 30-day window without triggering any
rollback, the empirical-validation gap is closed by observation.
Until then, **this is a code-level certification operating as an
empirically-unproven contract.**

---

## What to do if you are the GA approver

1. Read `docs/ga-review.md` for the gate-by-gate state.
2. Read `docs/production-readiness-code-level.md` for the iter-3
   delta and the file manifest.
3. Read this file (`docs/known-limitations.md`) so you understand
   what your signature is asserting and what it is NOT asserting.
4. Read `docs/automation-prerequisites.md` for the calendar-bounded
   gates that remain non-promotable.
5. Read `docs/operator-runsheet.md` so you know the operator-side
   work that must precede signing.
6. Make an informed signing decision on `docs/ga-approval.md`. The
   loop / iter-3 / this certification all stop short of that
   signature by design — per `<the_tradeoff_being_made>` and the
   §4.7 honesty invariant, the human signature is the thing the
   automation cannot do.

If after reading these files you are not comfortable signing,
that is the system working as designed. The deferral was your
explicit decision; revisit the deferral, not the certification.
