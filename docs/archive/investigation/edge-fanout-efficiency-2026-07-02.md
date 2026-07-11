# Edge fan-out efficiency investigation -- full-stream-then-filter, the trust model, and verifiable server-side filtering

Investigation from 2026-07-02, scoping only -- nothing in the repo changed except this file. Findings are
grounded in code (file:line), the threat model, the ADRs, and the EC2 measurements, cross-checked against
source.

---

## 0. Summary

The edge SUBSCRIBE plane streams the verbatim, leader-signed delta chain to every subscriber and filters
at the receiver (`PrefixStorageFilter`, after Ed25519 verification). That is O(edges x total-writes) in
both network egress and edge verify-CPU, versus an ideal O(edges x f x writes) where `f` is the keyspace
fraction an edge actually wants. Waste ratio is about 1/f.

Three questions decide whether the fix is cheap or a real feature. The answers:

1. **Trust model (the pivot):** the current deployment is a trusted, operator-run, single-hop,
   mTLS-both-ends system where the fan-out relay and the signer are the same in-process entity. The
   untrusted-relay tier that ADR-0038's no-suppression rule defends against (Plumtree/HyParView) is
   constructed but dormant -- `broadcast()` is never called on the data path; edges never forward to peer
   edges. So the "no server-side filtering" rule is belt-and-suspenders plus forward-compat, not a hard
   requirement forced by any adversary in the current threat model. It should be a configurable posture,
   not a one-way door.

2. **Scale cost:** network egress is the binding ceiling; edge verify-CPU is not. A single edge only
   saturates one core near about 14k writes/s -- ~9x above the measured ~1.6k w/s aggregate write
   ceiling -- so no edge is verify-bound at any rate this cluster can produce. But egress is centralized
   (no distribution tree is built): aggregate = E x W x ~680 B, and a single fan-out node tops out at
   ~1-2k edges at the measured write rate. Benign at hundreds of edges; a real problem at the ADR-0011
   target of thousands-to-millions.

3. **Can the signed chain support verifiable filtering?** Not cheaply, and a load-bearing structural fact
   reframes it: the signature covers only `mutations || epoch || nonce`, not the version position
   (`fromVersion`/`toVersion`/`seq`). So today's anti-suppression property actually leans on TLS, not the
   signature. Against a key-less relay, the only sound completeness proof is a leader-signed
   authenticated dictionary (per-range Merkle), which is a chain redesign (ADR-0038's own named upgrade
   path). A cheaper sound sub-chain exists only by changing the product model to declared disjoint
   topics.

**Recommendation:** a two-track, posture-flagged fix. The near-term cheap path -- server-side prefix
filtering on the fan-out drain, with the edge trusting the server (exactly the trust model the watch
plane already ships) -- is sound for the co-located mTLS deployment and directly relieves the binding
egress ceiling by the factor `f`. The long-term path -- leader-signed Merkle skip-evidence -- is only
warranted if and when a genuinely separate distribution tier is deployed. Independently of either, close
the latent signature-position gap (make the signed payload cover seq/from/to); it is cheap, hardens the
current full-chain claim, and is a prerequisite for any future skip-proof.

---

## 1. The trust model -- the pivot

### 1.1 What the current deployment actually is: trusted, operator-run, mTLS single-hop, relay == signer

- The edge fan-out surface (`--edge-port`) is mTLS-required both ends: `FanOutServer.java:339` /
  `NettyFanOutServer.java:249` both `setNeedClientAuth(true)`. A dial without a trusted client cert fails
  the handshake (`operator-runsheet.md:93-94`).
- The edge cert-DN must hold READ over the root prefix `""` (auth-on) to hydrate the whole store
  (`operator-runsheet.md:123-125`, `known-limitations.md:84-91`). Edges are operator-provisioned,
  authenticated, authorized -- not anonymous clients.
