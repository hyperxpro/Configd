# Watches -- research and design recommendation

Research from 2026-06-27/28, docs-only at the time -- no code, no watch implementation. This is a
recommendation, not a build. It feeds the driver protocol RFC. (Watches have since been built and shipped,
both single-key/prefix and multi-shard; see [`../../rfc/driver-protocol/`](../../../rfc/driver-protocol/) and
`docs/architecture/architecture.md` for the current design.)

The operator wanted watches (change-subscription: a client subscribes to a key/prefix and is notified on
change). Watches are a protocol-level feature, so this research is rigorous enough to be specified in an
RFC and implemented identically across the Rust / Go / Python / Java drivers.

## Read in this order

1. **[`prior-art.md`](prior-art.md)** -- how etcd (gold standard, studied hardest), Consul, and ZooKeeper
   do watches, at the level of mechanisms (revision resumption, compaction, long-poll index, one-shot vs.
   persistent), with primary-source citations. The design spectrum and the lesson taken from each.
2. **[`configd-analysis.md`](configd-analysis.md)** -- the crux: watches mapped onto Configd's sharded
   consensus + edge fan-out plane, with code evidence. Resolves the three central forks -- the multi-shard
   (prefix) watch, per-shard cursor-vector resumption, and edge-served vs leader-served -- and states
   precisely what Configd can and cannot guarantee.
3. **[`recommendation.md`](recommendation.md)** -- the RFC-ready design plus the staging call plus
   effort/risk. The normative spec a protocol author drops into the driver RFC.
4. **[`decision-log.md`](decision-log.md)** -- methodology plus the analytical decisions (DL-W-01...11).

## The answer in five sentences

A Configd watch is a client-facing, prefix-filtered, multiplexed projection of the edge fan-out plane that
already exists (hardened, CI-green at N=1), not a green-field feature. Because sharding gives no global
revision, the resume token must be a per-shard cursor vector, a prefix watch scatters across all shards,
and the only honest guarantee is per-key / per-shard order, never cross-shard order, at least-once with
`(gid, S)` dedup. Serve watches from the edge (offloads the scarce consensus tier, the etcd "any-member"
precedent), filter at the edge post-verification (ADR-0038), and handle the too-old case with an inline
per-shard snapshot resync (better than etcd's cancel-and-relist). Design the protocol cursor-vector-native
from the start; an edge-served single-shard watch is a productizable near-term step on the hardened plane
if the driver protocol ships with it and the veneer/drivers are funded; the multi-shard watch rides on the
already-scoped sharded edge as a follow-on (this is the sequencing that was in fact followed).

## What the operator had to decide (`recommendation.md` §12)

Staging (ship watches now vs. later) · served-from (edge vs. leader) · the public guarantee surface · the
filtered-watch trust model · whether the initial driver protocol ships with watches at all. If watches
ship early, this design has to be in the driver-protocol RFC from the first draft, most critically the
vector cursor and per-key-only ordering, or every polyglot driver silently breaks when a cluster shards.
