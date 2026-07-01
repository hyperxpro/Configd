# Watches Research — Decision Log

**Session: 2026-06-27/28. Pure research + design recommendation. No production code, no money, no
watch implementation.** Standing autonomy policy: research only; stop at the docs-only PR.

This log records the *methodology* and the *analytical decisions* made while producing `prior-art.md`,
`configd-analysis.md`, and `recommendation.md`. None of these are operator-binding — they are the
research team's evidence-based recommendations; the operator's calls are listed in `recommendation.md`
§12.

---

## Methodology

- **DL-W-01 — Parallel prior-art + codebase mapping, then primary-source grounding.** Ran five
  concurrent agents: three Explore agents mapping the live code (fan-out cursor model + dormant watch
  machinery; the edge plane + wire protocol; the sharding/addressing model) and two web-research agents
  (etcd hardest; Consul + ZooKeeper). Then **personally re-read the load-bearing source and ADRs**
  (`EdgeFrame.java`, `CommitNotificationSource.java`, `WatchService.java`, `WatchCoalescer.java`,
  `StaticShardMap` hash, ADR-0019/0020/0034/0038/multiraft-cross-shard) before transcribing any
  RFC-grade claim. *Why:* the docs feed a protocol RFC; agent summaries are leads, not citations.
- **DL-W-02 — Preserve verbatim-vs-inference distinctions from prior art.** Kept the etcd researcher's
  flags (e.g., etcd never uses "exactly-once" verbatim; "leader-independent" is synthesized). The brief
  quotes the six named etcd guarantees rather than applying convenient labels. *Why:* honesty about what
  a reference system actually guarantees is the point.

## Analytical decisions (the recommendations)

- **DL-W-03 — The resume token is a per-shard cursor VECTOR, not a global revision.** Configd has no
  global Raft log; a global sequencer would reintroduce the single-writer bottleneck sharding exists to
  remove (ADR-0019). The vector is already the cross-shard model (ADR-multiraft-cross-shard). At N=1 it
  degenerates to a scalar. *Evidence:* `configd-analysis.md` §1.
- **DL-W-04 — Prefix watches scatter across ALL shards.** `StaticShardMap.shardFor` hashes the full
  `(scope, key)`, so a prefix is not shard-local; `getPrefix` is already scatter-gather. Embrace
  scatter-gather for v1; range/domain partitioning for prefix locality is a v2+ redesign, not a v1
  option. *Evidence:* `StaticShardMap.java:56-79`, `ConfigdServer.java:1460-1501`,
  `configd-analysis.md` §2.
- **DL-W-05 — Edge-served, not leader-served (primary).** The edge fan-out plane is the hardened,
  horizontally-scalable read/watch tier; serving watches there offloads the scarce consensus plane and
  matches etcd's "any member serves a watch" precedent. Leader-served is an optional v2 fast-path that
  taxes consensus. *Evidence:* `configd-analysis.md` §4; `prior-art.md` §1.6.
- **DL-W-06 — Filter at the edge, post-verification (ADR-0038), and name the trust tradeoff it
  reintroduces.** Server-side filtering/coalescing breaks the signed chain (ADR-0038), so the edge gets
  the full chain and filters after verifying. That same suppression threat reappears edge→client for
  filtered watches; resolved by trust-domain (trusted-edge default), `full_chain_verify` for untrusted
  edges, and signed-skip-evidence as the named v2+ path. *Evidence:* `configd-analysis.md` §4.3, §5;
  ADR-0038.
- **DL-W-07 — Guarantee surface: per-key ✅ / per-shard ✅ / cross-shard global ❌; at-least-once + (gid,S)
  dedup.** This is the ADR-multiraft-cross-shard / ADR-0019 contract projected onto a change stream. The
  RFC must state it normatively; drivers must not assume global order. *Evidence:* `configd-analysis.md`
  §6; `recommendation.md` §5.
- **DL-W-08 — Streaming transport + `watch_id` multiplex (borrow etcd), not long-poll.** The edge plane
  is already a streaming session; long-poll is a redundant transport that can't express snapshot
  catch-up. *Evidence:* `configd-analysis.md` §9-§10; `recommendation.md` §7, §9.
- **DL-W-09 — Build on the edge plane, retire/repurpose the dormant `WatchService`.** `WatchService`/
  `WatchCoalescer` coalesce (conflicts with ADR-0038's verbatim chain), keep an in-memory scalar cursor,
  and are not resumable/failover-safe. Its prefix-registry/`filterByPrefix` logic is reusable as the
  edge-side filter layer (minus coalescing, plus the vector cursor); everything else is superseded.
  *Evidence:* `configd-analysis.md` §11; `WatchService.java`, `WatchCoalescer.java`, ADR-0038.
- **DL-W-10 — Too-old handling is an inline per-shard snapshot resync (partial, not global).** Better
  than etcd's cancel-and-relist: only the lagging shard's substream snapshots; the rest keep streaming.
  Requires idle watchers to advance their cursor on bookmarks or busy non-matching traffic strands them.
  *Evidence:* `configd-analysis.md` §8/§8.1; ADR-0034, ADR-0020 (prefix-filtered snapshot), ADR-0038
  (global cursor advances over non-matching).
- **DL-W-11 — v1/v2 call: design the protocol now (vector-native); ship the N=1 watch in v1 iff the
  driver protocol ships in v1 and the veneer+drivers are funded; the multi-shard watch is v2.** Refines
  the prior blanket "watches → v2" (`configd-pre-ec2-cleanup` Task 4) with evidence that the N=1 watch is
  a productization of a hardened plane, while the cursor-vector/scatter-gather is the already-scoped v2
  sharded-edge work. *Evidence:* `recommendation.md` §10.

## Scope discipline (what this session did NOT do)

- No production code; no watch implementation; no wire-format change; no money. Docs only under
  `docs/research/watches/`.
- Did not modify the dormant `WatchService`/`WatchCoalescer`/`SubscriptionManager` or any ADR. The
  recommendation to retire/repurpose them is a recommendation, not an action.
- Did not pre-empt the operator's v1/v2, served-from, guarantee-surface, or trust-model calls
  (`recommendation.md` §12). This research informs them.

## Feeds / relates to

- **Feeds the future driver protocol RFC** (the explicit purpose) — `recommendation.md` §11.
- Relates to: ADR-0019 (consistency model), ADR-0020 (prefix subscription), ADR-0034 (commit-notification
  boundary), ADR-0038 (signed chain / no coalescing), ADR-0039 (frontier staleness),
  adr-multiraft-cross-shard (D-C, the cursor-vector + guarantee surface), the Seam-G1 per-shard fan-out
  (`configd-phase1-wiring-state`), and the pre-EC2 watches→v2 note (`configd-pre-ec2-cleanup`).
</content>
