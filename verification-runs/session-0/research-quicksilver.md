# Prior-Art Research: Quicksilver-Shaped Topology vs. Global Multi-Region Consensus

> **Author:** prior-art-researcher (Session 0, topology-ADR effort) · **Date:** 2026-06-06
> **Branch:** `session-0-topology-adr` · **Repo:** `/home/ubuntu/Code/Configd`
> **Purpose:** Ground the load-bearing decision for ADR-0030 — adopt a *Quicksilver-shaped*
> topology (centralized strongly-consistent single-group writes → asynchronous fan-out to
> eventually-consistent edges with a bounded-staleness contract) and explicitly **reject**
> global multi-region / hierarchical-Raft write consensus.
>
> **Method & rules.** Every external claim cites a public primary source (Cloudflare blog,
> official docs, paper, or a clearly-labeled secondary). Numbers I could not pull from a
> primary source are tagged `[UNVERIFIED]`. Numbers that are *community/secondary inference*
> are labeled as such. I treated `docs/research.md`, `docs/architecture.md`, and the existing
> ADRs as **claims to verify**, not evidence — discrepancies I found are called out inline.
> I do **not** assume the pending decision is correct; §C contains arithmetic that would
> *invalidate* the architecture's own headline number if it had to carry cross-region writes,
> and that is the point.

---

## TL;DR for the topology-architect

1. **Quicksilver is not multi-region consensus.** It is exactly the shape the ADR proposes:
   a *centralized* Raft root cluster for write durability/ordering, then **asynchronous
   hierarchical fan-out** to ~330 cities, where edges serve **sequentially-consistent,
   eventually-up-to-date** reads from a local copy. Cloudflare distributes a change "to 200
   cities in 90 countries… within seconds" (2020), and at v2 (2025) serves "over three
   billion keys per second" with "90% of requests in less than 1 ms" — **with no consensus at
   the edge.** (Sources in §A.)

2. **The Quicksilver root itself uses Raft — and it is a single group, not WAN-stretched.**
   Cloudflare built "Quicksilver Raft — a Raft-enabled root cluster using the etcd Raft
   package." Writes are *batched* (500 ms) and de-duplicated with CAS before entering the log.
   This is the same single-group-Raft engine etcd uses, and it is centralized, not
   geo-distributed across voting regions. (§A.)

3. **etcd / single-group Raft cannot scale *global writes*, by construction.** Official etcd
   docs: a cluster "probably should have no more than seven nodes" because "the write
   performance suffers because data must be replicated across more machines," and across
   regions "the latency from crossing data centers will be somewhat pronounced because at
   least a majority of cluster members must respond to consensus requests." Adding nodes
   buys fault tolerance, **not** write throughput. (§B.)

4. **True geo-distributed consensus pays a hard RTT floor that breaks §0.1.** With *measured*
   AWS inter-region RTTs, a 5-voter global Raft group (leader in us-east-1) has a
   **network-only commit floor of ~68.5 ms** — and that is *before* fsync, batching, and
   queueing, and *only* for a client co-located with the leader. A client physically in
   ap-southeast-1 writing to that same group pays **~288 ms end-to-end** (full client↔leader
   RTT + commit floor) — **nearly 2× the §0.1 `< 150 ms p99 cross-region` target.** CockroachDB
   and Spanner confirm this is intrinsic, not an implementation defect: CockroachDB global-table
   writes are "as high as 800 ms… [optimizable to] 250 ms or less," and Spanner pays a
   commit-wait of ~7 ms *for clock uncertainty alone* on top of cross-region Paxos. (§C.)

5. **Net:** the evidence supports the decision. Centralize writes in one strongly-consistent
   group; do **not** put write consensus on the WAN. The §0.1 budgets
   (`write p99 < 150 ms`, `propagation p99 < 500 ms`, `edge read p99 < 1 ms`) are achievable
   with the Quicksilver shape and are **arithmetically impossible** with global/hierarchical
   Raft *write* quorums spanning APAC. Draft ADR material is in §D.

> **Discrepancies found in existing repo docs (flagged, not trusted):**
> - `docs/architecture.md:181-205` and `adr-0015-multi-region-topology.md` describe a
>   hierarchical/multi-region *write* topology (global 5-voter group + regional groups +
>   closed-timestamp follower reads). `STATE-OF-REALITY.md §4.1` and
>   `findings-design-vs-reality.md` confirm **none of this is implemented** (one Raft group,
>   `region` absent from `src/main`). The ADR-0015 design is also internally optimistic: its
>   "~68 ms" global-commit number is a *network-only floor* for a leader-co-located client and
>   omits the APAC-client penalty I compute in §C.
> - `adr-0023` (multi-Raft deferred) and `adr-0024` (cross-DC bridge deferred) already concede
>   that v0.1 is **single-Raft, single-DC** and that "WAN-stretched Raft violates the SLO" —
>   which *agrees* with this research. ADR-0030 should supersede the *aspirational* ADR-0015
>   write-topology and align with the honest posture of 0023/0024.
> - `docs/research.md:312` claims "330 cities, 125+ countries, 90,000+ instances" and
>   `:309` "5+ billion KV pairs, 1.6 TB." These reconcile with primary sources **only if you
>   note the era**: the 2020 "Introducing" post says *200 cities / 90 countries / 1 billion
>   pairs / 90,000+ instances*; the 2025 v2 posts say *330 cities / 5 billion pairs / 1.6 TB*.
>   research.md silently mixes 2020 and 2025 figures. I separate them by date below.