- The wired data path is single-hop and in-process: Raft leader `apply` -> `ConfigStateMachine.signCommand`
  (Ed25519) -> per-shard `FanOutBuffer.append` (already-signed delta) -> `NettyFanOutServer` streams the
  signed chain over mTLS -> edge `DeltaApplier` verifies. The fan-out server streams pre-signed bytes; it
  never re-signs and never holds the signing key (ADR-0038 requirement, satisfied by construction).
- The Plumtree/HyParView multi-tier relay is dormant scaffolding. `ConfigdServer` constructs
  `PlumtreeNode`/`HyParViewOverlay` (`:641-642`) and calls `plumtreeNode.tick()` (`:1051`), but
  `broadcast()` is never invoked on the commit/notify path (ADR-0030 notes it is benchmark-only). Edges do
  not forward to peer edges. ADR-0003/0011 (edge-to-edge gossip, 10k-1M edges) is design aspiration;
  ADR-0030 (Accepted) is what shipped: one centralized Raft root plus async full fan-out.

### 1.2 The threat model has no untrusted-relay adversary

`docs/archive/security/threat-model.md:62-69` enumerates A1 network-MITM (no keys), A2 storage-writer, A3
malicious Raft peer (install-snapshot), A4 API client, A5 hostile wire input, A6 supply-chain. A
"malicious edge relay that forwards to / suppresses for other edges" is not in the set. The adversary the
no-suppression rule most strongly answers is simply not enumerated.

### 1.3 ADR-0038's rule has two legs -- only one depends on trust

- **Leg (a) -- no coalescing:** collapsing signed deltas produces bytes the leader never signed, which
  breaks per-delta Ed25519 verification. This is a hard, trust-independent authenticity requirement that
  must never be relaxed.
- **Leg (b) -- no prefix filtering (suppression-detectability):** a relay-asserted skip-marker isn't
  leader-signed, so a compromised/buggy relay "could silently suppress arbitrary keys." This leg is the
  only one that depends on the relay being untrusted. Today the filtering entity is the signer's own
  process -- a compromise there already owns the key and can forge anything -- so leg (b) protects
  against nothing a compromised signer couldn't already do. Its real value is detecting a buggy relay or
  transport corruption (defense-in-depth) and preserving the option to run a genuinely separate untrusted
  tier later.
- Leg (b)'s worst case is moot today anyway: `secure/`/`GLOBAL` keys are read linearizably from the root
  (ReadIndex), never from bounded-stale edge copies (ADR-0030, invariant A1). Suppression at the fan-out
  tier cannot yield a security-decision bypass; its residual is ordinary staleness, which the staleness
  state machine and commit-timestamp clock surface.

### 1.4 The clinching evidence: the watch plane already filters server-side under exactly this trust model

The client-facing watch plane already does server-side, post-authorization filtering: `WatchMultiplexSink`
filters the live tail per-NOTIFY, and `FilteringReplaySource` narrows the catch-up snapshot to each
watch's authorized target. It drops the signed chain (WATCH_EVENT carries raw key/value pairs, no
signature/epoch/version) and trusts the server (mTLS plus server-side authz). Server-side-filter plus
client-trusts-server is already the shipped model for one of the two planes. The full-chain SUBSCRIBE
plane is the outlier -- kept not because of a live adversary but because the edge cache's gap-detection
mechanism relies on chain contiguity.

### 1.5 What relaxing the rule breaks vs. weakens vs. leaves untouched

