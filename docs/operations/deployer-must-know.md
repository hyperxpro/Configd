# Deployer MUST-KNOW -- Configd v1

These are **system-boundary and deployment conditions the server does NOT enforce for
you** -- distinct from the runsheet's server-side gates
([`operator-runsheet.md`](operator-runsheet.md)). Source: the v1 readiness review
([`../archive/readiness/v1-go-no-go-2026-07-01.md`](../archive/readiness/v1-go-no-go-2026-07-01.md),
section 4, the MUST-KNOW list, and section 5.1, operability caveats).

Read all ten. Each is: **the requirement - why it matters (the concrete failure
if ignored) - what to do.**

---

## 1. At-rest encryption is OFF by default -- enable it if you store secrets, and heed the one-way door

- **Requirement.** At-rest **confidentiality** is now AVAILABLE but **OFF by
  default**. With encryption OFF (the default), Configd's at-rest protection is
  **tamper-detection (integrity), not encryption**: config values -- **including
  `secure/` keys** -- are stored as **plaintext `byte[]`** under a keyed
  HMAC-SHA-256 integrity envelope (ADR-0042), which detects tampering but does
  **nothing** to hide the bytes. The `secure/` prefix buys read **freshness**
  (fail-closed linearizable reads -- see [runsheet Gate 6](operator-runsheet.md)),
  **not secrecy**.
- **Why it matters.** With encryption OFF, anyone with read access to the data dir,
  a snapshot, or a backup reads every value in the clear. If you store sensitive
  data or face a compliance bar, you MUST either keep secrets in a **dedicated
  secret manager** (store only references in Configd) **or** turn on encryption at
  rest.
- **What to do -- enabling encryption at rest.** Set
  `-Dconfigd.raft.encryption.enabled=true` (or env `CONFIGD_ENCRYPTION_AT_REST=true`).
  This encrypts the WAL, snapshot blob, and durable Raft state with **node-local
  AES-256-GCM** at the ADR-0042 seam (a new `algId=2` envelope; the GCM tag replaces
  the HMAC). The default key provider is `local`: the encryption root is
  **HKDF-derived from the cluster signing key**
  (`-Dconfigd.raft.encryption.kms.provider=local`, the only provider built in v1;
  naming any other provider is a **fail-loud startup refusal**, never a silent
  downgrade). No wire-format change, no cluster-wide key distribution, no new boot
  dependency.
- **ONE-WAY DOOR (read this before enabling).** Once **any** encrypted (`algId=2`)
  record is written, you **cannot disable encryption and cannot roll the binary
  back** to a pre-encryption version: recovery **fails closed** -- a reader without
  the encryption capability refuses the `algId=2` records rather than mis-read them,
  so the node cannot replay its own WAL/snapshot. **There is no supported "disable
  encryption" path in v1; treat enabling as permanent for a given data directory.**
  (To move a node back to plaintext you must stand up a fresh data dir under a build
  that still writes plaintext and re-replicate into it -- there is no in-place
  downgrade.) This is the same irreversibility CockroachDB/etcd/Vault document for
  storage encryption.
- **BACK UP THE SIGNING KEY BEFORE ENABLING -- it is the ONLY copy of your
  encryption root.** With the `local` provider the encryption root is *nothing but*
  HKDF(signing key): there is **no separate key file, no escrow, no recovery code**.
  The signing key and the KMS root are the **same custody object**, so the signing
  key inherits the **full gravity of an encryption master key** -- give it the same
  backup, access-control, and rotation discipline you would give a cloud-KMS root.
  **If you lose or destroy the signing key, every `algId=2` record on every node
  that derived its root from it is PERMANENTLY UNRECOVERABLE**, exactly as for a
  lost KMS master key -- there is no escape hatch. Before enabling encryption:
  (1) **back up the signing key to durable, off-host storage** kept separate from
  the data dir and its backups (the D-1 co-location guard already forbids
  co-locating them), and (2) **test-restore it** -- bring a node up from the key
  backup plus a snapshot and confirm recovery succeeds -- so you know the backup is
  good *before* you hold irreplaceable ciphertext.
