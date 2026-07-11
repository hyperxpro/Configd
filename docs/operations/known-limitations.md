# Known limitations

This is the honest, current statement of what Configd does not do, or does with caveats. These are
deliberate scoping choices and measured gaps, not surprises. Pair this with the
[consistency contract](consistency-contract.md) for the formal guarantees and the
[deployer must-knows](deployer-must-know.md) for the deployment conditions the server does not enforce
for you.

- [Encryption at rest](#encryption-at-rest-is-off-by-default)
- [Watches: ordering, topology, and the security model](#watches-ordering-topology-and-the-security-model)
- [Sharding and leadership](#sharding-and-leadership)
- [Authorization: LIST is defined but not exposed](#authorization-list-is-defined-but-not-exposed)
- [What's measured, and what isn't](#whats-measured-and-what-isnt)

## Encryption at rest is off by default

Configd can encrypt data at rest, but it is **off by default**. With encryption off, all config
values - including `secure/` (strong-read) keys - are stored as plaintext bytes in the control-plane
HAMT, WAL, and snapshot, protected only by an integrity envelope (HMAC-SHA-256, ADR-0042), which
detects tampering but provides no confidentiality. At edge nodes, `secure/` values are kept in-memory
only and never written to disk, which bounds but does not remove the exposure.

The `secure/` namespace is a *freshness* guarantee, not a security one. `secure/` (the strong-read key
class, ADR-0030 INV-1) means a key is always read fresh - linearizable, fail-closed, never served stale
- for security-critical decisions like ACL/auth revocations, kill-switches, and legal gates. It does not
mean the value is encrypted or confidential. Naming a key `secure/...` buys read freshness, not secrecy.
With encryption off, do not store secret material (passwords, tokens, private keys, PII) in Configd -
use a dedicated secret manager and keep only non-secret references in Configd.

**Turning encryption on.** Set `-Dconfigd.raft.encryption.enabled=true` (or env
`CONFIGD_ENCRYPTION_AT_REST=true`). This encrypts the WAL, snapshot blob, and durable Raft state with
node-local AES-256-GCM at the ADR-0042 seam (an `algId=2` envelope; the GCM tag replaces the HMAC, the
CRC32C corruption layer stays). The default `local` key provider derives the encryption root by HKDF
from the cluster signing key, domain-separated from the integrity/audit keys. An external Vault
Transit KMS provider (`configd-kms-vault`) also ships for off-host key custody and is discovered via
`ServiceLoader` (select with `-Dconfigd.raft.encryption.kms.provider=vault-transit`), with the per-node
custody secret sealed in a versioned `raft-kms-root` carrier. No wire-format change, no cluster-wide key
distribution. See [`deployer-must-know.md` section 1](deployer-must-know.md) for the full enabling
procedure and operator warnings.

**Enabling is a one-way door.** Once any `algId=2` record is written, encryption cannot be disabled and
the binary cannot be rolled back to a pre-encryption version - recovery fails closed (a non-encrypting
reader refuses the `algId=2` records). There is no supported disable path; treat it as permanent for a
given data directory. Enabling on a node with existing plaintext/HMAC (`algId=0/1`) records does not
rewrite them - they stay plaintext until a snapshot/compaction; enable from first boot or force a
compaction, and use `-Dconfigd.raft.encryption.requireEncrypted=true` to refuse legacy records once the
plaintext prefix is gone.

**Fate-sharing and key rotation.** With the `local` provider, confidentiality fate-shares with the
signing key: a signing-key compromise decrypts all at-rest data. Key rotation is non-destructive by
construction (`NodeKeyring`): the persisted, dual-slot keyring holds independent random per-term roots,
so a term rotation (`rotateTerm`) or a signing-key rotation (`rewrapForNewSigningKey`, which rewraps
every retained root under the new signing key's KEK before the swap) leaves all prior `algId=2` data
readable - old-term data still decrypts. Rotation is currently an offline, operator-serialized action -
there is no online admin trigger yet; a term or signing-key rotation is a maintenance action on a
stopped node. Key loss is still permanent: back up the signing key before enabling encryption, because
losing or destroying it means permanent, unrecoverable loss of all encrypted data. Off-host key custody
is available via the external Vault Transit KMS provider above; other custody backends (AWS KMS, GCP
KMS, an HSM/PKCS#11 provider) can be added behind the same `KmsProviderFactory` SPI without a core edit,
but none besides `local` and `vault-transit` ship today.

**Not encrypted at rest.** The audit log stays HMAC-only, so audit metadata (config key names,
principals) is integrity-protected but not confidential; encrypting it is a follow-up. On multi-shard
deployments, the GCM AAD binds the artifact-type magic but not the Raft group ID, and one key manager is
shared across all groups - nonce uniqueness is global and safe, but cross-group at-rest *integrity* (a
record spliced from group B's WAL into group A's) is caught only by the Raft log-consistency layer and
the per-shard durability anchor, not the envelope AAD. Binding the group ID into the AAD would be the
tighter belt before a multi-shard deployment relies on envelope-level cross-group integrity.

**Write-path overhead (measured, release SHA `eb9b293`).** Encryption on vs. off, single node, 256 B
values: sustained throughput knee 1210 -> 1180 w/s (-2.5%), commit latency p50 7.65 -> 7.77 ms (+1.5%),
p99 14.6 -> 36.4 ms (+150%). The cost is tail-weighted (per-record AES-GCM plus ciphertext allocation
roughly doubles p99). This is a single loopback node, so it measures the local encrypt-on-write cost
only (no cross-node replication fsync in the path) - a floor. See
`docs/measurement/ec2-drive-to-green-2026-07-02/gate7-final/`.

## Watches: ordering, topology, and the security model

The RFC section 2 driver-protocol watch surface is implemented server-side on the edge endpoint
(`--edge-port`): the `0x02` wire (the `WATCH_*` frames and per-shard cursor vector), the
multiplex/filter veneer, a whole-target authorization gate (`READ` and `WATCH`, reject-not-filter,
fail-closed), per-watch target-filtered delivery with catch-up snapshots, and bounded revocation under
a live ACL reload. Multi-shard (N>1) client-facing watches are served by a server-side aggregating
coordinator: one session core per covered shard behind a single connection, every event tagged
`(gid, S)`, a per-shard cursor vector, coalesced `WATCH_CREATED`/`WATCH_PROGRESS` vectors, and
independent per-shard resume. Every serving node hosts replicas of all N Raft groups, so the
scatter-gather is in-process and leadership-independent.

A conforming Java reference client (`configd-client` plus `-core`/`-http`/`-edge`) and a
`configd-conformance` suite ship and are wired into CI, exercising both planes against golden wire
vectors - watches are consumable out of the box on the JVM. Drivers in other languages are buildable
from the stand-alone RFC (`docs/rfc/driver-protocol/`) and validate against the same goldens, but none
ship yet. The legacy pull pattern (`GET /v1/config/{key}`, optionally linearizable) plus the edge
bounded-staleness read path with version cursors remains a supported read path.

**Guarantees you can rely on:** per-key and per-shard order (never cross-shard or global); batch-atomic
per shard-commit; at-least-once delivery with `(gid, S)` dedup (the driver drops `S <= cursor[gid]`);
bounded staleness on edge-served watches (ordered, not linearizable - use the strong-read path for
read-after-write); bounded revocation latency (at most the edge idle-poll interval after an `_acl/`
reload).

**Ordering is per-shard, never global.** Two events with different `gid` are concurrent - a driver must
not infer order from arrival sequence, from `S` magnitude across shards, or from the commit timestamp (a
per-leader wall clock). An initial snapshot at N>1 is per-shard-current (each covered shard snapshots at
its own sequence, captured at a different instant), not a cross-shard consistent cut - there is no
global clock to take one against. A globally-ordered cross-shard watch is out of scope by design, not
merely deferred.

**Completeness stalls, never lies.** Coverage is driven by the shard set, never inferred from the
client's cursor. A lagging or unreachable shard surfaces as a frozen `WATCH_PROGRESS` component while
the server clock keeps advancing - never a silent gap, and it never fails the whole watch; healthy
shards keep delivering.

**Backpressure is shared across a connection's shards.** The shard substreams behind one connection
share a single connection-level cursor-ack and a single slow-consumer fate, so one slow shard can
demote its siblings. Per-(watch, shard) flow control is not built - segregate latency-sensitive watchers
onto their own connections.

**`GAP_UNRECOVERABLE` recovery is re-list plus re-create.** When a component falls too far behind even
for a snapshot, the server cancels the watch with no `oldest` vector; a driver recovers by re-listing
current state and re-creating the watch from its own last-held cursor vector. A per-shard `oldest`
smart-resume payload is not built yet.

**Shard reach depends on target kind.** A KEY watch resolves to exactly one shard; a PREFIX, FULL, or
whole-chain target scatters to all shards and is authorized over the whole target (it requires the
root-scope grant). `scope` itself carries no authorization isolation today - the gate, store keys, read
path, and filter are uniformly scope-blind over a flat keyspace, so watches are effectively single-scope
(GLOBAL) until scope-aware ACLs land.

**Security model a deployer should know** (the watch path is internally sound; these are
system-boundary and deployment conditions):

- **The legacy whole-store `SUBSCRIBE` feed is co-resident on the same port.** The same `--edge-port`
  also serves the pre-existing whole-store `SUBSCRIBE` fan-out (the trusted server-to-edge backbone,
  ADR-0038). With auth on, it is gated at admission on a whole-store READ cover - a root-prefix `READ`
  grant with no intersecting deny; `WATCH` is not required, since `SUBSCRIBE` is a read feed. A cert
  that completes the mTLS handshake but lacks root READ is refused before any frame flows, so a
  watch-only principal can no longer escalate to the whole store via `SUBSCRIBE`. The edge/hydration
  identity (the edge node's cert DN) must hold READ over the root prefix `""` or edge hydration is
  refused. Authentication is not authorization: the gate is active only when ACL/auth is enabled (an
  `--auth-token` is set), which is decoupled from TLS. With auth off but mTLS on, the authorizer is
  absent, so every valid edge cert still pulls the whole store - per-cert trust plus network
  segregation is the operator's only control in that posture. Over a plaintext edge port with auth on,
  the identity is the literal `"plaintext"`, denied unless `"plaintext"` holds root READ.
  `SUBSCRIBE` is authorized once at admission and never re-checked - revoking an edge identity's root
  READ does not tear down its existing feed; revoke by disconnecting the session or rotating the edge
  cert. At N>1 this legacy plane serves only the primary shard's keys (a partial keyspace view), so the
  server refuses a legacy `SUBSCRIBE` connection per-connection at N>1 unless the operator sets
  `-Dconfigd.edge.allowPartialShardView=true` to accept that partial view explicitly. The flag gates only
  this legacy plane - a multi-shard WATCH is served at N>1 regardless, and at N=1 the flag is never
  consulted.
- **Server-side prefix filtering (ADR-0045), default on, is a trusted-domain posture.** A prefix-scoped
  edge that opts in (`configd.edge.accept_filtered=on`, `0x03` wire) has its stream filtered to its
  prefix set server-side, cutting egress. Whole signed deltas are dropped, never rewritten - per-delta
  Ed25519 authenticity stands - and strong-read (`secure/`) keys are always shipped. The edge trusts the
  server's covered-through assertion on the heartbeat. A genuine data-loss gap is still caught
  server-side and re-snapshotted; a delivered notification whose position regresses below the applied
  version is caught edge-side and resynced; but a well-formed suppression of a matching delta behind a
  correct covered position is not edge-detectable - sound only within the co-located mTLS domain. Set
  `configd.edge.fanout.filter=off` (restore the full chain) the moment a separate or untrusted relay
  tier terminates the fan-out.
- **The always-shipped strong-read prefix set can drift.** It is the hardcoded `secure/` default on the
  edge but config-driven (`--strong-read-prefixes`) on the server. If an operator overrides the server's
  strong-read prefixes, the two could drift, so the edge might not treat a server-strong-read key as
  always-shipped. Not exploitable today, since strong-read reads bypass the edge copy entirely via the
  linearizable root ReadIndex - but this should be closed (thread the configured set to the edge) before
  any edge-local-serve feature for strong-read keys.
- **mTLS plus an explicit grant is required.** A watch needs a verified cert DN and an explicit `READ`
  and `WATCH` grant to that DN; without mTLS, all watches are rejected. The default config grants watch
  only to `root`, so out of the box no edge cert can watch until the operator adds an `_acl/` grant.
- **The reserved-prefix (`_acl/`, `_system/`) ADMIN gate is enforced at the HTTP boundary, not yet
  mirrored in the watch gate.** A KEY or PREFIX watch cannot name a reserved key directly, but a FULL or
  whole-chain watch does span them - it is gated by the root-only full-scope grant, so no non-root
  principal can observe reserved keys via any watch kind today. Moving the reserved-prefix rule into the
  shared ACL service so every gate inherits it directly is a follow-up.

**Per-connection fairness.** All watches on one connection share a single cursor, ack, and backpressure
fate - a slow watch can demote its siblings; per-watch fairness is not built. A connection-level catch-up
snapshot maps to the first (drain-owning) watch on that connection.

**Not built:** the disjoint sharded-edge topology (edges serving shard subsets, with the driver merging
client-side over the same wire, more connections); per-watch flow control; the `prev_value` /
leader-served / long-poll-gateway named extensions; moving the reserved-prefix rule into the shared ACL
service.

## Sharding and leadership

The default is a single, region-local Raft group (N=1; ADR-0030, ADR-0023). The multi-Raft sharding
layer is built and server-wired on main (`StaticShardMap` and `shardFor` routing); at N=1 it is
byte-identical to a non-sharded build.

**Measured throughput.** The single-group write knee is about 800 writes/s (m6i.4xlarge; bound by
leadership churn, not CPU or disk). Aggregate throughput across shards scales near-linearly: about
2.45x on 3 machines (656 -> 1075 -> 1607 committed w/s;
`docs/archive/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md`). No literal sustained 10,000
writes/s has been run - the single-cluster max measured is 1607 w/s, and 10k/s remains a
sharded-aggregate target (about 535 w/s per leader-machine, cross-machine, implying roughly 17-19
machines) rather than a number that has been captured directly.

**Leadership auto-balance ships, on by default at N>1.** After a failover, leadership can drift -
multiple groups' leaders piling onto one box collapses aggregate throughput toward the single-group
plateau. A decentralized auto-balance loop (`LeaderBalanceLoop`, one per node,
`configd.raft.autobalance.*`) runs by default at N>1: base cadence 30 s with about 25% jitter, a 60 s
cooldown between actions, and an imbalance threshold of 2; each cycle it sheds at most one over-owned
leader - it never pulls - converging toward one leader per box without operator action. Turn it off with
`-Dconfigd.raft.autobalance.enabled=false`, or watch without acting via
`-Dconfigd.raft.autobalance.dryRun=true`. The 2.45x scaling number above was captured under manual
one-leader-per-box placement, before the balancer landed - the balancer is built and end-to-end tested
but not yet load-measured at scale, so treat it as the mechanism that maintains the placement the 2.45x
needs, not as itself proven at that throughput.

An operator can also redistribute leadership by hand:
`POST /v1/admin/groups/{groupId}/transfer-leadership?target=<nodeId>` (ADMIN-gated; refused when auth is
off or ADMIN cannot be evaluated). The response is `200` as soon as the transfer is accepted, not once
it completes - the actual move is asynchronous, so a transfer to a far-lagging target can return `200`
without ever taking effect. If the target cannot catch up within about one election timeout, the
transfer auto-aborts and the current leader resumes (Raft dissertation section 3.10); writes are never
blocked while a transfer is pending. There is no dedicated status endpoint - confirm a transfer took
effect by re-reading the leader (`raft_node_leader_count`, `raft_shard_leader_<gid>`, or
`RaftNode.leaderId()`). A request against a node that is not the group's leader returns `503` with an
`X-Leader-Hint` header pointing at the current leader; clients follow that hint to retry against the
right node. The Raft-dissertation transfer-abort event itself only logs to stderr today - there is no
counter yet.

**What's not built.** Leadership does not transfer proactively on graceful shutdown: `SIGTERM` flips
readiness to draining, so the orchestrator stops routing to the node, but the node does not hand off a
group's leadership first - recovery relies on the normal election after the node leaves. When
multi-shard is enabled, the Raft owner pool needs at least as many threads as shards, or all shards
serialize on one owner thread for no gain; the shard count is capped at 16, and in practice roughly ten
or eleven busy leaders saturate a 16-vCPU box. Chunked snapshot transfer lifts the old total-state
ceiling by streaming a large snapshot as ordered chunks (each at most 4 MiB, default chunk 1 MiB), but
the follower still reassembles the whole snapshot in heap under a fail-closed cap
(`configd.raft.maxReassembledSnapshotBytes`, default 512 MiB); spilling incoming chunks to disk instead
of buffering the whole snapshot in heap is not built. See
[`deployer-must-know.md` section 4](deployer-must-know.md) for the upgrade-ordering requirement this
implies.

## Authorization: LIST is defined but not exposed

The authorization model defines a `LIST` permission alongside `READ`, `WRITE`, `WATCH`, and `ADMIN`
(`AclService.Permission`), and policy rules can grant or deny it. There is currently no list or
enumerate endpoint over the API for it to gate, so `LIST` is inert - nothing in the request path checks
it, because there is nothing to check it against.

## What's measured, and what isn't

**Soak.** The clean-code soak ran a flat 6 hours (file descriptors flat at 350, RSS spread 2.6%, heap
floor stable, GC overhead 0.92%, zero rejected writes;
`docs/archive/measurement/ec2-2026-06-30/04-soak.md`). A prior attempt at 24 hours ran out of box
capacity at 3.45 hours - not a Configd leak. No full 24-hour or 72-hour soak has completed yet; the
first-30-days posture is the [burn-in contract](burn-in-contract.md).

**Disaster recovery.** Leader-loss under load, WAL-replay restart, and wipe-plus-install-snapshot were
all run on real hardware: 372 ms failover (one bounded election, no storm), zero committed-write loss
across all three fault modes out of 1000 writes, and recovery times of 4.2 s (WAL replay) and 5.9 s
(snapshot install). This ran on a single-box, three-co-located-node topology - cross-machine failover
adds network RTT, but the correctness (no loss, bounded election) is topology-independent.

**Faulted linearizability.** An adversarial matrix drives a real cluster under process kills and
restarts, network partitions (including multi-node quorum-breaking partitions), process pauses, packet
loss, clock skew, and overlapping combinations of these, across N=3 and N=5, and across
encryption/auth/clock-skew/multi-shard postures, checked by the trusted Porcupine linearizability
checker. This matrix found a real bug on pre-fix code: a phantom-absent linearizable read (a 404 for a
committed-and-acknowledged key) served by a fresh leader whose ReadIndex omitted the current-term no-op
gate required by Raft section 6.4. It's fixed (`RaftNode.readIndex()` now gates on
`noopCommittedInCurrentTerm`; regression test `ReadIndexNoOpBeforeServeTest`), and the full matrix
re-ran with every history linearizable on the fixed code. Results are pinned under
`docs/measurement/e1-faulted-linz-2026-07-10/`. Asymmetric or partial (bridge, non-transitive)
partitions still need per-pair, source-addressed network cuts, which a single-host loopback substrate
can't produce - that's a follow-up; the same safety edge is already stressed by the pause, isolation,
and quorum-breaking combinations above. A soak beyond 72 hours (endurance) is a separate, still-pending
measurement.

**Edge staleness.** The staleness mechanism (the edge's `StalenessTracker`, measuring against the
covered frontier of commit timestamp and cursor-matched heartbeat) is implemented and load-bearing. A
release-SHA re-run met the contract bound with large margin: at 4 edges / 500 w/s / 180 s, p99 was 24 ms
and p9999 117 ms; at 1 edge / 100 w/s / 30 min, p99 was 13 ms, p9999 212 ms, max 232 ms (the bound is
p99 < 500 ms, p9999 < 2 s - met with 10-38x margin). A deeper-tail measurement at high multi-edge density
wants dedicated edge hardware, since single-box co-location occasionally starves an edge JVM; the
clean per-edge steady-state distribution above is representative and the bound is met. See
`docs/measurement/ec2-drive-to-green-2026-07-02/gate7-final/`.

**Residuals.** No literal sustained 10,000 writes/s or 100,000-write burst has ever run (single-cluster
max measured is 1607 w/s). No cross-region or WAN measurement exists - Configd is single-region by
design, and both measurement runs were same-AZ. No full 24-hour or 72-hour soak has completed. Separately,
a measured server-sink allocation reduction of about 176 bytes per operation is understood but not yet
folded into the code.