---

## (A) How Cloudflare Quicksilver actually works

### A.0 What Quicksilver is

Quicksilver is Cloudflare's internal globally-replicated key-value store for **configuration
distribution** — the system that pushes every customer config change (DNS, Workers, WAF rules,
etc.) out to every Cloudflare data center. It is the closest public production analogue to
Configd's mission, which is why §0.3 names it the baseline to beat.

### A.1 The write path: centralized root → batched Raft → async fan-out

**Centralized root, not regional voters.** From the 2020 "Introducing Quicksilver" post
(published **March 30, 2020**):

> "Our centralized web services would write values to a set of root nodes which would
> distribute the values to management nodes living in every data center."
> — *Introducing Quicksilver: Configuration Distribution at Internet Scale* (2020).
> <https://blog.cloudflare.com/introducing-quicksilver-configuration-distribution-at-internet-scale/>

> "Each server would eventually get its own copy of the data from a management node in the
> data center in which it was located." — *ibid.*

**The root is a single Raft group built on etcd's Raft library**, with 500 ms batching and
CAS-based de-duplication (from "Moving Quicksilver into production," published
**Nov 25, 2020**):

> "we decided to build Quicksilver Raft — a Raft-enabled root cluster using the etcd Raft
> package."
> — *Moving Quicksilver into production* (2020).
> <https://blog.cloudflare.com/moving-quicksilver-into-production/>

> "the QSKTBridge was in charge of batching: aggregating 500ms of updates before flushing to
> Quicksilver." — *ibid.*

> "we used the Compare And Swap command to ensure that only one batch with a given timestamp
> would ever be written." — *ibid.*

**Distribution is a hierarchical pull/fan-out tree, not consensus:**

> "Each small data center replicates from multiple bigger sites and these replicate from our
> two core data centers." — *Moving Quicksilver into production* (2020).

This is the load-bearing architectural fact for ADR-0030: **consensus is used only for the
*source of truth* (the root), and pure replication/fan-out is used for *distribution*.** There
is no write quorum spanning the edge.

### A.2 Propagation time: "within seconds," globally

> "Every time a user makes a change to their DNS, adds a Worker, or makes any of hundreds of
> other changes to their configuration, we distribute that change to 200 cities in 90 countries
> where we operate hardware. And we do that within seconds."
> — *Introducing Quicksilver* (2020).

> **Catch-up / staleness threshold:** "if the latest received transaction log is over 30
> seconds old, Quicksilver would disconnect and try the next server in the list."
> — *Moving Quicksilver into production* (2020).

Note: Cloudflare's *published* propagation figure is the qualitative "within seconds" plus the
30-second disconnect threshold. I did **not** find a published hard p99 propagation number
(e.g. "500 ms p99 global"). Any specific sub-second global-propagation p99 attributed to
Quicksilver is `[UNVERIFIED]` against Cloudflare's own posts — it is community inference.

### A.3 Consistency model: sequential consistency + monotonic sequence numbers + MVCC

> "Quicksilver has, from the start, provided sequential consistency to clients."
> — *Quicksilver v2: evolution of a globally distributed key-value store (Part 1)* (published
> **July 10, 2025**).
> <https://blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-1/>

> "It is now easily possible to detect whether an update was lost, by comparing the sequence
> number and making sure it is exactly one higher than the last message we have seen."
> — *Introducing Quicksilver* (2020).

**MVCC + sliding window** were added in v2 to preserve sequential consistency under the new
caching tiers (the old "everything everywhere" model trivially had it):

> v2 maintains consistency through "multiversion concurrency control (MVCC) and sliding window
> approaches." — *Quicksilver v2 Part 1* (2025); corroborated by InfoQ's summary,
> <https://www.infoq.com/news/2025/08/cloudflare-key-value-store/>.

> "while adding only about 500 MB of extra disk space usage" — the MVCC two-hour history window
> overhead. — *Quicksilver v2 Part 1* (2025).

So the contract is: **reads are eventually-consistent at the edge but *sequentially* ordered
(no gaps, monotonic per the sequence number); writes are linearizable at the root.** This is
exactly the split the consistency-contract should promise.

### A.4 v1 (LMDB, full replication) vs v2 (MVCC + tiered caching)

**v1 — full replication on LMDB.** Every node stored the whole dataset:

> v1: "each server has a full copy of the data and updates it through asynchronous
> replication." — *Quicksilver v2 Part 1* (2025).

> Storage engine choice: "we settled on a datastore library called LMDB after extensive
> analysis of different options." "LMDB is also optimized for low read latency rather than
> write throughput." — *Introducing Quicksilver* (2020).