| Concern | Effect of server-side filtering | Note |
|---|---|---|
| Gap detection (`DeltaApplier`, `fromVersion==currentVersion`) | breaks | A dropped delta jumps the next `fromVersion`, so the edge reads it as data loss and triggers a re-snapshot storm. Needs an authenticated cursor-advance / skip token. |
| Global version cursor / frontier (`VersionCursor`, ADR-0039) | weakens | Server must assert the cursor value (relay-asserted, not chain-derived). Sound iff server trusted. |
| Suppression-detectability / chain completeness | weakens, by design | Downgrades from cryptographic to operational (trust server; staleness state machine plus metrics catch wholesale stalls). Acceptable only in the operator trust domain. |
| Per-record authenticity (Ed25519) plus anti-replay (epoch+nonce) | preserved | iff the fix drops whole signed deltas and never coalesces/rewrites. |
| `PoisonPillDetector`, `BloomFilter` negative cache | unaffected | Edge-local, per-subscription; simply see fewer keys. |
| Edge SUBSCRIBE hydration | unaffected today | Hydration is edge-from-server, not edge-from-edge (no Plumtree). Would matter only if edge-to-edge hydration existed. |
| Snapshot replay | preserved | A filtered-snapshot path already exists (`FilteringReplaySource`). |
| Watch plane, audit trail | unaffected | Watch already filtered; audit lives on the control plane, not the edge. |

### 1.6 Verdict

Configurable-both, defaulting to trusted-domain. The current deployment is unambiguously a trusted,
in-process, mTLS single-hop fan-out. The cheap fix is sound and merely realigns the SUBSCRIBE plane with
the watch plane's existing trust model. Non-negotiable carve-outs: (1) never coalesce/rewrite -- leg (a)
stands; (2) provide an authenticated cursor-advance/skip token; (3) `secure/`/`GLOBAL` stay on the
linearizable root-read path (already true). Guardrail: the moment a separate distribution tier or
edge-to-edge forwarding is deployed, the untrusted-relay adversary becomes real and the no-suppression
guarantee (or leader-signed skip-evidence) must be restored -- hence a posture flag, not a one-way door.

---

## 2. The real scale cost

### 2.1 The math and the record size

Cost is O(E x W) network plus O(E x W) Ed25519 verifies vs. the ideal O(E x f x W); waste ratio is about
1/f (an edge wanting 1% of the keyspace moves and verifies ~100x what it needs).

Record size, computed exactly from the wire codec (`EdgeFrameCodec.encodeNotificationInto:320-343`):
fixed header 52 B plus batch (about 556 B for a 32 B key plus 512 B value) plus Ed25519 signature 64 B
plus nonce 8 B, about 680 B per write per edge at the measured 512 B value size; about 1.2 KB at a 1 KB
value. Overhead floor is ~168 B/record regardless of value (signature plus framing dominate small
values). Cross-check: ADR-0038 section 3 uses "~1 KB typical, 80 Mbit/s per subscriber at 10k w/s,"
consistent with W x recordSize.

### 2.2 What is measured vs. computed

- **Measured on metal (write/consensus plane only):** closed-loop clean knee N=1 656, N=2 1075, N=3
  1607 w/s (2.45x on 3 machines); single-box plateau ~1100; single-group knee ~800
  (`ec2-horizontal-2026-07-01/02-scaling-curve.md`). These runs used no `--edge-port` -- the fan-out plane
  was never exercised; the 18.5 MB/s per-box tx there is Raft replication, not fan-out. Cluster-bound by
  per-group heartbeat-starvation churn at ~62% CPU / <1% NIC.
- **Measured (JMH micro):** NOTIFY frame encode `prodEncodeIntoByteBufPooled` 7.3 microseconds/frame;
  ADR-0043 shows io_uring regresses ~2x at 1024 subscriber streams (Epoll is the default) -- the only
  high-fan-out data point.
- **Not measured anywhere in the repo:** end-to-end fan-out throughput vs. edge count
  (`FanOutLoadClientMain` takes a `subscribers` arg but no recorded results exist); Ed25519 verify cost
  (no benchmark at all); combined fan-out plus verify; per-edge egress on metal.

### 2.3 Ed25519 verify cost -- external estimate (not measured in repo)

Code uses the JDK built-in SunEC `Signature.getInstance("Ed25519")` (pure-Java, re-instantiated per
delta). Published JDK figures: ~40-150 microseconds/core, central ~70 microseconds, about 14k verifies/s
per core. (Native libsodium/OpenSSL would be ~10-30 microseconds, but the code does not use them.)

