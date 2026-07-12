# FAQ

Short answers with links to the authoritative doc. If an answer here ever disagrees with
[known limitations](../operations/known-limitations.md) or the
[consistency contract](../operations/consistency-contract.md), those win.

## What is Configd, in one paragraph?

A strongly-consistent, sharded configuration store for a single region. Writes go through Raft, so
they are durable and linearizable; committed changes fan out asynchronously to edge nodes, and
applications read from an in-process, lock-free cache in microseconds. The core serves writes; the
edge serves reads. See the [architecture overview](Architecture-Overview.md).

## When should I use it — and when not?

Use it when you want linearizable config writes plus very fast, bounded-stale reads inside one
region, and you are on the JVM or willing to speak the [documented wire protocol](../rfc/driver-protocol/).
Do not use it if you need multi-region write consensus (rejected by design, ADR-0030/0031), service
discovery, distributed locks or leases (none of which it has), or a mature multi-language client
ecosystem (one Java reference client ships today). For a feature-level comparison with etcd, Consul,
and ZooKeeper, see [Comparison](Comparison.md).

## Is it production-ready?

It is measured rather than promised: real-hardware disaster-recovery drills (372 ms failover, zero
committed-write loss), a Jepsen-grade faulted-linearizability matrix (which found and fixed a real
ReadIndex bug before release), a 6-hour soak, and a measured scaling curve. It has **not** yet
completed a 72-hour soak, has never been driven at a literal sustained 10k writes/s, and is
single-region by design. Read [known limitations](../operations/known-limitations.md) — it is the
honest list — and follow the [burn-in contract](../operations/burn-in-contract.md) for the first 30
days.

## Can I store secrets in it?

Not with the default configuration. At-rest encryption is **off by default**; until you enable it,
every value — including `secure/` keys — is stored as plaintext bytes (integrity-checked, not
confidential). `secure/` is a *freshness* class (always-linearizable reads), not an encryption flag.
Use a dedicated secret manager and store only non-secret references, or enable the opt-in AES-256-GCM
encryption first — and understand that enabling it is a one-way door. See
[known limitations §1](../operations/known-limitations.md#encryption-at-rest-is-off-by-default).

## Why does the server log loud warnings at startup?

Configd is secure-by-config, not secure-by-default: TLS, authentication, the audit log, replay
protection, and at-rest encryption are each off until enabled, and the server warns while they are.
That is deliberate — a lab boot should be one command, and a production boot should follow the
[operator runsheet](../operations/operator-runsheet.md), which walks each control.

## What consistency do reads have?

By default a read is served locally and marked `X-Consistency: stale` (bounded staleness, sequentially
consistent, monotonic per client via version cursors). Add `?consistency=linearizable` for a
leader-confirmed read via Raft ReadIndex — it fails closed with `503` plus an `X-Leader-Hint` rather
than silently serving stale data. `secure/` keys are always served linearizably or not at all.
Cross-shard ordering is never guaranteed. The formal statement is the
[consistency contract](../operations/consistency-contract.md).

## Does it support watches?

Yes — server-side per the [driver-protocol RFC §2](../rfc/driver-protocol/), including multi-shard
watches, with a conforming Java client (`configd-client`) and a conformance suite. Guarantees are
per-key and per-shard ordering with at-least-once delivery and dedup; there is deliberately no
globally-ordered cross-shard watch. Details and the security model:
[known limitations §2](../operations/known-limitations.md#watches-ordering-topology-and-the-security-model).

## How many nodes do I need?

One for a lab. Three (or five) for anything real — Raft needs a majority quorum to commit writes and
to elect a leader, so a two-node cluster is strictly worse than one. Edge nodes are separate,
take no part in consensus, and scale out independently.

## Is there a client for my language?

Java, today. `configd-client` is the conforming reference client for both planes. Other languages
speak either plain HTTP (`GET`/`PUT`/`DELETE /v1/config/...` — see the [HTTP API](HTTP-API.md)) or
implement the stand-alone [driver-protocol RFC](../rfc/driver-protocol/), which is written to be
implementable end to end and validated against golden byte vectors.

## What throughput should I expect?

Measured, not modeled: the single-group write knee is about 800 writes/s (bound by leadership churn,
not CPU or disk), and aggregate throughput scales near-linearly with shards across machines (about
2.45× on 3 machines, 656 → 1607 committed w/s). Reads do not hit the cluster at all — they are
in-process at the edge. Numbers and test setups:
[known limitations §3](../operations/known-limitations.md#sharding-and-leadership) and
[`docs/measurement/`](../measurement/).

## Why Java 25?

Configd targets JDK 25 with generational ZGC (ADR-0022, ADR-0041): the read/commit tail benefits from
sub-millisecond GC pauses, and the codebase uses current language features under `--enable-preview`.
The build pins the toolchain; use the bundled `./mvnw` and any JDK 25 distribution (Corretto is what
CI uses).

## How do I report a security issue?

Privately, via GitHub's private vulnerability reporting — never a public issue. See
[SECURITY.md](../../SECURITY.md), including what is in scope for a default-insecure configuration.

## Something else?

If the [docs map](../README.md) doesn't answer it, open an issue with the question — unclear docs are
treated as bugs.
