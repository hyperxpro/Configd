# Known Limitations - v1

> **Current v1 known limitations (updated 2026-06-27; sections 3 - 4 reconciled to the two EC2 runs 2026-07-01).** This section is the
> authoritative, current statement of what v1 does and does not do. The live, audited per-subsystem
> status (132 items) is `docs/archive/readiness/production-readiness-register.md`; this section
> surfaces the user- and operator-facing limitations. The dated **iter-3 note further below
> (2026-04-25)** is preserved as historical record and is **superseded by this section and the register**
> wherever they differ.

### 1. At-rest encryption is available (OFF by default); with it OFF, do NOT store secrets

Configd can now encrypt data at rest, but it is **OFF by default**. With encryption OFF (the default),
all config values - **including `secure/` (strong-read) keys** - are stored as **plaintext** bytes in the
control-plane HAMT / WAL / snapshot, protected by an **integrity** envelope only (HMAC-SHA-256, ADR-0042),
which detects tampering but provides **no confidentiality**. (At edge nodes, `secure/` values are kept
**in-memory only**, never written to disk - the in-memory-only mitigation at edge nodes - which bounds,
but does not remove, the exposure.)

- **The `secure/` namespace is a *freshness* guarantee, not a security/encryption one.** `secure/` (the
  strong-read key class, ADR-0030 INV-1) means a key is **always read fresh** - linearizable, fail-closed,
  never served stale - for security-*critical decisions* like ACL/auth revocations, kill-switches, and
  legal gates. It does **not** mean the value is encrypted or confidential. Naming a key `secure/...` buys
  read **freshness**, not **secrecy**.
- **With encryption OFF, do not store secret material** (passwords, tokens, private keys, PII) in Configd.
  Use a dedicated secret manager (e.g. Vault, a cloud KMS / secret store) and keep only non-secret
  references in Configd.
- **Turning encryption ON.** Set `-Dconfigd.raft.encryption.enabled=true` (or env
  `CONFIGD_ENCRYPTION_AT_REST=true`). This encrypts the WAL, snapshot blob, and durable Raft state with
  **node-local AES-256-GCM** at the ADR-0042 seam (a new `algId=2` envelope; the GCM tag replaces the
  HMAC, the CRC32C corruption layer stays). The default `local` key provider derives the encryption root
  by HKDF from the cluster signing key (domain-separated from the integrity/audit keys); an external
  **Vault Transit** KMS provider (`configd-kms-vault`) also ships for off-host key custody and is discovered
  via `ServiceLoader` (select with `-Dconfigd.raft.encryption.kms.provider=vault-transit`), with the per-node
  custody secret sealed in a versioned `raft-kms-root` carrier. No wire-format change, no
  cluster-wide key distribution. See
  [`deployer-must-know.md` section 1](deployer-must-know.md) for the full enabling procedure and the
  operator warnings summarised below.
- **Enabling is a ONE-WAY DOOR.** Once any `algId=2` record is written, encryption **cannot be disabled**
  and the binary **cannot be rolled back** to a pre-encryption version - recovery fails closed (a
  non-encrypting reader refuses the `algId=2` records). There is **no supported disable path in v1**; treat
  it as permanent for a given data directory. Enabling on a node with existing plaintext/HMAC
  (`algId=0/1`) records does **not** rewrite them - they stay plaintext until a snapshot/compaction; enable
  from first boot or force a compaction, and use `-Dconfigd.raft.encryption.requireEncrypted=true` to
  refuse legacy records once the plaintext prefix is gone.