### 2.4 Where it bites

**(a) Edge verify-CPU -- not the near-term ceiling.** Each edge verifies the whole chain (W verifies/s,
independent of E). At 70 microseconds/verify: 800 w/s is 5.6% of one core; 1600 is 11%; 5000 is 35%. A
single edge saturates one core only near W of about 14k w/s (~9x the measured write ceiling). The waste
is real (at f=1%, ~99% of that CPU verifies discarded deltas) but small in absolute terms and distributed
one-W-per-edge, so no single machine feels it.

**(b) Server/fleet egress NIC -- the binding ceiling.** There is no distribution tree built (ADR-0011
Layer 2 is not implemented); edges hang off a fan-out server as 1:1 sessions, so egress is centralized.
Aggregate = E x W x 680 B:

| E \ W | 800 w/s | 1600 w/s | 5000 w/s |
|--:|--:|--:|--:|
| 10 | 0.044 Gbps | 0.087 | 0.27 |
| 100 | 0.44 | 0.87 | 2.72 |
| 1000 | 4.35 | 8.7 (at ceiling) | 27.2 (infeasible) |
| 5000 | 21.8 (infeasible) | 43.5 (infeasible) | 136 (infeasible) |

Per-fan-out-node edge ceiling at 10 Gbps sustained: W=800 gives ~2300 edges; W=1600 gives ~1150; W=5000
gives ~370. A single fan-out node tops out at ~1-2k edges at the measured write rate -- far short of
ADR-0011's "10k edges/node" target (which assumed the un-built tree). Adding fan-out servers does not
reduce total egress; each re-ingests and re-emits the full chain.

**(c) Server fan-out CPU -- secondary, batching-sensitive.** Encoding is per-edge (not serialize-once).
At 64 notifs/frame, E=1000/W=1600 is about 18% of a core; at the worst case of 1 notif/frame (caught-up
real-time edges) the same point is about 6 cores. NIC-bound first, but degrades sharply if batching is
thin.

### 2.5 Verdict

Network egress dominates the waste; edge verify-CPU does not bite until ~9x above the measured write
ceiling. Benign or theoretical for a small fleet (at most a few hundred edges at up to 1.6k w/s stays
under ~1 Gbps aggregate and ~11% edge-core). A real problem at the stated target scale (ADR-0011 targets
10k-1M edges at 10k w/s): on the built topology a single fan-out node saturates its NIC at ~1-2k edges,
and the target needs the tree that does not exist. ADR-0038 section 3 itself declares "sustained
burst-rate writes to large edge fleets is outside this design's envelope."

The crucial implication for the fix: server-side prefix filtering cuts the binding egress ceiling by the
factor `f` (E x W becomes E x f x W). For narrow subscriptions (f = 1%) that is a ~100x egress reduction
-- it attacks the exact binding ceiling. It does not help the pathological all-edges-want-everything case
(f = 1), which only the distribution tree addresses.

---

## 3. Can the signed chain support verifiable filtering?

### 3.1 The load-bearing structural fact: the signature omits the position

`ConfigStateMachine.signCommand` signs `CommandCodec.encodeBatch(mutations) || BE(epoch,8) || nonce`
(`ConfigDelta.signingPayload():127-137`). The signature does not cover `fromVersion`, `toVersion`, or
`seq` -- those are separate unsigned wire fields
(`EdgeFrameCodec.encodeNotificationInto:328-331`). The "chain" is therefore not a hash chain and has no
Merkle/accumulator: it is a set of independently Ed25519-signed mutation records, ordered by an unsigned
version link, plus a signed monotone `epoch`. The edge does not even check `toVersion == fromVersion + 1`,
and the replay check is monotone (`epoch > highestSeen`), not dense.

