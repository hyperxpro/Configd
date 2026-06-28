# Watches — Research & Design Recommendation

**Status: research complete (2026-06-27/28). Docs-only. No code, no watch implementation, no money.**
This is a *recommendation*, not a build. It feeds the future **driver protocol RFC**.

The operator wants watches (change-subscription: a client subscribes to a key/prefix and is notified on
change). Watches are a **protocol-level** feature, so this research is rigorous enough to be specified in
an RFC and implemented **identically** across the Rust / Go / Python / Java drivers.

## Read in this order

1. **[`prior-art.md`](prior-art.md)** — how etcd (gold standard, studied hardest), Consul, and ZooKeeper
   do watches, at the level of *mechanisms* (revision resumption, compaction, long-poll index, one-shot
   vs persistent), with primary-source citations. The design spectrum and the lesson taken from each.
2. **[`configd-analysis.md`](configd-analysis.md)** — the crux: watches mapped onto Configd's **sharded**
   consensus + **edge** fan-out plane, with code evidence. Resolves the three central forks — the
   multi-shard (prefix) watch, per-shard cursor-vector resumption, and edge-served vs leader-served — and
   states precisely what Configd **can** and **cannot** guarantee.
3. **[`recommendation.md`](recommendation.md)** — the RFC-ready design + the **v1/v2 call** + effort/risk.
   The normative spec a protocol author drops into the driver RFC.
4. **[`decision-log.md`](decision-log.md)** — methodology + the analytical decisions (DL-W-01…11).

## The answer in five sentences

A Configd watch is a **client-facing, prefix-filtered, multiplexed projection of the edge fan-out plane
that already exists** (hardened, CI-green at N=1) — not a green-field feature. Because sharding gives no
global revision, the resume token **must be a per-shard cursor vector**, a prefix watch **scatters across
all shards**, and the only honest guarantee is **per-key / per-shard order, never cross-shard order**, at
least-once with `(gid, S)` dedup. Serve watches from the **edge** (offloads the scarce consensus tier —
the etcd "any-member" precedent), filter **at the edge post-verification** (ADR-0038), and handle the
too-old case with an **inline per-shard snapshot resync** (better than etcd's cancel-and-relist).
**Design the protocol now, cursor-vector-native;** an N=1 edge-served watch is a v1-capable productization
of the hardened plane **if** the driver protocol ships in v1 and the veneer+drivers are funded; the
multi-shard watch is **v2**, riding on the already-scoped sharded edge.

## What the operator must decide (`recommendation.md` §12)

v1 or v2 · served-from (edge vs leader) · the public guarantee surface · the filtered-watch trust model ·
whether the v1 driver protocol ships with watches at all. **If watches are v1, this design must be in the
driver-protocol RFC from the first draft — most critically the vector cursor and per-key-only ordering,
or every polyglot driver silently breaks when a cluster shards.**
</content>