The motivation for low-write/high-read is explicit: Quicksilver serves enormous read volume
but changes infrequently.

> Read-latency win vs the predecessor (Kyoto Tycoon): "for our DNS service, the 99th percentile
> of reads dropped by two orders of magnitude!" and average read "usually around 500
> microseconds." — *Introducing Quicksilver* (2020) / *Moving Quicksilver into production*
> (2020).

**v1.5 — replica/proxy split.** Full-replica nodes plus persistent-cache proxies:

> "replica, which stores the full dataset and proxy, which acts as a persistent cache."
> — *Quicksilver v2 Part 1* (2025).

**v2 — MVCC + three-tier caching** (the dataset outgrew "everything everywhere"). From
"Quicksilver v2 Part 2" (published **July 17, 2025**),
<https://blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-2-of-2/>:

> "Quicksilver V2 has three levels of storage. 1. Level 1: The local cache on each server that
> contains the key-values that have most recently been accessed. 2. Level 2: The data center
> wide sharded cache that contains key-values that haven't been accessed in a while, but do
> have been accessed. 3. Level 3: The replicas on the storage nodes that contain the full
> dataset." — *Quicksilver v2 Part 2* (2025).

> L2 is "divided into 1024 logical shards." — *Quicksilver v2 Part 2* (2025).

> Reactive prefetching: "Every resolved cache miss is prefetched by all servers in the data
> center," via "a stream of all resolved cache misses, to which all Quicksilver proxies in the
> same data center subscribe." — *Quicksilver v2 Part 2* (2025).

> Working-set motivation: "in large data centers approximately 20% of the keyspace was in
> use," "in small data centers… just about 1%," and "we see about ten times more negative
> lookups than positive ones." — *Quicksilver v2 Part 1* (2025).

### A.5 Published scale and latency numbers (separated by era)

| Metric | 2020 ("Introducing"/"Moving into production") | 2025 (v2 Parts 1 & 2) |
|---|---|---|
| Data centers / cities | "200 cities in 90 countries" | "330-city network" (InfoQ summary of v2) |
| KV pairs | "In 2019, we exceeded 1 billion" | "five billion key-value pairs" |
| Total dataset size | (not given) | "1.6TB dataset" (InfoQ summary of v2) |
| Reads | "2.5 trillion reads each day" (≈ 29 M/s avg) | "over three billion keys per second, worldwide" |
| Writes | "30 million write requests a day" (≈ 350/s avg) | (not restated; producers cited at "50 writes per second" example loads) |
| DB instances | "over 90,000 database instances across thousands of servers" | (per-server: "ten Quicksilver instances") |
| Read latency | avg "~500 microseconds"; p99 "dropped by two orders of magnitude" vs KT | "90% of requests in less than 1 ms," "99.9% of requests in less than 7 ms" |
| Cache hit rate | n/a (full replication) | L1 "99.9% or higher"; L1+L2 "99.99% or higher for the worst caching instance"; non-worst "higher than 99.999%" |
| Global propagation | "within seconds"; 30 s disconnect threshold | (not restated as a number) |