- **Fate-sharing + key rotation.** With `local`, confidentiality fate-shares with the signing key:
  a signing-key compromise decrypts all at-rest data. **Key rotation is now non-destructive by
  construction** (`NodeKeyring`): the persisted, dual-slot keyring holds independent random per-term roots,
  so a term rotation (`rotateTerm`) or a signing-key rotation (`rewrapForNewSigningKey`, which rewraps every
  retained root under the new signing key's KEK before the swap) leaves all prior `algId=2` data readable —
  old-term data still decrypts. The residual is that rotation is **offline/operator-serialized in v1** (no
  online admin trigger yet, see [`deployer-must-know.md` section 1](deployer-must-know.md)) and that key
  **loss** is still permanent: **back up the signing key before enabling encryption; losing or destroying it
  means permanent, unrecoverable loss of all encrypted data.** Off-host key custody is available via the
  external Vault Transit KMS provider (above).
- **Not encrypted at rest (v1):** the **audit log stays HMAC-only**, so audit metadata (config key
  **names**, principals) is not confidential. **N>1 note (LOW, inherited):** the GCM AAD binds the
  artifact-type magic but **not** the Raft groupId, and one key manager is shared across all groups -
  nonce uniqueness is global and safe, but cross-group at-rest *integrity* (a record spliced from group
  B's WAL into group A's) is caught only by the Raft log-consistency layer, not the envelope; bind
  groupId into the AAD before an N>1 deployment relies on cross-group at-rest integrity.
- **Write-path overhead (measured, release SHA eb9b293).** Encryption ON vs OFF, single node, 256 B values:
  sustained throughput knee 1210 -> 1180 w/s (-2.5%), commit latency p50 7.65 -> 7.77 ms (+1.5%), p99 14.6 ->
  36.4 ms (+150%). Low on throughput and median; the cost is tail-weighted (per-record AES-GCM + ciphertext
  allocation roughly doubles p99). Single loopback node, so this is the local encrypt-on-write cost only (no
  cross-node replication fsync in the path) - a floor. See `docs/measurement/ec2-drive-to-green-2026-07-02/gate7-final/`.

### 2. Client-facing watches: the RFC section 2 protocol is implemented server-side (N>=1, single- and multi-shard); a conforming Java reference client + conformance suite now ship

v1 now implements the **server side of the RFC section 2 driver-protocol watch surface** on the edge endpoint
(`--edge-port`): the `0x02` edge wire (the `WATCH_*` frames + the per-shard cursor vector), the
multiplex/filter veneer, the **whole-target authorization gate** (`READ  and  WATCH`, reject-not-filter,
fail-closed), per-watch target-filtered delivery + catch-up snapshots, and **bounded revocation under
live ACL reload** (W7-7). **Multi-shard (N>1) client-facing watches are delivered in v1** by the
server-side aggregating coordinator: one `FanOutSessionCore` per covered shard behind one connection, every
event tagged `(gid, S)`, a per-shard cursor vector, coalesced `WATCH_CREATED` / `WATCH_PROGRESS` vectors,
and independent per-shard resume (mixed TAIL / SNAPSHOT_FIRST). Every serving node hosts replicas of all N
Raft groups, so the scatter-gather is in-process and leadership-independent. What is **not** yet built is
the disjoint sharded-edge topology (edges serving shard subsets, the driver merging client-side - the same
wire, more connections) - a v2 item. The legacy in-process `WatchService` is unrelated server-internal
plumbing (register section 4.8).

- **A conforming client driver now ships.** A conforming **Java** reference client (`configd-client` +
  `-core`/`-http`/`-edge`) and a `configd-conformance` suite (wired into CI, exercising both planes against
  the golden wire vectors) now ship, so watches are consumable out-of-the-box on the JVM. Drivers in other
  languages are buildable from the stand-alone RFC (`docs/rfc/driver-protocol/`) and validate against the same
  goldens. The legacy pull pattern - `GET /v1/config/{key}` (optionally linearizable) + the edge
  bounded-staleness read path with version cursors - remains a supported read path.
- **Guarantees (rely on exactly these):** per-key and per-shard order YES (never cross-shard / global NO);
  batch-atomic per shard-commit YES; at-least-once with **`(gid, S)` dedup** (the driver drops
  `S <= cursor[gid]`); bounded-staleness (edge-served, **ordered not linearizable** - use the strong-read
  path for read-after-write); bounded revocation latency (<= the edge idle-poll interval after an `_acl/`
  reload).
- **Ordering is per-shard, never global (W6-2a / W5-4b).** Two events with different `gid` are
  **concurrent** - a driver MUST NOT infer order from arrival sequence, from `S` magnitude across shards, or
  from `commit_ts` (a per-leader wall clock). A `with_initial_snapshot` at N>1 is **per-shard-current** (each
  covered shard snapshots at its own `snapshot_seq`, captured at different instants), **NOT** a cross-shard
  consistent cut - there is no global clock to take one against.
- **Completeness stalls, never lies (never a silent partial).** Coverage is driven by the shard set
  (`shardIds()`), never inferred from the client's cursor. A lagging or unreachable shard surfaces as a
  **frozen `WATCH_PROGRESS` component while `server_now` advances** - it is never a silent gap and never
  fails the whole watch; the healthy shards keep delivering, and the frozen component is the explicit
  "this shard is behind" signal.
- **Shared-fate backpressure across a connection's shards (W8-6 / W10-8).** The N shard substreams behind
  one connection share **one** connection-level `CURSOR_ACK` scalar and **one** `SlowConsumerGovernor` fate,
  so a single slow shard can demote or quarantine its siblings (cross-shard head-of-line blocking). Per-
  (watch, shard) flow control is v2. Segregate latency-sensitive watchers onto their own connections.
- **`GAP_UNRECOVERABLE` recovery is re-list + re-create (W5-9a).** When a component has fallen too far
  behind even for a snapshot, the server sends `WATCH_CANCELED(GAP_UNRECOVERABLE)` with **`has_oldest=0`**
  (no `oldest` vector) at both N=1 and N>1; a driver recovers by re-listing current state and re-creating
  the watch from its own last-held cursor vector. The per-shard `oldest` smart-resume payload is a v1.x
  follow-up.
- **KEY vs FULL / `full_chain_verify` shard reach.** A KEY watch resolves to exactly **one** shard
  (`shardFor(scope, key)`); a PREFIX / FULL / `full_chain_verify` target scatters to **all N** shards
  (`shardIds()`) and is authorized over the **whole** target - FULL / `full_chain_verify` requires the
  **root-scope** grant (W7-3). A `full_chain_verify` / FULL watch now covers **all** shards under one
  whole-store authorization (the Gate-3 F1 fix - it is no longer primary-shard-only).
- **Single-scope at N>1 (B5).** `scope` still carries **no** authorization isolation (the gate, store keys,
  read path, and filter are uniformly scope-blind over the flat keyspace); v1 is effectively single-scope
  (GLOBAL). Scope-aware ACLs are a prerequisite before any multi-scope watch at N>1.
- **Security model a deployer MUST know** (from the Gate-1 security review - the watch path is internally
  sound; these are system-boundary/deployment conditions):
  - **Co-resident legacy SUBSCRIBE (now authorized).** The same `--edge-port` also serves the
    pre-existing whole-store SUBSCRIBE fan-out (the trusted server-to-edge backbone, ADR-0038). With
    auth **ON** it is now gated at admission on a **whole-store READ cover** - a root-prefix `READ` grant
    with no intersecting `READ` deny anywhere; `WATCH` is not required (SUBSCRIBE is a read feed). A cert
    that completes the mTLS handshake but lacks root READ is refused `NOT_AUTHORIZED` before any frame
    flows, so a watch-only principal (subtree `READ  and  WATCH`, no root READ) can no longer escalate to
    the whole store via SUBSCRIBE - **the prior bypass is closed**. The edge/hydration identity (the edge
    node's cert-DN) MUST therefore hold READ over the root prefix `""` or edge hydration is refused.
    **Authentication is not authorization:** the gate is active **only when ACL/auth is enabled**
    (an `--auth-token` is set), which is **decoupled from TLS**. With auth **OFF** but mTLS **ON** (the
    `--tls-*` triple set, no `--auth-token`) the authorizer is absent, so **every valid edge cert still
    pulls the whole store** - per-cert trust plus network segregation is the operator's only control in
    that posture (see [`deployer-must-know.md` section 2](deployer-must-know.md)). Over a **plaintext**
    edge port with auth **ON** the identity is the literal `"plaintext"`, denied unless `"plaintext"`
    holds root READ. **Admission-time only (no bounded revocation):** unlike a watch (re-authorized on
    every `_acl/` policy-version bump), a SUBSCRIBE is authorized once at admission and never re-checked -
    revoking an edge identity's root READ does NOT tear down its existing whole-store feed, which streams
    until reconnect; revoke by disconnecting the session or rotating the edge cert. This is a broader
    exposure (the whole store) with weaker revocation (none) than a watch. NOT reachable-as-a-bypass in
    the default config (only `root`, which already holds whole-store). **Primary-shard-only at N>1.**
    Unlike a WATCH (multi-shard-complete), the legacy whole-store SUBSCRIBE plane serves only the
    **primary** shard's keys at N>1 - a partial keyspace view (a downstream cache would believe it holds
    the whole store). The server therefore **refuses** a legacy SUBSCRIBE connection **per-connection** at
    N>1 (`BAD_SUBSCRIBE`, zero data frames) unless the operator sets
    `-Dconfigd.edge.allowPartialShardView=true` to accept the primary-only view explicitly.
    `allowPartialShardView` gates **only** this legacy SUBSCRIBE plane - a multi-shard WATCH is served at
    N>1 regardless of the flag. At N=1 the flag is never consulted (one shard is the whole keyspace), so
    the legacy plane is byte-identical to a non-sharded build.
  - **Server-side prefix filtering (ADR-0045), default ON, is a trusted-domain posture.** A prefix-scoped
    edge that opts in (`configd.edge.accept_filtered=on`, `0x03` wire) has its stream filtered to its prefix
    set server-side, cutting egress. Whole signed deltas are dropped (never rewritten - per-delta Ed25519
    stands), strong-read (`secure/`) keys are always shipped, and the edge learns the covered-through position
    from the HEARTBEAT (a dense covered-S cursor + forward-only version chain). **The trust boundary:** the
    edge trusts the server's covered-S assertion. A genuine data-loss gap (ring eviction) is still caught
    **server-side** and re-snapshotted; a **delivered `NOTIFY` whose position regresses below the applied
    version** is caught **edge-side** (the forward-only gap check) and resynced; a regressed covered-S on the
    HEARTBEAT is safely **ignored** (the edge advances its covered cursor monotonically, never regresses it);
    but a **well-formed suppression of a matching delta** behind a correct covered-S is **not** edge-detectable
    - sound only within the co-located mTLS domain. **Set
    `configd.edge.fanout.filter=off` (restore the full chain) the moment a separate or untrusted relay tier
    terminates the fan-out** - a two-way door. This posture only ever narrows what a narrow edge already asked
    for; it never widens exposure (an edge still needs whole-store READ to subscribe at all).
  - **Strong-read prefix drift (prerequisite, not a v1 hazard).** The always-shipped strong-read prefix set is
    the **hardcoded `StrongReadKeyClass.DEFAULT` (`secure/`)** on the edge, but **config-driven**
    (`--strong-read-prefixes`) on the server. If an operator overrides the server's strong-read prefixes, the
    two could **drift**, so the edge might not treat a server-strong-read key as always-store. **Non-exploitable
    today** - strong-read reads bypass the edge copy entirely via the linearizable root ReadIndex - but this
    drift MUST be closed (thread the configured set to the edge) before any edge-local-serve feature for
    strong-read keys.
  - **mTLS + explicit grant required.** A watch needs a verified cert-DN **and** an explicit `READ  and  WATCH`
    grant to that DN; without mTLS all watches are rejected (fail-closed). The default config grants watch only
    to `root`, so out-of-the-box no edge cert can watch until the operator adds an `_acl/` grant.
  - **Single-scope keyspace at N=1.** `scope` carries **no** authorization isolation (gate, store keys,
    read path, and filter are uniformly scope-blind over the flat keyspace); it is forward-compat metadata.
  - **Reserved-prefix (`_acl/`, `_system/`) ADMIN** is enforced at the HTTP boundary, not yet mirrored in
    the watch gate. A KEY/PREFIX watch cannot *name* a reserved key (the watch path grammar requires a
    leading `/`, disjoint from the flat `_acl/`/`_system/` keys); a FULL / `full_chain_verify` watch *does*
    span them, but is gated by the **root-only full-scope grant** (only `root` holds the root grant, and
    `root` is ADMIN) - so no non-root principal can observe reserved keys via any watch kind. A follow-up
    should still move the reserved-prefix rule into `AclService` so every gate inherits it before any
    non-root watch grant.
- **v1 boundaries:** per-connection **shared drain** (W8-6) - all watches on a connection share one cursor,
  one ack, and one backpressure fate (a slow watch can demote its siblings; per-watch fairness is v2); a
  connection-level catch-up snapshot maps to the drain-owning (first) watch (single-snapshotting-watch).
- **Deferred:** the disjoint sharded-edge topology (edges serving shard subsets, driver-side merge - v2);
  a globally-ordered cross-shard watch (out of scope by design - no global clock); the legacy whole-store
  SUBSCRIBE multi-shard lift (it stays primary-shard-only at N>1); the `GAP_UNRECOVERABLE` per-shard
  `oldest`-vector population (v1.x, W5-9a); per-watch flow-control (W10-8); the `prev_value` / leader-served /
  long-poll-gateway named extensions (W10-2/4/7); the reserved-prefix watch-gate hardening. (The conforming
  Java client + shared conformance suite that this list previously named as "the next arc" have **shipped**.)

### 3. Sharding: v1 ships single-group (N=1); multi-shard is built, server-wired, and metal-proven

v1 runs a **single Raft group by default** (N=1; ADR-0030, ADR-0023). The multi-Raft sharding layer is
built, sim-verified, and **server-wired on main** (`StaticShardMap` + `shardFor` routing); at N=1 it is
byte-identical to a non-sharded build. **Reconciled 2026-07-01:** the N>1 aggregate throughput is no
longer unmeasured - it was **measured on real hardware** and is **near-linear ~2.45x on 3 machines**
(656->1075->1607 committed w/s;
`docs/archive/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md`; register sections 2.11 and 9.2).

- **Measured v1 write throughput:** the single-group write knee is **~800 writes/s** (register section 9.1,
  m6id.4xlarge; **leadership-churn-bound**, not CPU/disk). This is **below the original 10k/s baseline**.
- The 10k/s baseline is a **sharded-aggregate** target (~535 w/s per leader-machine cross-machine ->
  ~17-19 machines), now evidenced as a real near-linear path. Leadership placement is now maintained by a
  built-in **auto-balancer** (below); note the measured 2.45x was captured under **manual** one-leader-per-box
  placement, so the balancer is built and E2E-tested but **not yet load-measured at scale** (see
  [`deployer-must-know.md` item 5](deployer-must-know.md)). No literal sustained 10k/s
  has been run (single-cluster max = 1607 w/s).
- **Leadership auto-balance ships (on by default); manual transfer is also exposed.** Post-failover
  leadership can drift (multiple groups' leaders piling onto one box collapses the aggregate toward the
  single-group plateau). v1 ships a **decentralized leadership auto-balance loop** (`LeaderBalanceLoop`, one
  per node, `configd.raft.autobalance.*`, **enabled by default** at N>1; it *sheds* at most one over-owned
  leader per cycle, never pulls) that re-spreads leadership back toward one-per-box without operator action.
  An operator can also redistribute manually via the ADMIN-gated
  `POST /v1/admin/groups/{groupId}/transfer-leadership?target=<nodeId>` (refused when auth is off or ADMIN
  cannot be evaluated; a bad target no longer wedges writes - the Raft §3.10 transfer-abort resumes writes
  after the election timeout). The balancer is built and E2E-tested (`LeadershipAutoBalanceE2ETest`) but the
  2.45x horizontal number predates it (measured under manual placement), so it is not yet proven at scale.
  What remains v2 is **transfer-on-graceful-shutdown** (SIGTERM flips readiness to draining but does not hand
  off leadership first).

### 4. Empirical validation: validated on metal (2026-06-30 / 07-01), with bounded residuals

**Reconciled 2026-07-01.** The empirical claims are no longer deferred - two paid EC2 runs measured them
GREEN against a `main`-identical server (register section 11.12). The honest residuals are precisely bounded:

- **Soak - 6 h clean, NOT 24 h.** The clean-code soak reached the full **6 h** flat (FD 350->350, RSS 2.6 %
  spread, heap floor stable, GC 0.92 %, 0 rejected; `docs/archive/measurement/ec2-2026-06-30/04-soak.md`), past the
  prior 3.45 h attempt that OOM'd on **box capacity** (not a Configd leak). **Residual:** no
  full 24 h / 72 h soak has completed (register section 9.7, verified at 6h). The first-30-days posture is the
  [burn-in contract](burn-in-contract.md).
- **DR drills - executed on metal.** Leader-loss under load, WAL-replay restart, and wipe+InstallSnapshot
  were run: **372 ms** failover (1 bounded election, no storm), **0/1000** committed-write loss across all
  three modes, RTO **4.2 s** (WAL) / **5.9 s** (snapshot) (`docs/archive/measurement/ec2-2026-06-30/02-dr-drills.md`;
  register section 7.5). **Caveat:** single-box 3-co-located topology - cross-machine failover adds network RTT,
  but the correctness (no loss, bounded election) is topology-independent.
- **Faulted linearizability - a real Jepsen-grade matrix that FOUND and FIXED a bug (E1).** The
  earlier evidence was a 15-second, N=1, quorum-preserving *smoke* (`run-gate.sh`, 4 seeds). E1
  (2026-07-10) replaced it with a real **adversarial combination-nemesis matrix**: `kill -9`+restart,
  `iptables -j REJECT` partitions (single + multi-node **quorum-breaking**), `SIGSTOP`/`SIGCONT` pauses,
  `iptables -m statistic` packet loss, `libfaketime` clock skew, and overlapping combinations, on **N=3 and
  N=5** across at-rest-encryption / auth / clock-skew / **multi-shard** postures, checked by the trusted
  Porcupine checker, discrimination re-proven (both seeded bugs turn the checker RED on HEAD). **The matrix
  found a real linearizability bug** on the pre-fix bytes (`299ba14`): a phantom-absent linearizable read
  (404/absent for a committed-and-acked present key) served by a fresh leader whose ReadIndex omitted the
  current-term-no-op gate (Raft §6.4). It was **fixed in this arc** (`RaftNode.readIndex()` gate, commit
  `5a0e20f`; regression `ReadIndexNoOpBeforeServeTest`) and the full matrix re-ran
  **every-history-LINEARIZABLE on the fixed code**. Results pinned under
  `docs/measurement/e1-faulted-linz-2026-07-10/`; the standing CI job now runs this matrix, not the smoke. **Residual (honest):** asymmetric / partial (bridge, non-transitive) partitions need per-pair
  source-addressed cuts and remain a **netns follow-up** (the single-host loopback substrate cannot do them);
  the same safety edge is already stressed by pauses + isolation + quorum-breaking combinations. Endurance
  (E2, the ≥72 h soak) is a separate, still-pending arc — see the soak note below.
- **Edge-staleness distribution (INV-S2) - re-run pending with the gap-quarantine fix.** The on-metal
  INV-S2 distribution run this arc surfaced a real edge-fan-out bug: a perfectly caught-up edge
  (`cursor == lastAckedSeq`) under any sustained write stream past the fan-out buffer capacity was
  spuriously gap-demoted and quarantined, freezing its frontier and ramping measured staleness to the
  histogram ceiling. The staleness **mechanism** itself validated (a subscribed edge hovers 2-251 ms,
  bounded by the 250 ms heartbeat). The bug is now **fixed** (transient lock-free-read-race GAPs are
  distinguished from a genuine fall-behind; register section 4.13). **DONE: re-run on release SHA eb9b293,
  bound MET with large margin** - 4 edges/500 w/s/180 s: p99 24 ms, p9999 117 ms; 1 edge/100 w/s/30 min:
  p99 13 ms, p9999 212 ms, max 232 ms (bound p99 < 500 ms / p9999 < 2 s met ~10-38x). A faithful deep-tail
  measurement at high multi-edge density wants dedicated edge hardware (single-box co-location occasionally
  starves an edge JVM); the clean per-edge steady-state distribution is representative and the bound is met.
  See `docs/measurement/ec2-drive-to-green-2026-07-02/gate7-final/`.
- **Residuals** (burn-in / v2): no literal sustained **10 k/s** or **100 k burst** (single-cluster max
  1607 w/s), no **cross-region / WAN** measurement (single-region by design), no full **24 h / 72 h** soak,
  and a faithful **INV-S2 deep-tail at high multi-edge density** wants dedicated edge hardware (the release-SHA
  re-run above met the bound on the clean per-edge distribution; single-box co-location limits the multi-edge
  p9999). The definitive C3 and the INV-S2 bound are both DONE on the release SHA (above). See the register
  section 11 empirical-validation rows and the [burn-in contract](burn-in-contract.md).

---

## Historical record - iter-3 code-level pass (2026-04-25)

> The note below is the **original 2026-04-25 (iter-3)** known-limitations document, retained as a
> historical record of the v0.1 code-level GA framing. Several specifics have since drifted (e.g. the wire
> format has since bumped to v2; iterative development through v1 followed). **For current status, defer to the
> "Current v1 known limitations" section above and to `docs/archive/readiness/production-readiness-register.md`.**

**Authored:** 2026-04-25 (iter-3 code-level production-readiness pass).
**Companion to:** the iter-3 production-readiness code-level review, the automation-prerequisites doc,
the GA approval doc, and the GA review doc (these are archived pre-v1 artifacts).

> **Read this as the historical context for the v0.1 code-level framing.**
> v0.1 is **code-level** production-ready, not **empirically**
> production-ready. The decision was made to defer load / soak /
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
- **Property-based fuzzing** of `FrameCodec` (11 properties x 50-500
  tries) and `RaftMessageCodec` (12 properties x 100-200 tries).
- **Wire-format byte-equality** against checked-in fixtures pinned
  by an externally-verified CRC32C reference vector.
- **Formal model checking** (TLC) of Consensus, ReadIndex,
  SnapshotInstall - pre-existing, unchanged by iter-3.
- **CI guardrail** for fixture-bump without version-bump (added in
  pass-6, polished in passes 7-8).

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
- Real cert rotation under load: not exercised (S5 YELLOW -
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

### Snapshot transfer: chunked (4 MiB total-state ceiling lifted), bounded by follower heap

- The old `MAX_SNAPSHOT_BLOB_LEN = 4 MiB` was a **total-state** ceiling: a snapshot
  larger than one frame was dropped at the sender and the follower wedged. Chunked
  `InstallSnapshot` now streams a large snapshot as ordered chunks (each <= the 4 MiB
  per-**chunk** cap, default chunk 1 MiB), driven off the follower's echoed
  `nextExpectedOffset`, so the total-state ceiling is lifted. Consensus semantics are
  unchanged (transport-only): the follower installs only after the whole snapshot is
  reassembled in order and `matchIndex` advances only on that install.
- **The total is now bounded by the follower's HEAP, not by disk.** The receiver
  reassembles the whole snapshot in memory to apply it. A fail-closed cap
  (`configd.raft.maxReassembledSnapshotBytes`, default **512 MiB**, effective value
  clamped to ~2 GiB - the max single-array size) refuses a snapshot that would exceed it
  **before it can OOM** the follower: the partial is dropped, a `SEVERE` line is logged
  (`refusing InstallSnapshot reassembly ... exceed the reassembly cap`), and no install
  occurs - **no OOM, no corruption**, but that follower **stays out of quorum until an
  operator raises the cap** (or trims state). The cap **must exceed the largest expected
  total committed state**; the state has to fit in heap to be applied regardless -- and the
  transient peak during a transfer is **~2-3x the snapshot size** (growable reassembly
  buffer plus the final-array copy at install), so size the heap with ~3x-the-snapshot
  headroom, not merely above the cap (see
  [`deployer-must-know.md` section 4](deployer-must-know.md)).
  **Disk-spilling reassembly** (streaming chunks to disk rather than buffering the whole
  snapshot in heap) is a **v2** item - today reassembly is heap-bound.
- **Over-cap observability (updated - the metrics now exist).** The Gate-2 observability arc shipped the
  dedicated series this item previously said were missing: `raft_shard_snapshot_reassembly_refused_<gid>`
  (the over-cap reassembly refusal, per shard), `configd_snapshot_bytes` (snapshot size vs the per-chunk
  cap), and `raft_shard_replication_lag_max_<gid>` (the real per-follower replication-lag / wedge proxy that
  replaces the `matchIndex`-lag-by-proxy guidance). The `SEVERE` log line still fires per occurrence; it is
  now backed by real metrics rather than log-watch alone.
- **Deploy-ordering requirement (silent corruption risk):** the chunked protocol
  reuses the existing `offset`/`done` fields and there is **no wire-version negotiation**
  for the Raft RPC codec. A **pre-chunking** follower ignores those fields and installs
  **chunk 0 as the whole snapshot**, silently corrupting its state, for any snapshot
  larger than one chunk. **All nodes must be upgraded together**; single-chunk transfers
  (<= chunk size) remain safe. See
  [`deployer-must-know.md` section 4](deployer-must-know.md).
- **Mitigation:** upgrade the whole cluster together; keep state within the reassembly
  cap; monitor per-follower `matchIndex` lag against leader `commitIndex` as the proxy
  for "follower stuck (snapshot could not be installed)".

### Wire-version mismatch alerting

- v1 is the first version, so no v0/v1 mixed traffic exists today.
- The Raft RPC codec (`RaftMessageCodec`) has **no wire-version byte** and no
  negotiation; the transport `FrameCodec.WIRE_VERSION` guards only the frame envelope.
  A payload-layout change to a Raft RPC (e.g. the chunked-transfer
  `InstallSnapshotResponse.nextExpectedOffset` field) is therefore **not** caught by a
  version tripwire between mixed-code peers -- which is why cross-version snapshot
  transfer is a deploy-ordering requirement (above), not a negotiated capability.
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
  identified. **Updated (Gate-2):** drop frequency is now exported via
  `configd_raft_transport_frames_dropped`, `raft_shard_append_send_rejected_<gid>`,
  `raft_shard_snapshot_chunk_send_rejected_<gid>`,
  `configd_raft_transport_inbound_connections_refused`, and
  `configd_raft_transport_connection_decode_dropped_total` - it is no longer stderr-only.
- No outbound-drop counter exists today, and none is planned as a
  standalone item: the consensus core (`RaftNode`) has no event-counter
  sink (only an `InvariantChecker`, which throws in test/sim and is
  wrong for an *expected* operational drop, and an immutable
  point-in-time `RaftMetrics` gauge record). A real counter would need
  a metrics seam threaded through the server-layer transport
  (`RaftTransportAdapter`, which is where the encoder `IllegalArgumentException`
  originates) - genuine cross-module surface. For the dominant
  (snapshot) case the chunked-InstallSnapshot cap-lift removes the drop
  path entirely, so the burn-in instrumentation to build first is the
  proactive pair the burn-in contract names - a snapshot-size-bytes
  gauge and a per-follower `matchIndex`-lag gauge - not a reactive
  drop counter (`docs/operations/burn-in-contract.md`, section 2C).

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
- The section 3 phase-4 adversarial-review protocol terminated cleanly:
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

The operator's job during that window is documented in the iter-3
production-readiness code-level review, section 7. In summary:

- Heightened alerting on the stderr substrings listed in that section, item 1.
- Daily error-budget review.
- No concurrent feature deploys.
- Predefined rollback triggers (4 specific ones in item 4).
- Named on-call rotation per the operator runsheet, step 7,
  must be in place before the burn-in starts.

If the cluster passes the 30-day window without triggering any
rollback, the empirical-validation gap is closed by observation.
Until then, **this is a code-level certification operating as an
empirically-unproven contract.**

---

## What to do if you are the GA approver

1. Read the GA review doc for the gate-by-gate state.
2. Read the production-readiness code-level review for the iter-3
   delta and the file manifest.
3. Read this file so you understand
   what your signature is asserting and what it is NOT asserting.
4. Read the automation-prerequisites doc for the calendar-bounded
   gates that remain non-promotable.
5. Read [`operator-runsheet.md`](operator-runsheet.md) so you know the operator-side
   work that must precede signing.
6. Make an informed signing decision. The
   loop / iter-3 / this certification all stop short of that
   signature by design - the human signature is the thing the
   automation cannot do.

If after reading these files you are not comfortable signing,
that is the system working as designed. The deferral was your
explicit decision; revisit the deferral, not the certification.
