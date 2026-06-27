# Seam E — per-shard observability

> Charter Step 5 / handoff §2.E. Extend metrics from group-0-only to PER-SHARD health, so an operator
> can see each shard's leader/term/commit-index/apply-lag and the per-node leader count. Additive +
> N=1-safe (existing series unchanged; new per-shard series added).

## What

`MetricsRegistry` is name-only (not tag-capable), so per-shard metrics use the in-repo name-encoding
convention `base.<gid>` (handoff §2.E). `ConfigdServer.registerPerShardMetrics(registry, driver,
runtimes)` (package-private, testable) registers, for EVERY shard, pull gauges that read the group's
`RaftNode.monitorView()` — the H-3 safe, `<=`one-tick-stale, never-torn `(role, term, commitIndex,
lastApplied)` snapshot, read off-owner by the Prometheus scrape thread (the exact pattern the existing
group-0 scrape uses):

- `raft.shard.commit_index.<gid>` — the group's commit index
- `raft.shard.last_applied.<gid>` — the group's applied index
- `raft.shard.apply_lag.<gid>` — `max(0, commitIndex - lastApplied)` (the per-shard backlog)
- `raft.shard.current_term.<gid>` — the group's term
- `raft.shard.leader.<gid>` — `1` if THIS node leads the shard, else `0`
- `raft.node.leader_count` (node-level) — how many shards this node currently leads (the
  leader-count-per-node view; ~10–11 leaders saturate a 16-vCPU node — Workstream C / the M1 ceiling)

Each gauge is null-safe (a removed/absent group reads `0`). Registered once at boot, after the bring-up
loop, off the hot path.

## What stays (intentionally)

- The existing GLOBAL group-0 scrape in the tick loop — `raft_pending_apply_entries` +
  `raft_elections` (read from `monitorView()` of `DEFAULT_RAFT_GROUP`) — is UNCHANGED (back-compat for
  the existing burn-rate alerts; at N=1 it equals the single shard). The per-shard series are additive.
- **Per-shard write throughput / 429 (admission) — DEFERRED (documented).** These are recorded at the
  proposer site into a single shared `ConfigdMetrics`; making them per-shard means threading a per-gid
  metrics handle through the just-four-way-reviewed write hot path. They are DORMANT-until-N>1 (N>1 is
  boot-refused until Seam G), and the AGGREGATE write-commit/429 series already exist. So Seam E delivers
  the per-shard HEALTH view (the operator's "see each shard's health"); per-shard write-rate breakdown
  is a follow-up wired alongside the N>1 enablement in Seam G (when it becomes observable).

## N=1

At N=1 the loop registers exactly the group-0 series (`raft_shard_*_0` + `raft_node_leader_count`),
purely additive — the existing series and all consensus/wire/WAL behaviour are unchanged (byte-identity
is about consensus/wire/WAL/snapshot, not the /metrics series set; the existing series are untouched).

## Tests

`PerShardMetricsTest` — registers the per-shard gauges over N real bring-up groups; scrapes the registry
and asserts each shard's series is present with the right value (commit-index advances after a commit;
`leader.<gid>`=1 for a single-node leader; `leader_count`=N); N=1 registers exactly the group-0 series.