Primary sources for the 2020 column: *Introducing Quicksilver* and *Moving Quicksilver into
production*. For the 2025 column: *Quicksilver v2 Part 1*, *Part 2*, and the InfoQ summary
(InfoQ explicitly summarizing the two v2 posts:
<https://www.infoq.com/news/2025/08/cloudflare-key-value-store/>; the "330-city" and
"1.6TB / five billion" figures appear in InfoQ's summary of the posts).

> **Verbatim 2025 latency/hit-rate quotes** (from v2 Part 2):
> "it currently responds to 90% of requests in less than 1 ms"; "99.9% of requests in less
> than 7 ms"; "The level 1 cache hit-rate is 99.9% or higher, on average"; "The combined level
> 1 and level 2 cache hit-rate is 99.99% or higher for the worst caching instance"; "it serves
> over three billion keys per second, worldwide."

> **Era / writes-per-day caveat:** the "30 million writes/day ≈ 350/s" figure is from 2020.
> Cloudflare did not republish a writes-per-day number in the 2025 posts; treating 2020's
> ~350 writes/s as the *current* write rate is `[UNVERIFIED]`. (Configd's §0.1 baseline of
> 10k writes/s is ~28× Quicksilver's *2020* average write rate — a useful framing for §0.3,
> but only if the era is stated.)

### A.6 The single Quicksilver insight for ADR-0030

> **Consensus for the source of truth; coordination for distribution.** Cloudflare runs *one*
> Raft root for durable ordering, then fans out asynchronously. The edge has *zero* Raft
> participation and still achieves 3 B reads/s at sub-ms p90 with sequential consistency. This
> is precisely the topology ADR-0030 proposes, and Cloudflare's published numbers are the
> existence proof that it scales where global consensus cannot.

---

## (B) etcd vs. true multi-region / geo-distributed consensus

### B.1 etcd / single-group Raft: why it does not scale *global writes*

etcd is single-group Raft (the same engine Quicksilver's root uses). Its scaling envelope is
**fault tolerance, not write throughput**, and it degrades on the WAN. From the official etcd
docs:

> "an etcd cluster probably should have no more than seven nodes" … "the write performance
> suffers because data must be replicated across more machines."
> — etcd FAQ, <https://etcd.io/docs/v3.4/faq/>.

> "the latency from crossing data centers will be somewhat pronounced because at least a
> majority of cluster members must respond to consensus requests."
> — etcd FAQ, <https://etcd.io/docs/v3.4/faq/>.

> Cluster sizing & odd membership: "It is recommended to have an odd number of members in a
> cluster." — etcd hardware/FAQ docs,
> <https://etcd.io/docs/v3.6/op-guide/hardware/>.

> Write durability is fsync-bound on *every* node: etcd "should run on a block device that can
> write at least 50 IOPS of 8KB sequentially, including fdatasync, in under 10ms."
> — etcd hardware recommendations, <https://etcd.io/docs/v3.6/op-guide/hardware/>.

> Storage ceiling: "8GB is a suggested maximum size for normal environments" (default backend
> quota 2 GiB). — etcd FAQ, <https://etcd.io/docs/v3.4/faq/>.

**The structural argument** (corroborated by a community engineering write-up,
<https://learnkube.com/etcd-breaks-at-scale>):

> "In a Raft cluster, there's always exactly one leader." … "Adding more etcd nodes doesn't
> increase the number of writes you can handle. In fact, it can make things worse because the
> leader has to replicate data to even more followers." … "you can't scale writes horizontally
> in a Raft cluster." — learnkube (secondary; consistent with the official etcd quotes above).

**Why this matters for ADR-0030.** A single Raft group is *correct* for a centralized root
(that is exactly what Quicksilver does), but it is **not** a vehicle for *global write
scale-out* and is **actively harmed** by stretching its voters across regions: every commit
must wait for a cross-region majority, and adding regional voters only raises the quorum size.
The conclusion is not "Raft is bad" — it is "do not put the Raft *voting set* on the WAN, and
do not look to a bigger Raft group for write throughput." (etcd's own answer to scale-out is
*sharding into independent groups*, which is the multi-Raft path discussed next — and which
ADR-0023 already defers.)

### B.2 True multi-region / geo-distributed consensus: where voters go and what it costs

These systems *do* run consensus across regions. The lesson is the **price**, paid in every
write's commit latency, and the elaborate machinery they add to claw back *read* latency.

**Spanner (Paxos + TrueTime, external consistency).** Spanner places Paxos voting replicas
across regions and pays a *commit-wait* tied to clock uncertainty *on top of* cross-region
Paxos:

> "Under external consistency, the system behaves as if all transactions run sequentially, even
> though Spanner actually runs them across multiple servers (and possibly in multiple
> datacenters)…" — Google Cloud, *Spanner: TrueTime and external consistency*,
> <https://docs.cloud.google.com/spanner/docs/true-time-external-consistency>.

> TrueTime uncertainty ε is kept small but nonzero — "Google keeps epsilon under 7 ms, usually
> around 4 ms," and commit-wait is "about 7 ms… in practice the average wait is about half
> that" — i.e. the commit-wait is *irreducible overhead for the clock bound alone*, layered on
> the cross-region Paxos round trip. (Secondary syntheses of the OSDI 2012 Spanner paper;
> the *concept* is in the Google Cloud doc above, the *numbers* originate in the Spanner paper.
> The specific ε/commit-wait figures are `[secondary]` here — primary is Corbett et al.,
> *Spanner*, OSDI 2012.)

The takeaway: even Google, with hardware atomic clocks, cannot make a *globally consistent
write* cheap — it pays cross-region Paxos **plus** commit-wait.

**CockroachDB multi-region (multi-Raft + closed timestamps).** Cockroach shards data into
per-range Raft groups and offers `GLOBAL` tables for read-everywhere data — but the *write*
cost is explicit and large:

> "write latency… can be as high as 800ms with the default cluster configuration (as of 22.1)
> in a cluster spanning the globe" and optimization brings "latency to 250ms or less."
> — Cockroach Labs, *How to optimize write latency for global tables* (Nov 8, 2022),
> <https://www.cockroachlabs.com/blog/optimize-write-latency/>.

> The mechanism: global-table "writes to these ranges are created slightly in the future,
> making them invisible to current time reads," and "After the transaction commits, control is
> not returned to the client until the current time catches up to the future write timestamp."
> — *ibid.* (This is the read-vs-write asymmetry: reads are local/cheap, writes pay the future
> offset + replication.)

> Reads, by contrast: "Read latency in global tables can be as low as several milliseconds,
> similar to stale follower reads." — *ibid.*

**The universal pattern across Spanner / CockroachDB / TiKV.** They *don't* make cross-region
consensus writes fast — they make **reads** fast by giving followers a *safe timestamp*
(closed timestamp / safe-ts / TrueTime safe time) so reads are served locally without contacting
the leader. That is the same "bounded-staleness follower read" idea, and it is the *read-path*
half of what ADR-0030 wants — but ADR-0030 gets it via async fan-out to edges (Quicksilver
style) rather than via in-Raft non-voting replicas, which avoids the cross-region *write*
quorum entirely.

**Geo-Raft / hierarchical / multi-Raft variants** (TiKV multi-Raft, hierarchical Raft, FlexiRaft)
relax *which* replicas must ack, but they cannot beat the physics: a strongly-consistent write
must reach a *durable majority*, and if that majority spans regions, the commit floor is the
cross-region RTT to the median voter (see §C). FlexiRaft and Flexible Paxos let you *choose*
the quorum intersection to favor a region, but the write still pays at least one cross-region
RTT whenever the durable set isn't co-located. (FlexiRaft summary:
<http://muratbuffalo.blogspot.com/2024/09/flexiraft-flexible-quorums-with-raft.html>.)

---

## (C) Cross-region quorum latency math vs. §0.1 targets

### C.1 The Raft commit-latency model (one citable formula)

A Raft write commits when a **majority including the leader** has durably appended it. With
`N` voters, the leader needs `floor(N/2)` follower acknowledgements, and — because it can
ignore the slowest followers — the **commit latency floor is the round-trip time to the
`floor(N/2)`-th *closest* voter.** This is standard Raft (Ongaro & Ousterhout, 2014) and is
restated cleanly in the SDN-control-plane Raft study:

> "Updates in RAFT require a single-round trip delay between the leader and the preferred
> follower majority (the fastest to reach followers)." … failure of a preferred follower
> forces "an additional 'slower' follower," raising commit time.
> — *Response Time and Availability Study of RAFT Consensus*, arXiv 1902.02537,
> <https://arxiv.org/pdf/1902.02537>.

So: `commit_floor(network) = RTT to the (floor(N/2))-th nearest voter`. This is a **floor** —
it omits leader fsync, follower fsync, batching delay, queueing, and TLS — each of which only
*adds* latency.

### C.2 Inter-region RTTs (measured, cited)

Round-trip times between AWS regions, from a static published measurement table
(<https://latency.bluegoat.net/>, AWS inter-region grid; cross-checked against the AWS-builders
analysis using CloudPing + AWS Network Manager,
<https://dev.to/aws-builders/looking-at-aws-inter-region-latency-through-distance-34eh>):

| Region pair | RTT (ms) | Source / cross-check |
|---|---|---|
| us-east-1 ↔ us-west-2 | **63.40** | bluegoat |
| us-east-1 ↔ eu-west-1 | **68.55** | bluegoat |
| us-east-1 ↔ ap-northeast-1 (Tokyo) | **149.16** | bluegoat |
| us-east-1 ↔ ap-southeast-1 (Singapore) | **219.64** | bluegoat |
| eu-west-1 ↔ ap-southeast-1 | **171.04** | bluegoat |
| us-west-2 ↔ ap-northeast-1 (Tokyo) | **98.04** | bluegoat; cross-check ~97–103 ms (AWS-builders, CloudPing/AWS Net Mgr) |
| eu-west-1 ↔ ap-northeast-1 (Tokyo) | ~202–206 (cross-check) | AWS-builders (CloudPing 205.86 / AWS Net Mgr 202.00) |

These agree (±~5 ms) with the RTT matrix already in `docs/research.md:454-466` and with the
RTTs ADR-0015 uses, so the arithmetic below is robust to the exact source.

### C.3 Commit-latency floors for 3 / 5 / 7-node placements (arithmetic shown)

All numbers are **network-only floors**; real commits add fsync (etcd target: each node's
fdatasync **under 10 ms**, per §B) + batching + queueing.

**3-voter global group, leader in us-east-1 (needs 1 follower ack = nearest voter):**

| Voter set | Sorted follower RTTs from leader | floor(N/2)=1 → 1st nearest | Commit floor |
|---|---|---|---|
| {us-east-1, us-west-2, eu-west-1} | 63.40, 68.55 | 63.40 | **63.4 ms** |
| {us-east-1, eu-west-1, ap-southeast-1} | 68.55, 219.64 | 68.55 | **68.55 ms** |

A 3-voter group survives **zero** region failures (loss of either follower's region can still
leave a 2/3 majority, but loss of the leader's region loses the leader; and any single region
failure that removes a voter drops you to 2 voters where quorum = 2, i.e. no further fault
tolerance). That is why production global groups use 5.

**5-voter global group, leader in us-east-1 (needs 2 follower acks = 2nd-nearest voter):**

| Voter set | Sorted follower RTTs | floor(N/2)=2 → 2nd nearest | Commit floor |
|---|---|---|---|
| {us-east-1, us-west-2, eu-west-1, ap-northeast-1, ap-southeast-1} | 63.40, 68.55, 149.16, 219.64 | **68.55** | **68.55 ms** |

This is the configuration ADR-0015 assumes ("~68 ms"). My computation confirms the **floor**
is ~68.5 ms — **for a client co-located with the leader in us-east-1.** It does *not* include
fsync (+≤10 ms/node), batching (Quicksilver uses 500 ms; even a 5–20 ms micro-batch adds up),
or the client's own RTT to the leader.

**The penalty ADR-0015 omits — a non-US client.** §0.1 says `write p99 < 150 ms
**cross-region**`, i.e. the budget must hold for a writer *not* next to the leader. End-to-end
client-perceived latency = `RTT(client↔leader) + commit_floor_at_leader`:

| Writing client | Leader | client↔leader RTT | + commit floor | **End-to-end floor** | vs §0.1 150 ms |
|---|---|---|---|---|---|
| us-east-1 (co-located) | us-east-1 | ~0 | 68.55 | **~68.6 ms** | ✅ under |
| eu-west-1 | us-east-1 | 68.55 | 68.55 | **~137.1 ms** | ⚠️ at the edge of budget, before fsync/batch |
| ap-southeast-1 | us-east-1 | 219.64 | 68.55 | **~288.2 ms** | ❌ ~1.9× over budget |
| ap-northeast-1 | us-east-1 | 149.16 | 68.55 | **~217.7 ms** | ❌ ~1.45× over budget |

**This is the load-bearing result.** A globally-consistent write that an APAC client must route
to a US-anchored 5-voter group is **~288 ms at the *floor***, vs. a `< 150 ms` target — and no
amount of voter re-placement fixes it for *all* clients simultaneously, because moving the
leader closer to APAC pushes US/EU clients over budget instead. (Symmetrically: leader in
Tokyo gives APAC clients ~98 ms but US-east clients ~149 ms client-RTT + commit.)

**7-voter group, leader in us-east-1 (needs 3 follower acks = 3rd-nearest voter):**

- If the 3rd-nearest voter is *still in the US/EU cluster* (e.g. adding us-east-2 ~13 ms and a
  4th nearby): 3rd nearest ≈ **68.55 ms** — same floor as 5-node, just more fault tolerance.
- If the 7 voters are spread "for survivability" so the 3rd-nearest is ap-northeast-1: commit
  floor jumps to **~149 ms**, i.e. *the act of spreading voters for region-survival pushes the
  commit floor up.* 7 nodes also means more fsyncs gating each commit.

> **Conclusion of the math.** A *centralized* group (all voters in one low-RTT cluster) can hit
> the §0.1 commit budget — but that is **not multi-region write consensus**, it is exactly the
> Quicksilver *centralized root*. The moment voters span APAC↔US↔EU to get "global write
> consensus / region survival," the commit floor (and especially the *non-co-located client*
> end-to-end latency) **exceeds §0.1's `150 ms` target by 1.5–2×, before fsync and batching.**
> Spanner (~7 ms commit-wait *for clocks alone*, on top of cross-region Paxos) and CockroachDB
> (`GLOBAL`-table writes 250–800 ms) independently confirm this floor is physics, not a bug.

### C.4 How the Quicksilver shape meets §0.1 where global consensus cannot

| §0.1 target | Global/hierarchical Raft *write* consensus | Quicksilver-shaped (centralized write + async fan-out) |
|---|---|---|
| **Write commit p99 < 150 ms cross-region** | ❌ ~288 ms floor for APAC client to US group (§C.3); CockroachDB 250–800 ms confirms | ✅ Write commits in **one low-RTT region** (centralized root); client routes to the root region. Quicksilver root is centralized + 500 ms *batch*, but commit itself is intra-cluster. |
| **Propagation p99 < 500 ms global** | N/A (consensus = the write, not propagation) | ✅ Asynchronous fan-out. Quicksilver publishes "within seconds" (qualitative); a push-based tree with bounded-staleness contract is the mechanism. A hard 500 ms p99 is Configd's *target*, not Quicksilver's published number `[UNVERIFIED for Quicksilver]`. |
| **Edge read p99 < 1 ms (in-process)** | ❌ a Raft follower read still needs ReadIndex/lease confirmation (≥ intra-region RTT) or closed-timestamp staleness | ✅ Edge serves from a **local copy**, no consensus. Quicksilver: "90% of requests in less than 1 ms," "99.9%… less than 7 ms." |
| **Operational complexity / zero external coordination** | ❌ multi-Raft + placement driver + closed-timestamp side-transport (CockroachDB-class machinery) | ✅ One root group + a fan-out tree. (Note: ADR-0015's design *adds* a PlacementDriver and closed-timestamp side-transport — the very complexity §0.3 wants to avoid.) |

---

## (D) ADR-0030 draft material: "Influenced by" + "Reasoning"

> Drop-in text for the topology-architect. Every external claim carries a citation; numbers
> Cloudflare did not publish are marked. Keep the era tags (2020 vs 2025) when quoting scale.

### Suggested ADR-0030 framing

**Title:** ADR-0030: Centralized Strongly-Consistent Writes with Asynchronous Bounded-Staleness
Edge Fan-out (Quicksilver-Shaped Topology); Reject Global Multi-Region / Hierarchical Raft
Write Consensus.

**Status:** Proposed. Supersedes the *write-topology* portion of ADR-0015 (which described a
global 5-voter cross-region Raft group + regional groups that was never implemented — see
`STATE-OF-REALITY.md §4.1`). Consistent with ADR-0023 (multi-Raft deferred) and ADR-0024
(cross-DC bridge deferred / "WAN-stretched Raft violates the SLO").

### Influenced by

- **Cloudflare Quicksilver (primary baseline).** Borrowed mechanism: **consensus for the
  source of truth, coordination for distribution** — a *single centralized* Raft root (built
  on "the etcd Raft package") for durable, ordered writes, then *asynchronous hierarchical
  fan-out* to every data center where edges serve sequentially-consistent local reads with
  **no edge consensus**. Cloudflare distributes a config change "to 200 cities in 90 countries…
  within seconds" (2020) and at v2 serves "over three billion keys per second" at "90% of
  requests in less than 1 ms" (2025). Also borrowed: **monotonic sequence numbers** for gap
  detection ("exactly one higher than the last message we have seen"), **MVCC + sliding
  window** for sequential consistency under tiered caching, and a **30-second disconnect /
  catch-up threshold** for slow consumers.
  Sources: *Introducing Quicksilver* (2020,
  <https://blog.cloudflare.com/introducing-quicksilver-configuration-distribution-at-internet-scale/>);
  *Moving Quicksilver into production* (2020,
  <https://blog.cloudflare.com/moving-quicksilver-into-production/>);
  *Quicksilver v2 Part 1* (2025,
  <https://blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-1/>);
  *Quicksilver v2 Part 2* (2025,
  <https://blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-2-of-2/>).

- **etcd / single-group Raft.** Borrowed: the *centralized* single-group Raft engine for the
  write root (same engine Quicksilver's root uses). Borrowed as a *constraint*, not a goal:
  etcd's own guidance that a cluster "probably should have no more than seven nodes" and that
  cross-DC consensus latency is "pronounced" tells us to keep the voting set **small and
  region-local**. Source: etcd FAQ + hardware docs
  (<https://etcd.io/docs/v3.4/faq/>, <https://etcd.io/docs/v3.6/op-guide/hardware/>).

- **CockroachDB / Spanner closed-timestamp & safe-timestamp follower reads.** Borrowed
  *concept* (a "safe timestamp" lets followers serve bounded-staleness reads locally) but
  **rejected the in-Raft delivery** (cross-region voting/non-voting replicas). We get the
  read-locality benefit via Quicksilver-style edge copies instead, avoiding the cross-region
  *write* quorum. Sources: Cockroach Labs *optimize write latency for global tables* (2022,
  <https://www.cockroachlabs.com/blog/optimize-write-latency/>); Google Cloud *Spanner:
  TrueTime and external consistency*
  (<https://docs.cloud.google.com/spanner/docs/true-time-external-consistency>).

### Reasoning

- **Cross-region write consensus cannot meet §0.1's `write p99 < 150 ms`.** With *measured*
  AWS RTTs, a 5-voter global Raft group (leader us-east-1) has a **network-only commit floor of
  ~68.5 ms** (RTT to the 2nd-nearest voter, eu-west-1), and a client in **ap-southeast-1
  writing to it pays ~288 ms end-to-end** (full client↔leader RTT 219.64 ms + 68.55 ms commit
  floor) — **before** fsync (etcd targets each node's fdatasync under 10 ms), batching, and
  queueing. That is ~1.9× over the 150 ms budget, and re-placing the leader only moves the
  pain to a different region's clients. CockroachDB independently reports `GLOBAL`-table writes
  "as high as 800 ms… [optimizable to] 250 ms or less"; Spanner pays ~7 ms commit-wait for
  clock uncertainty *alone* on top of cross-region Paxos. The latency is physics, not an
  implementation choice. (Math in §C; RTTs: <https://latency.bluegoat.net/>; Raft commit model:
  arXiv 1902.02537.)

- **A single Raft group does not scale *write throughput* and is *harmed* by WAN spread.**
  Official etcd guidance: "the write performance suffers because data must be replicated across
  more machines"; adding voters raises the quorum and the fsync count without adding write
  capacity. Therefore the only viable use of one group is a **small, region-local, centralized
  root** — exactly Quicksilver's shape — not a global write fabric. (etcd FAQ.)

- **The Quicksilver shape demonstrably hits all three latency budgets.** Centralized writes
  commit inside one low-RTT cluster; asynchronous fan-out gives "within seconds" global
  propagation (Configd targets a hard `< 500 ms p99` — a *tightening* of Quicksilver's
  qualitative figure, since Quicksilver never published a hard propagation p99
  `[UNVERIFIED for Quicksilver]`); and edges serve from a **local copy with no consensus**,
  matching Quicksilver's "90% of requests in less than 1 ms." Edge reads are deliberately
  **not** linearizable — they are sequentially-consistent and bounded-stale, which is the
  consistency-contract split this ADR commits to.

- **It also wins §0.3's operational-complexity axis.** The rejected ADR-0015 design requires a
  PlacementDriver, scope-aware shard routing, cross-region non-voting replicas, and a
  closed-timestamp side-transport (200 ms cadence) — CockroachDB-class machinery. The
  Quicksilver shape needs one root group plus a fan-out tree, with monotonic sequence numbers
  for gap detection — far less coordination surface.

### Rejected alternatives (for the ADR's "Rejected Alternatives" section)

- **Global single Raft group spanning regions:** ~288 ms end-to-end write floor for APAC
  clients vs. 150 ms target (§C); adding voters worsens it (etcd FAQ). Rejected on latency.
- **Hierarchical Raft (global group + regional groups) — ADR-0015's design:** the *global*
  group still pays the §C cross-region floor for any GLOBAL-scope write from a distant client;
  adds PlacementDriver + closed-timestamp side-transport complexity that loses §0.3's
  operational-complexity axis; and the cross-group ordering it must disclaim is a correctness
  footgun. Never implemented (`STATE-OF-REALITY.md §4.1`). Rejected on latency + complexity.
- **Leaderless / EPaxos-style global writes:** fast path still needs a (fast) cross-region
  quorum; under conflicts degrades to 2 RTT; complexity and dependency-graph commit are
  unjustified for low-frequency config writes (Quicksilver's *2020* write rate was ~350/s).
  Rejected on complexity-for-no-latency-gain for this workload.
- **CockroachDB/Spanner-style in-Raft non-voting replicas for cross-region reads:** solves the
  *read* problem but keeps the cross-region *write* quorum; we get read-locality more cheaply
  via async edge copies. Rejected for the write path; its *closed-timestamp read concept* is
  borrowed (see "Influenced by").

---

## Appendix: source ledger (primary unless marked)

**Cloudflare Quicksilver (primary — Cloudflare engineering blog):**
- *Introducing Quicksilver: Configuration Distribution at Internet Scale* (Mar 30, 2020):
  <https://blog.cloudflare.com/introducing-quicksilver-configuration-distribution-at-internet-scale/>
- *Moving Quicksilver into production* (Nov 25, 2020):
  <https://blog.cloudflare.com/moving-quicksilver-into-production/>
- *Quicksilver v2 … Part 1* (Jul 10, 2025):
  <https://blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-1/>
- *Quicksilver v2 … Part 2* (Jul 17, 2025):
  <https://blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-2-of-2/>
- Cloudflare network scale (330 cities / 125 countries): <https://www.cloudflare.com/network/>

**etcd (primary — official docs):**
- FAQ: <https://etcd.io/docs/v3.4/faq/>
- Hardware recommendations: <https://etcd.io/docs/v3.6/op-guide/hardware/>

**Geo-distributed consensus (primary docs + vendor engineering blogs):**
- CockroachDB, *How to optimize write latency for global tables* (Nov 8, 2022):
  <https://www.cockroachlabs.com/blog/optimize-write-latency/>
- Google Cloud, *Spanner: TrueTime and external consistency*:
  <https://docs.cloud.google.com/spanner/docs/true-time-external-consistency>

**Latency / Raft model:**
- AWS inter-region RTT table (measured, static): <https://latency.bluegoat.net/>
- AWS inter-region latency analysis (CloudPing + AWS Network Manager cross-check):
  <https://dev.to/aws-builders/looking-at-aws-inter-region-latency-through-distance-34eh>
- Raft commit-latency model: *Response Time and Availability Study of RAFT Consensus*,
  arXiv 1902.02537: <https://arxiv.org/pdf/1902.02537>
- FlexiRaft / flexible WAN quorums (secondary):
  <http://muratbuffalo.blogspot.com/2024/09/flexiraft-flexible-quorums-with-raft.html>

**Secondary (clearly labeled; used only to cross-check primary numbers):**
- InfoQ summary of the v2 posts (330-city, 1.6 TB / 5 B pairs, 3 B keys/s, p90<1ms, p99.9<7ms,
  99.99%+ hit rate): <https://www.infoq.com/news/2025/08/cloudflare-key-value-store/>
- learnkube, *Why etcd breaks at scale* (single-leader write-scaling argument):
  <https://learnkube.com/etcd-breaks-at-scale>

**`[UNVERIFIED]` items (could not confirm against a primary source):**
- A hard Quicksilver *propagation p99* number (e.g. "500 ms p99 global"). Cloudflare published
  only "within seconds" + a 30 s disconnect threshold. Configd's `< 500 ms p99` is a *target*,
  not a Quicksilver-published figure.
- Quicksilver's *current* (2025) writes/day or writes/s. Only the 2020 "30 million/day ≈ 350/s"
  is published; treating it as current is unverified.
- Spanner's exact ε / commit-wait numbers are from secondary syntheses of the OSDI 2012 paper;
  the *concept* (external consistency, commit-wait) is in the cited Google Cloud doc, the
  *figures* trace to the Spanner paper rather than the doc I fetched.