- **Enable from FIRST BOOT (or force a compaction).** Enabling on a node that
  already holds plaintext / HMAC records (`algId=0/1`) does **not** rewrite them --
  they stay plaintext on disk until a snapshot/compaction rewrites them encrypted.
  To avoid a plaintext residue, enable encryption from a node's **first boot**, or
  **force a compaction/snapshot after enabling** so the historical WAL prefix is
  rewritten as ciphertext and truncated. To then *enforce* that only encrypted
  records are ever accepted (defending a rollback/replay of an old plaintext WAL
  segment), set `-Dconfigd.raft.encryption.requireEncrypted=true` -- the reader then
  REFUSES any `algId=0/1` record. Set this **only after** the plaintext prefix has
  been compacted away, or the node will refuse to start.
- **Key rotation is now NON-DESTRUCTIVE (but offline in v1) + fate-sharing.** The at-rest keys are held in
  a persisted, dual-slot **keyring** (`NodeKeyring`, wired at boot from `ConfigdServer`) that keeps independent
  random per-term roots, decoupled from the signing key. Rotation is **crash-atomic and non-destructive by
  construction**: a **term** rotation (`rotateTerm`) appends a fresh root and **retains every old term** (new
  writes stamp the new term; old data still decrypts under its retained root); a **signing-key** rotation
  (`rewrapForNewSigningKey`) rewraps every retained root under the new signing key's KEK and writes the new
  slot **before** the operator swaps `signing-key.bin`, so all prior data still verifies and a crash on either
  side of the swap boots on the matching slot. This corrects the earlier "rotating the signing key orphans /
  bricks all `algId=2` data" caveat. **The residual is that rotation is OFFLINE / operator-serialized in v1**
  (there is no online admin trigger yet - do a term/signing-key rotation as an out-of-band maintenance action
  on a stopped node). Confidentiality still **fate-shares with the signing key** under `local` (a signing-key
  compromise decrypts all at-rest data), and key **loss** is still permanent: back up the signing key.
  **Off-host key custody now ships:** an external **Vault Transit** KMS provider (`configd-kms-vault`) is
  discovered via `ServiceLoader` and selected with `-Dconfigd.raft.encryption.kms.provider=vault-transit`; it
  seals a per-node custody secret in a versioned `raft-kms-root` carrier (`KmsSealedRootStore`), moving the
  root of trust off-host so confidentiality no longer fate-shares with a co-located signing key. Other cloud
  KMS/HSM backends can be added behind the same SPI without a core edit; only `local` and `vault-transit` ship
  in v1.
- **Not covered by encryption at rest (v1).** The **audit log stays HMAC-only** --
  audit metadata (config key **names**, principals) is **not** encrypted at rest.
  The edge stays memoryless (no at-rest surface). A live-node RAM adversary is out
  of scope (every at-rest scheme draws this line). (See the readiness review,
  [section 4](../archive/readiness/v1-go-no-go-2026-07-01.md), item 4.)

## 2. Grant the edge hydration identity whole-store READ -- SUBSCRIBE is gated on it