Consequence: today's anti-suppression leans on TLS, not the signature. ADR-0038's claim "a relay cannot
drop a single delta without the edge seeing a chain break" holds only because `fromVersion` is unsigned
and protected by TLS. A key-less relay that terminates TLS and re-originates the stream can drop D_k,
forward D_{k+1} with `fromVersion` rewritten to `k-1`: the signature still verifies (covers only
mutations+epoch+nonce), the monotone-epoch check passes, `fromVersion == currentVersion` passes -- D_k is
silently suppressed with no gap. Enforcing `toVersion==fromVersion+1` does not fix it (the relay rewrites
every subsequent link consistently). In the built co-located mTLS system this needs breaking TLS, so it
is not a live vulnerability today -- but it means the property the per-delta signature was sold as
providing standalone is actually TLS-dependent, and it is why "just add a gap-marker" is harder than it
looks. Any real skip-proof must sign the position.

### 3.2 The four mechanisms

**(a) Signed gap-markers / skip-proofs -- theater unless produced at the leader.** If the fan-out tier
signs them, it holds no key (ADR-0038) so it cannot; and if it did, the same party doing the filtering
would be certifying "I filtered nothing," which is theater. If the leader signs them, the leader signs at
apply time and by design does not know each edge's prefix set, so per-subscription skip-proofs cannot be
precomputed -- to sign "P has no other key in this commit" the leader must authenticate the commit's key
set canonically, which is mechanism (b).

**(b) Per-commit leader-signed sorted Merkle / accumulator -- the only sound general answer, and it is a
redesign.** Leader builds a key-ordered authenticated commitment at apply time and includes the root in
the signed payload (reuses the cluster key); edge gets the filtered subset plus inclusion proofs plus
adjacent-leaf range/absence proofs, giving completeness without the full chain. Write-path cost is the
hard part: the store is a HAMT (unsorted); range proofs need a persistent authenticated dictionary
(Merkle-ized sorted trie/B-tree keyed by path) plus proof-gen on the commit hot path, plus a wire version
bump. Not a near-term fix; a chain redesign (this is ADR-0038's named "signed per-range Merkle summaries"
upgrade path). Size: multi-month, thousands of lines of code. Buys real suppression-evidence against a
key-less relay and fixes the section 3.1 position gap in one move.

**(c) Per-prefix / per-shard sub-chains -- blocked by hash routing; salvageable only via declared
topics.** Per-group sub-chains already exist at N>1 (each group has its own signed chain; the cursor is
already a `(gid,S)` vector), but routing is hash-of-full-path (invariant INV-PATH), so a prefix scatters
across all N groups -- "subscribe to groups covering my prefix" means subscribe to all groups. Per-group
is not per-prefix. A second-level dense per-prefix seq would give completeness for free by arithmetic (no
Merkle) -- but a dense per-prefix seq must be assigned at commit time, so the leader must know the
partition at write time. That is feasible only by restricting subscriptions to a declared, disjoint topic
partition (Kafka-subject style), which is a product-model change, not a transport tweak, and only sound
if the per-topic seq is signed (section 3.1).

**(d) Dense per-subscription seq -- redundant with TLS, useless against the filterer.** A counter
assigned by the filtering tier proves only that the transport between filterer and edge dropped nothing,
which mTLS already guarantees. It says nothing about server-side omission.

### 3.3 Feasibility verdict

| Mechanism | Over existing chain? | Defeats key-less relay? | Size | Buys |
|---|---|---|---|---|
| (d) dense per-sub seq | trivial | no (redundant with TLS) | days | nothing new |
| (a) fan-out-signed gap-marker | yes | no (theater) | 1-2 person-weeks | nothing |
| (a) leader-signed gap-marker | no, collapses to (b) | yes | = (b) | = (b) |
| (c) per-group sub-chains | already exist | n/a (prefix scatters all groups) | 0 | doesn't reduce prefix volume |
| (c) per-declared-topic dense signed chains | product-model change | yes, iff topic seq signed | weeks to months | per-topic skip-evidence, no Merkle |
| (b) leader-signed sorted Merkle | redesign (auth-dictionary plus wire bump) | yes | multi-month, thousands of LOC | true completeness proof; ADR-0038's named path |

There is no cheap sound completeness proof over the existing chain. The cheapest thing that actually
delivers suppression-evidence is (c) declared-topic dense signed sub-chains, and its cost is a product
decision (give up arbitrary runtime prefixes), not crypto. Cross-cutting must-fix for any of them: the
signed payload must start covering the position.

---

## 4. Prior art

The industry splits cleanly into two regimes that map onto the trusted-cheap / untrusted-verifiable fork.

**Regime A -- trusted server, dense sequence, no crypto (the cheap path).** Every production change-feed
that filters server-side in an operator-run domain uses a dense monotonic sequence/revision for gap
detection and no cryptographic completeness:
- **etcd Watch** -- server-side key-range filter over the revision-ordered MVCC log; client fully trusts
  the server; gap/coverage via dense `revision` plus `compact_revision`/`ErrCompacted` forcing re-sync;
  `progress_notify` emits idle empty responses so a subscriber distinguishes "caught up" from "stalled."
  (https://etcd.io/docs/v3.5/learning/api/) This is the closest fit for the cheap path.
- **Kafka** -- per-partition dense offsets; gap detection is offset arithmetic; brokers trusted, no
  per-record crypto. Key shape lesson: routing happens at publish time (partition key) and content
  filtering is consumer-side. Kafka sidesteps the trust-the-filter question by pre-sharding at write time
  -- which is what Configd's N-group sharding already does; sub-shard prefix filtering is the only place
  the (a)/(b) fork actually bites.
- **Consul** blocking queries plus `X-Consul-Index`; **ZooKeeper** notify+refetch (no stream);
  **Kinesis/DynamoDB Streams** per-shard sequence -- all trusted, no crypto completeness.

**Regime B -- untrusted server, crypto completeness (the real path), and why it fits poorly:**
- **CT / RFC 6962** -- a Merkle log proves whole-log append-only plus consistency but gives no per-subset
  completeness proof; a monitor wanting "all certs for domain X" must scan the entire log or trust a
  third party. That is literally what Configd does today, and why ADR-0038 chose it.
- **CONIKS / Google Key Transparency / Trillian map** -- do give per-key completeness via a sparse Merkle
  prefix tree, but are point-lookup-shaped, not streaming, and a whole expensive subsystem (Trillian is in
  maintenance mode; map mode de-emphasized in favor of logs-only Tessera, a "don't build a verifiable map
  unless you must" signal).
- **Bitcoin light clients -- the on-point precedent.** BIP-37 (client sends a filter, server returns
  matching txns plus Merkle proofs) was deprecated, and BIP-157 names why: privacy, DoS, and, critically,
  "malicious full nodes can omit critical data with little risk of detection." Merkle inclusion proofs
  prove what is sent but nothing against omission -- the exact hole in a naive "server filters and proves
  what it sends" design. The replacement, BIP-158, has the server compute a deterministic committed
  compact filter (every node produces byte-identical output) and the client filters client-side and
  cross-checks peers. The industry moved away from trusted server-side filtering toward "server sends a
  commitment, client decides," which is essentially Configd's current full-chain-to-edge design.

The standard answer: in operator-run trusted domains, everyone does plain server-side filtering with
dense sequence/revision numbers for gap detection and no crypto completeness. Crypto completeness appears
only when the server is genuinely untrusted, and then it is expensive and usually not streaming-shaped.
For an mTLS operator-run edge fan-out, the etcd model (server-side filter plus dense cursor plus
compaction/progress-notify) is the right-sized, mainstream choice. A bespoke streaming per-subset
completeness proof is something nobody ships; even the untrusted-server precedent (BIP-158) is "commit
plus client-filters," not per-query proofs.

---

## 5. The recommended fix path

### 5.1 Track 1 -- near-term cheap path (recommended first step): server-side prefix filtering, edge trusts server

Given the actual trust model (section 1) this is sound, and given the scale cost (section 2.5) it
directly relieves the binding ceiling (egress becomes E x f x W) and the 1/f verify waste. It realigns
the SUBSCRIBE plane with the watch plane's already-shipped trust model. Shape (etcd-style, Regime A):

- Wire the subscribed prefix set into the drain (`FanOutSessionCore.drainStreaming` /
  `FanOutConnectionDriver`), which today ignore it, filtering whole signed deltas server-side.
- Replace the edge's `fromVersion==currentVersion` chain check on filtered streams with a dense
  per-subscription cursor plus an authenticated cursor-advance / "range advanced, nothing matched" token
  so a filtered gap surfaces as an advance, not false data-loss. Add an etcd-style
  compaction/`ErrCompacted` re-sync and progress-notify so "caught up" is distinct from "stalled."
- Non-negotiable carve-outs: (1) never coalesce/rewrite -- per-delta Ed25519 authenticity is
  trust-independent (ADR-0038 leg (a) stands); filtering means dropping whole signed deltas only. (2) The
  authenticated cursor-advance token is where the trust is spent -- the edge trusts the server's assertion
  "(A,B] had nothing under your prefixes." (3) `secure/`/`GLOBAL` keys stay on the linearizable root-read
  path (already true) -- filtering the bounded-stale plane cannot affect security decisions.
- Ship it as a posture flag: default server-side-filter on for the co-located trusted deployment; off
  (full-chain) when a separate relay tier is configured. Not a one-way door.

Rough size: weeks, not months. The building blocks exist and are inert: `SubscriptionManager.matchingNodes`
is a ready reverse `prefix->nodes` index (currently dead code, its only live caller is a metric gauge);
the SUBSCRIBE wire frame already carries `prefixes()`; `FilteringReplaySource` already demonstrates a
server-side filtered snapshot on the watch plane. Missing: thread the prefix set into the drain, the
authenticated cursor-advance primitive plus edge-side handling, the posture flag, and tests. Snapshot
hydration filters the same way `FilteringReplaySource` already does.

### 5.2 Track 2 -- long-term path (only if a separate distribution tier is deployed): leader-signed Merkle skip-evidence

If and when an independently-deployable distribution tier (ADR-0011) or edge-to-edge forwarding is
actually deployed, the untrusted-relay adversary becomes real and completeness must be restored
cryptographically. The only sound general mechanism is (b) leader-signed sorted-Merkle per-commit roots
plus range/absence proofs (section 3.2), ADR-0038's own named upgrade path, a multi-month chain redesign
(authenticated-dictionary storage engine plus wire version bump plus proof-gen). Note the prior-art
caution: even here the faithful precedent (BIP-158) is "leader emits a deterministic signed commitment,
edge filters client-side," not a bespoke per-query completeness proof. Do not build a verifiable map
unless the threat model forces it.

### 5.3 Track 0 -- do regardless: sign the position

Independently of the filtering decision, extend the signed payload to cover the version position
(`seq`/`fromVersion`/`toVersion`, or a Merkle root). Today's full-chain anti-suppression leans on TLS
(section 3.1), so the property ADR-0038 advertises is weaker than stated; a key-less relay could rewrite
the unsigned linkage. This is a cheap, standalone hardening (extend `ConfigStateMachine.signCommand`'s
payload plus edge verify) that strengthens the current design and is a prerequisite for any future
skip-proof. It is a wire-format change (needs a version bump), so schedule it with other wire work.

### 5.4 The recommendation, restated

Do Track 1 (posture-flagged server-side filtering, trusted-domain, etcd-shaped) as a near-term fix -- it
matches Configd's actual trust model, it is the mainstream industry answer for an operator-run mTLS edge,
it directly cuts the binding egress ceiling by `f`, and it costs weeks because the routing index, the
prefix wire field, and a filtered-snapshot path already exist. Defer Track 2 (Merkle skip-evidence) to a
later phase, gated on an actually-deployed untrusted relay tier -- building it now would be
over-engineering against an adversary that does not exist today, exactly the mistake BIP-37 to BIP-158
documents the industry unwinding. Schedule Track 0 (sign the position) with the next wire-format change
regardless of the above.

### 5.5 Interaction with the multi-shard-watch gap

Per-group sub-chains already exist (N>1) but do not solve cross-shard ordering (the driver-protocol RFC,
section 2: no cross-shard/global order) -- they carry and formalize it, because there is no cross-shard
sequencer. Server-side prefix filtering neither unblocks nor worsens the multi-shard watch gap -- it is
orthogonal. A watch is the client-facing projection of this same fan-out plane, and its server-side
filter is done by an already-verified trusted filterer (categorically different from the key-less relay
ADR-0038 fears), so the watch plane inherits the same guarantees and the same gap identically. The one
thing that would move the boundary is Track 2's declared-topic model (c): it reshapes the partition axis
from hash-shard to a subscriber-meaningful topic axis for both the SUBSCRIBE and watch planes, a better
mental model, but still no global order and a product-model change. If a topic-partition redesign is ever
on the table, it should be scoped as a joint SUBSCRIBE plus multi-shard-watch fix, not two separate
efforts.

---

## 6. Adjacent finding (not part of this scope, flagged for the operator)

A concurrent EC2 run (`docs/measurement/ec2-drive-to-green-2026-07-02/README.md:40-84`) found a separate
reliability bug on this same fan-out plane: under sustained writes past the buffer capacity (10000),
`FanOutBuffer.readSince` returns conservative self-healing GAPs at the lock-free eviction boundary, and
`FanOutSessionCore` counts each toward the gap-demote quarantine limit -- so a perfectly caught-up edge
(`cursor == lastAckedSeq`) is spuriously demoted and quarantined (~28 min cooldown) on an idle box at 50
w/s. This blocked a planned staleness-distribution measurement. It is orthogonal to the efficiency
question but bears on the same edge-hydration story; the operator owns its disposition decision (fix,
document as a known limitation, or investigate further). Worth resolving alongside any fan-out drain
change, since Track 1 touches the same `FanOutSessionCore` drain path.

---

## 7. Summary of findings

- Trust model determined: trusted-domain, operator-run, mTLS single-hop, relay==signer today; the
  untrusted-relay tier (Plumtree) is dormant; the no-filtering rule is belt-and-suspenders plus
  forward-compat, a configurable posture, not a hard requirement. Evidence in section 1.
- Scale cost quantified: O(E x W) vs. O(E x f x W), waste 1/f; ~680 B/record; egress is the binding
  ceiling (~1-2k edges/node at measured rates), verify-CPU is not (~9x headroom); benign at hundreds, a
  real problem at the ADR-0011 target. Section 2.
- Signature-chain verdict: no cheap sound proof over the existing chain; (b) leader-signed Merkle is a
  redesign (ADR-0038's named path); (c) declared-topic dense signed chains is a product change; the
  signature omits the position, so today's guarantee leans on TLS. Section 3.
- Prior art: etcd/Kafka/Consul (trusted, dense-cursor, no crypto) vs. CT/Key-Transparency/BIP-158
  (untrusted, expensive, not streaming); the etcd model fits the cheap path. Section 4.
- Recommended fix path: Track 1, near-term posture-flagged server-side filtering (weeks); Track 2,
  long-term Merkle skip-evidence (gated on a real untrusted tier); Track 0, sign-the-position (cheap, do
  regardless); multi-shard-watch interaction is orthogonal. Section 5.
- Read-only: nothing else in the repo changed.