- **Requirement.** If you enable `--edge-port` with auth ON, the **edge / hydration
  identity** (the edge node's mTLS cert-DN) MUST hold **`READ` over the root prefix
  `""`** -- otherwise its whole-store `SUBSCRIBE` is refused `NOT_AUTHORIZED` at
  admission and edge hydration never starts.
- **Rollout ordering (this is a breaking change with auth ON).** The gate is new:
  existing edge certs that lack root READ are now **refused `NOT_AUTHORIZED` at
  SUBSCRIBE**. Grant the edge / hydration identities root READ **before** upgrading,
  or hydration breaks on restart.
- **Why it matters.** The same `--edge-port` serves the pre-existing **whole-store
  `SUBSCRIBE` fan-out** -- the trusted server-to-edge backbone (ADR-0038). It is now
  gated at admission on a **whole-store READ cover** (a root-prefix `READ` grant with
  no intersecting `READ` deny anywhere; `WATCH` is not required -- a `SUBSCRIBE` is a
  read feed, not a watch), enforced by `AclServiceWatchAuthorizer`
  (`configd-server/.../fanout/AclServiceWatchAuthorizer.java`). A cert that completes
  the mTLS handshake but lacks root READ is refused before any frame flows. This
  **closes the prior watch-gate bypass**: a watch-only principal (subtree
  `READ  and  WATCH`, no root READ) can no longer escalate to the whole store by opening
  a raw `SUBSCRIBE`. See
  [`known-limitations.md` section 2](known-limitations.md) ("Co-resident legacy
  SUBSCRIBE (now authorized)").
- **Authentication is not authorization -- mTLS ON but auth OFF still leaks the whole
  store.** The gate is active **only when ACL/auth is enabled** (an `--auth-token` is
  set), and auth is **decoupled from TLS** (`ServerConfig.authEnabled()` vs
  `tlsEnabled()`). In the **mTLS-REQUIRED-but-no-auth-token** posture the authorizer is
  absent, so **any valid edge cert still pulls the whole store** -- the transport is
  authenticated but nothing is authorized. There, **per-cert trust** (who you issue edge
  certs to) **plus network segregation** of the raw `SUBSCRIBE` listener from untrusted
  clients remains your **only** control -- do not drop it in that posture. Over a
  **plaintext** edge port with auth ON, the identity is the literal `"plaintext"`,
  denied unless `"plaintext"` holds root READ.
- **A whole-store-READ grant IS a whole-store read (including a live change stream).**
  Granting an identity root READ authorizes it to pull the entire store via `SUBSCRIBE`
  **and** to receive a **live `NOTIFY` change stream of the whole store** -- not just a
  point-in-time snapshot (`WATCH` capability is not required for this). That is the
  feed's purpose, not a bypass. Do not grant root READ to a principal you intend to
  restrict to a subtree; use a per-subtree grant, and reserve root READ for the
  edge/hydration backbone (and `root`).
- **Default config.** Out-of-the-box only `root` holds the root grant (and `root`
  already holds the whole store), so nothing is over-exposed by default.

## 3. `scope` is NOT a tenant-isolation boundary at N=1

- **Requirement.** Do **not** rely on the `scope` field (`GLOBAL/REGIONAL/LOCAL`)
  for authorization or tenant isolation.
- **Why it matters.** At N=1 the ACL gate, the store keys, the read path, and the
  watch filter are **uniformly scope-blind** over a single flat keyspace; `scope`
  is **forward-compat metadata** for future sharding/namespacing, not an
  authorization dimension ([`known-limitations.md` section 2](known-limitations.md), lines
  62-63; [readiness review section 4 item 5](../archive/readiness/v1-go-no-go-2026-07-01.md)). A grant or a
  watch is decided on the **key**, independent of scope -- so two "tenants"
  separated only by `scope` share one authorization namespace.
- **What to do.** Achieve tenant isolation with **distinct key prefixes + ACL
  policy** (`_acl/` roles/policies with deny-precedence), not with `scope`. Treat
  `scope` as a routing/label hint only.

## 4. Upgrade ALL nodes together -- chunked snapshot transfer corrupts a not-yet-upgraded follower

- **Requirement.** This version streams a large Raft snapshot as **chunks** (lifting the
  old 4 MiB single-frame ceiling). A node running this code MUST NOT send a chunked
  (multi-chunk) `InstallSnapshot` to a node running an **older** build. Therefore:
  **upgrade every node in a cluster to this version together** -- do NOT run a
  mixed-version cluster where any node's committed state exceeds the chunk size
  (default 1 MiB) while an old node is still in the cluster.
- **Why it matters.** The chunked protocol reuses the pre-existing `offset`/`done`
  fields on `InstallSnapshot`, and there is **no wire-version negotiation** for the
  Raft RPC codec. An **old** follower (pre-chunking) **ignores `offset` and `done`**
  and installs **chunk 0 as if it were the whole snapshot**, then marks itself
  installed at the snapshot's full `lastIncludedIndex`. The result is **silent state
  corruption**: the follower believes it holds committed state it never received, and
  nothing errors or alerts. This happens for **any snapshot larger than one chunk**
  (the exact case this feature exists to serve).
  - **Single-chunk transfers are safe.** A snapshot that fits one chunk is sent
    exactly as before (`offset = 0`, `done = true`, whole blob), which an old node
    handles correctly. Only **multi-chunk** transfers to an old node corrupt it.
  - This cannot be fixed in code retroactively -- the vulnerable receiver is the
    **already-deployed old build**. It is a **hard deploy-ordering requirement**.
- **What to do.**
  1. **Coordinated upgrade.** Bring the whole cluster to this version in one rollout.
     Avoid a long mixed-version window; in particular do not let a large-state leader
     ship a snapshot to an old, lagging follower mid-rollout. A rolling restart is
     fine **provided** no node needs a multi-chunk snapshot while any old node remains
     -- if in doubt, keep committed state small (or drain writes) during the rollout.
  2. **Set the reassembly cap above your largest expected total committed state.**
     Chunking removes the 4 MiB wire ceiling, but the receiver reassembles the whole
     snapshot **in memory** to apply it, so the total is bounded by the follower's heap
     and a **fail-closed cap** (`configd.raft.maxReassembledSnapshotBytes`, default
     **512 MiB**; the effective value is clamped to ~2 GiB, the max single-array size).
     The cap **MUST exceed the largest total committed state** any group will hold --
     the whole state has to fit in the follower's heap to be applied regardless, so size
     it accordingly. Size the heap with **headroom of roughly 3x the largest snapshot**,
     not merely "above the cap": reassembly buffers the incoming bytes in a growable
     buffer (which can hold up to ~2x the data right after a growth step) and then copies
     them once more into the final array at install, so the transient peak during a
     transfer is ~2-3x the snapshot size on top of the node's normal working set. A heap
     only modestly above the snapshot size can OOM during reassembly even though the
     fail-closed cap was never exceeded. A snapshot that would exceed the cap is **refused before it can
     OOM** the follower: the partial is dropped, a **`SEVERE`** line is logged
     (`refusing InstallSnapshot reassembly ... exceed the reassembly cap`), and no
     install occurs -- **no OOM, no corruption**, but that follower **stays out of
     quorum until an operator raises the cap** (or trims state). This over-cap refusal
     logs **`SEVERE` per occurrence with no dedicated metric** -- log-watch that string;
     a drop counter/alert is a documented observability follow-up (see the snapshot-drop
     observability item in [`known-limitations.md`](known-limitations.md)).
  3. **Alert on `matchIndex` lag** -- a follower whose `matchIndex` falls and stays far
     behind the leader's `commitIndex` is the proxy for "stuck: snapshot could not be
     installed" (over-cap, or a transfer that never completed). Fold this into the
     first-30-days burn-in alerting -- the [burn-in contract](burn-in-contract.md)
     section 2C snapshot row.
  - The old **"snapshot too large for v1 wire"** stderr drop no longer occurs for
    total state above 4 MiB (chunking handles it); a codec reject now only means a
    single **chunk** exceeded the per-chunk cap (a chunk-size misconfiguration).
- **What IS rolling-safe vs all-at-once (the format-compatibility contract).** This "upgrade together" rule
  is specific to a **Raft frame-format (`WIRE_VERSION`) change** and to the pre-chunking→chunking transition
  above. Per the whole-system upgrade contract (C0-C9,
  [`../design/group-b/07-upgrade-capability-as-built.md`](../design/group-b/07-upgrade-capability-as-built.md)):
  the **edge/client plane** (first-frame version pin), the **at-rest keyring/encryption** formats, and the
  **`_acl/format` policy** version are all **rolling-safe** (every format is version-discriminated and fails
  closed on an unknown version -- never a silent misparse). Only a **Raft node↔node frame-format bump** has no
  in-band negotiation and therefore requires an all-at-once (blue/green or brief stop-the-world) upgrade. If
  you are deploying this release fresh, the pre-chunking corruption case is historical (no pre-chunking build
  exists in your cluster).

## 5. Horizontal scale at N>1 -- leadership auto-balance ships (on by default); still monitor it

- **Requirement.** If you deploy multiple machines for horizontal write scale, aim for **one group-leader
  per box (1-1-1)**. v1 ships a built-in **leadership auto-balancer** that maintains this for you, but you
  should still monitor the distribution (the balancer is built and E2E-tested, not yet load-measured at
  scale).
- **Why it matters.** The proven **2.45x across 3 machines** (near-linear,
  656->1075->1607 w/s;
  [`../archive/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md`](../archive/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md))
  **requires** exactly one leader per box. That measurement was captured under **manual** placement, before
  the balancer landed -- so treat the balancer as the mechanism that *maintains* the placement the 2.45x needs,
  not as itself proven at that throughput. The drift the balancer corrects:
  - **The built-in auto-balancer.** `LeaderBalanceLoop` (one per node, `configd.raft.autobalance.*`,
    **enabled by default** at N>1; `dryRun=false`, base cadence 30 s with jitter) observes the cluster-wide
    leader distribution and, when this node is over-owned, **sheds at most one** group's leadership per cycle
    via `transferLeadership` (it sheds, never pulls), converging toward one-per-box without operator action.
    Kill switch: `-Dconfigd.raft.autobalance.enabled=false`; observe-only: `-Dconfigd.raft.autobalance.dryRun=true`.
  - **Manual transfer is also exposed.** `RaftNode.transferLeadership` is wired on the ADMIN-gated
    `POST /v1/admin/groups/{groupId}/transfer-leadership?target=<nodeId>` route
    (`AdminApiHandler.transferLeadership`; refused when auth is off or ADMIN cannot be evaluated) for a
    deliberate placement or to drain a node before maintenance.
  - **Fresh simultaneous boot rarely lands 1-1-1 (~1 in 20):** whichever node is
    ready a beat sooner "sweeps" and wins **all** its groups, biasing to
    `3-0-0`/`2-1-0`
    ([`../archive/measurement/ec2-horizontal-2026-07-01/05-leadership-placement.md`](../archive/measurement/ec2-horizontal-2026-07-01/05-leadership-placement.md)).
    The auto-balancer then sheds the sweep back toward balance over the next few cycles.
  - After a failover, leaders can **drift/pile onto one node**, collapsing
    aggregate throughput back toward the **single-box plateau (~1100 w/s)** -- or,
    per group, the single-group knee (**~800 w/s**) -- until the balancer (or an operator)
    re-spreads. (The aggregate is robust to *modest* imbalance -- a 2-1-0
    placement still sustained ~1628 w/s -- but not to a full sweep.)
- **What to do.**
  - **Leave the auto-balancer on** (the default) and **monitor `raft_node_leader_count` /
    `raft_shard_leader_<gid>`** for a stuck imbalance (a full sweep the balancer has not yet corrected). If you
    need immediate placement (e.g. draining a node for maintenance), use the manual transfer route above.
  - **N>1 thread and shard sizing.** The Raft owner pool must have at least as many
    threads as shards; if it does not, all shards serialize on one owner thread and
    throughput does not increase -- the server logs a startup warning when this
    mismatch is detected. The shard count is capped at 16; in practice, roughly ten
    to eleven busy leaders saturate a 16-vCPU box before CPU becomes the bottleneck
    (see the archived horizontal measurement,
    [`../archive/measurement/ec2-horizontal-2026-07-01/`](../archive/measurement/ec2-horizontal-2026-07-01/)).
  - **Edge endpoint at N>1 -- `allowPartialShardView` meaning NARROWED.** With `--edge-port`
    at N>1 the server now **boots** and serves **multi-shard client-facing WATCH** across all
    shards. The legacy whole-store `SUBSCRIBE` feed stays **primary-shard-only** at N>1, so it
    is refused per connection (`BAD_SUBSCRIBE`, counted as
    `edge_fanout_sessions_closed_bad_subscribe_total`) unless you set
    `-Dconfigd.edge.allowPartialShardView=true` to accept the primary-only view.
    `allowPartialShardView` previously gated the whole edge endpoint's boot; it now gates
    **only** the legacy `SUBSCRIBE` plane (WATCH is served regardless). Migration is safe --
    strictly more permissive: a config that set the flag keeps working, and one that did not
    now boots and serves WATCH instead of refusing to start.
  - **Monitor leadership distribution continuously.** The auto-balancer corrects drift automatically, but
    verify it is doing so (watch `raft_node_leader_count`); a persistent full sweep may need a manual transfer
    or a controlled restart.
  - The remaining leadership residual is **transfer-on-graceful-shutdown** (SIGTERM flips readiness to draining
    -- item on B7 below -- but does not hand off a group's leadership first; recovery relies on the normal
    election after the node leaves). At **N=1 (the v1 default)** the balancer is dormant and this item does not
    apply.

## 6. Keep control-plane and edge identity policies consistent -- one person is two principals in v1

- **Requirement.** If you use both the control-plane API and the edge watch surface,
  keep the bearer token (or OIDC) policy and the certificate-DN (mTLS) policy
  consistent for the same human operator.
- **Why it matters.** In v1, the control-plane API authenticates with a **bearer
  token or HTTP Basic (incl. OIDC-validated bearer)** and the edge authenticates with **mTLS** client
  certificates **and/or** a token/basic `AUTH` frame (§06 §6A) -- so one
  person can hold two distinct principals: their bearer/token identity on the API
  and their certificate-DN identity on the edge. Watch authorization is evaluated
  **per-principal**: a person whose bearer-token identity is read-restricted on a
  key can still watch (and therefore read) that key under their certificate-DN
  identity if the cert-DN policy grants it -- or vice versa. The two identity
  policies are **not automatically linked** and the server does not cross-check them.
- **What to do.** Treat a watch grant as equivalent to the corresponding read grant
  across both auth mechanisms. If you restrict a key for a token identity, apply
  the same restriction to the certificate-DN identity for the same person. Audit
  the `_acl/` policy for both identity types whenever you change access to sensitive
  keys.

## 7. No silent unauthenticated PUBLIC bind (B5) -- default is loopback

- **Requirement.** The default bind address is now **loopback (`127.0.0.1`)**, not `0.0.0.0`. If you bind a
  **non-loopback** interface while **auth is OFF**, the server **refuses to start** unless you explicitly
  acknowledge the risk with `--allow-insecure-public-bind` (sysprop
  `configd.security.allowInsecurePublicBind=true`), which then logs a loud warning and continues.
- **Why it matters.** This closes the Redis/etcd "default-open" footgun class: an unauthenticated store bound
  to a public interface silently accepts writes/admin from anyone who can reach the port. This is a
  **footgun-fix, NOT "auth required by default"** -- v1 remains **secure-by-config**: a deliberate no-auth,
  public deployment stays fully possible, it just can no longer happen **by accident/silently**. (Auth and
  encryption remain a workload choice, per the Group-B posture.)
- **What to do.** For production, set `--auth-token` (or another authenticator) **and** bind the intended
  interface -- auth-on public binds need no override. For a deliberate no-auth public deployment (e.g. a
  private network segment you trust), pass `--allow-insecure-public-bind` **knowingly** and segregate the port
  at the network boundary. Leave the flag unset otherwise.

## 8. Write-admission control is ON by default (B6)

- **Requirement.** Write-admission / overload control (`configd.write.maxInflightProposals`) is now **ON by
  default** with a conservative tuned value, so the store protects itself from write floods out of the box.
- **Why it matters.** On-by-default self-protection is the safe industry-standard posture: a burst of writes
  is bounded (excess is shed with **HTTP 429 + `Retry-After`**, a pre-commit reject) rather than growing
  unbounded leader memory. This composes with the always-on rate limiter (which sheds even earlier).
- **What to do.** Accept the default unless you have measured headroom to raise it; tune via
  `-Dconfigd.write.maxInflightProposals=N` (0 disables the bound). Watch `configd_write_rejected_overloaded_total`
  and the election-churn signal (burn-in contract §2B) if you see sustained 429s.

## 9. Readiness is shard-aware and drains on SIGTERM (B7)

- **Requirement.** `/health/ready` now reflects **every shard this node should be serving**: a node that has
  lost quorum on **any** hosted shard (not just group 0) reports **NOT-ready**. On **SIGTERM** the node flips
  readiness to **NOT-ready (draining) BEFORE** it begins closing, so an orchestrator/LB stops routing and
  in-flight work drains.
- **Why it matters.** A group-0-blind readiness **lied** to the orchestrator at N>1 (it reported READY while
  shards 1..N-1 had lost quorum), and a hard close without the drain-flip dropped in-flight requests on
  restart. This is a pure correctness fix -- no policy change.
- **What to do.** Wire your orchestrator's readiness probe to `/health/ready` (it already should) and give the
  pod a graceful-termination window long enough to drain before SIGKILL. No configuration is required.

## 10. Protect key material from core dumps and swap (F3)

- **Requirement.** In-JVM zeroization of key material is **platform-bounded** (the JVM may copy heap bytes
  during GC; `SecretKeySpec.destroy()` is a no-op on JDK 25 -- Configd uses `Destroyable` root-key types to
  best-effort clear its own roots). Belt-and-braces custody therefore requires **deploy-level** controls the
  server cannot enforce: disable core dumps for the process and disable swap (or encrypt it) on nodes that hold
  the signing key or an encrypted data dir.
- **Why it matters.** A core dump or a swapped-out page can persist raw key/root bytes to disk, defeating the
  at-rest and audit integrity guarantees exactly as a co-located signing key would (threat A2/T3).
- **What to do (tested boundary).** Set `ulimit -c 0` (or the systemd `LimitCORE=0`) for the Configd unit;
  ensure `-XX:-HeapDumpOnOutOfMemoryError` (already the runsheet default -- heap dumps expose config values,
  see [`security-heap-dump-policy.md`](security-heap-dump-policy.md)); run with swap off (`swapoff -a`) or
  encrypted swap on key-bearing nodes. Treat these as part of the signing-key custody procedure alongside the
  D-1 co-location guard.

---

## Quick reference

| # | MUST-KNOW | The failure if ignored | Enforced by server? |
|---|-----------|------------------------|---------------------|
| 1 | At-rest encryption is OFF by default; enabling it is a one-way door | OFF: plaintext secrets readable from disk/snapshot/backup. ON: cannot disable/roll back; key rotation is non-destructive (but offline); signing-key **loss** = permanent, unrecoverable loss of all encrypted data (back up the key before enabling; off-host custody via Vault Transit KMS) | No -- deployment choice (`-Dconfigd.raft.encryption.enabled`) |
| 2 | Grant edge hydration identity root READ | Edge SUBSCRIBE refused NOT_AUTHORIZED (auth on); whole-store read requires root READ | Yes -- gated at admission (auth on) |
| 3 | `scope` is not tenant isolation | Cross-tenant read/write via scope-blind gate | No -- scope is metadata |
| 4 | Upgrade all nodes together | Multi-chunk snapshot to an old node = silent state corruption; total snapshot bounded by heap/reassembly cap | No -- deploy-ordering is on you |
| 5 | Monitor leadership distribution (auto-balancer maintains 1-per-box) | Full sweep the balancer has not yet corrected collapses aggregate to single-box plateau | Partly -- auto-balancer ON by default at N>1; manual transfer route also available |
| 6 | Keep cert-DN and bearer/OIDC policies consistent | A person read-restricted by token can watch via cert identity | No -- operator must align both policies |
| 7 | No silent unauthenticated PUBLIC bind (default loopback) | Auth-OFF public bind refuses to start unless `--allow-insecure-public-bind` | Yes -- fail-closed at boot (footgun-fix, not "auth required") |
| 8 | Write-admission control is ON by default | Write floods bounded with 429 instead of unbounded leader memory | Yes -- `configd.write.maxInflightProposals` on by default |
| 9 | Readiness is shard-aware + drains on SIGTERM | Group-0-blind readiness lies at N>1; hard close drops in-flight | Yes -- correctness fix |
| 10 | Protect key material from core dumps/swap | Core dump / swapped page persists raw key bytes | No -- deploy-level (`ulimit -c 0`, swap off) |

**Companion:** [`operator-runsheet.md`](operator-runsheet.md) -- the six server-side
release gates (Auth - mTLS - Audit - Replay - Signing-key - Strong-reads) plus the
always-on rate limiter.
