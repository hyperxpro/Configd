# Edge Fan-Out Efficiency Investigation — full-stream-then-filter, the trust model, and verifiable server-side filtering

**Date:** 2026-07-02
**Type:** READ-ONLY investigation (scoping, not building). Nothing in the repo was changed except this file.
**Method:** 5-lane Opus team — security (LEAD), reliability, protocol, prior-art, java-engineer — each grounded in code (file:line), the threat model, the ADRs, and the EC2 measurements. Findings cross-checked against source by the coordinator.

---

## 0. TL;DR

The edge SUBSCRIBE plane streams the **verbatim, leader-signed delta chain to every subscriber** and filters at the receiver (`PrefixStorageFilter`, after Ed25519 verification). That is O(edges × total-writes) in both network egress and edge verify-CPU, versus an ideal O(edges × f × writes) where `f` is the keyspace fraction an edge actually wants. Waste ratio ≈ **1/f**.

Three questions decide whether the fix is cheap or a real feature. The answers:

1. **Trust model (the pivot):** v1 is a **trusted, operator-run, single-hop, mTLS-both-ends** deployment where the fan-out relay and the signer are **the same in-process entity**. The untrusted-relay tier that ADR-0038's no-suppression rule defends against (Plumtree/HyParView) is **constructed but dormant** — `broadcast()` is never called on the data path; edges never forward to peer edges. So the "no server-side filtering" rule is **belt-and-suspenders + forward-compat**, not a hard requirement forced by any adversary in the v1 threat model. It should be a **configurable posture**, not a one-way door.

2. **Scale cost:** **network egress is the binding ceiling; edge verify-CPU is not.** A single edge only saturates one core near ≈14k writes/s — ~9× above the **measured** ~1.6k w/s aggregate write ceiling — so no edge is verify-bound at any rate this cluster can produce. But egress is centralized (no distribution tree is built): aggregate = E × W × ~680 B, and a single fan-out node tops out at **~1–2k edges at the measured write rate**. **Benign at hundreds of edges; a now-problem at the ADR-0011 target of thousands-to-millions.**

3. **Can the signed chain support verifiable filtering?** Not cheaply, and a load-bearing structural fact reframes it: **the signature covers only `mutations || epoch || nonce` — not the version position (`fromVersion`/`toVersion`/`seq`).** So *today's* anti-suppression property actually leans on **TLS**, not the signature. Against a key-less relay, the only sound completeness proof is a leader-signed authenticated dictionary (per-range Merkle) = a **chain redesign** (ADR-0038's own named upgrade path). A cheaper sound sub-chain exists only by changing the **product** model to declared disjoint topics.

**Recommendation:** a **two-track, posture-flagged** fix. The v1.x cheap path — **server-side prefix filtering on the fan-out drain, with the edge trusting the server** (exactly the trust model the *watch* plane already ships) — is sound for the co-located mTLS deployment and directly relieves the binding egress ceiling by the factor `f`. The v2 real path — leader-signed Merkle skip-evidence — is only warranted **if and when** a genuinely separate distribution tier is deployed. Independently of either, close the **latent signature-position gap** (make the signed payload cover seq/from/to); it is cheap, hardens the *current* full-chain claim, and is a prerequisite for any future skip-proof.

---

## 1. The trust model — the pivot (security LEAD)

### 1.1 What v1 actually is: trusted, operator-run, mTLS single-hop, relay == signer

- The edge fan-out surface (`--edge-port`) is **mTLS-required both ends**: `FanOutServer.java:339` / `NettyFanOutServer.java:249` both `setNeedClientAuth(true)`. A dial without a trusted client cert fails the handshake (`operator-runsheet.md:93-94`).
- The edge cert-DN must hold `READ` over the root prefix `""` (auth-on) to hydrate the whole store (`operator-runsheet.md:123-125`, `known-limitations.md:84-91`). Edges are **operator-provisioned, authenticated, authorized** — not anonymous clients.
- The wired data path is **single-hop and in-process**: Raft leader `apply` → `ConfigStateMachine.signCommand` (Ed25519) → per-shard `FanOutBuffer.append` (already-signed delta) → `NettyFanOutServer` streams the signed chain over mTLS → edge `DeltaApplier` verifies. The fan-out server **streams pre-signed bytes; it never re-signs and never holds the signing key** (ADR-0038 requirement, satisfied by construction).
- **The Plumtree/HyParView multi-tier relay is dormant scaffolding.** `ConfigdServer` constructs `PlumtreeNode`/`HyParViewOverlay` (`:641-642`) and calls `plumtreeNode.tick()` (`:1051`), but **`broadcast()` is never invoked on the commit/notify path** (ADR-0030 notes it is benchmark-only). Edges do **not** forward to peer edges. ADR-0003/0011 (edge-to-edge gossip, 10k–1M edges) is design aspiration; ADR-0030 (Accepted) is what shipped: one centralized Raft root + async full fan-out.

### 1.2 The threat model has no untrusted-relay adversary

`docs/archive/security/threat-model.md:62-69` enumerates A1 network-MITM (no keys), A2 storage-writer, A3 malicious Raft *peer* (install-snapshot), A4 API client, A5 hostile wire input, A6 supply-chain. **A "malicious edge relay that forwards to / suppresses for other edges" is not in the set.** The adversary the no-suppression rule most strongly answers is simply not enumerated for v1.

### 1.3 ADR-0038's rule has two legs — only one depends on trust

- **Leg (a) — no coalescing:** collapsing signed deltas produces bytes the leader never signed → breaks per-delta Ed25519 verification. **HARD, trust-independent authenticity requirement. Must never be relaxed.**
- **Leg (b) — no prefix filtering (suppression-detectability):** a relay-asserted skip-marker isn't leader-signed, so a compromised/buggy relay "could silently suppress arbitrary keys." **This leg is the only one that depends on the relay being untrusted.** In v1 the filtering entity *is* the signer's own process — a compromise there already owns the key and can forge anything — so leg (b) protects against **nothing a compromised signer couldn't already do**. Its real v1 value is detecting a *buggy* relay / transport corruption (defense-in-depth) and preserving the option to run a genuinely-separate untrusted tier later.
- Leg (b)'s worst case is moot in v1 anyway: `secure/`/`GLOBAL` keys are read **linearizably from the root** (ReadIndex), never from bounded-stale edge copies (ADR-0030 A1/INV-1). Suppression at the fan-out tier cannot yield a security-decision bypass; its residual is ordinary staleness, which the staleness state machine + commit-timestamp clock surface.

### 1.4 The clinching evidence: the watch plane already filters server-side under exactly this trust model

The client-facing **watch** plane already does server-side, post-authorization filtering: `WatchMultiplexSink` filters the live tail per-NOTIFY, and `FilteringReplaySource` narrows the catch-up snapshot to each watch's authorized target. It **drops the signed chain** (WATCH_EVENT carries raw key/value pairs, no signature/epoch/version) and **trusts the server** (mTLS + server-side authz). **Server-side-filter + client-trusts-server is already the shipped model for one of the two planes.** The full-chain SUBSCRIBE plane is the outlier — kept not because of a v1 adversary but because the edge cache's gap-detection mechanism relies on chain contiguity.

### 1.5 What relaxing the rule breaks vs. weakens vs. leaves untouched

| Concern | Effect of server-side filtering | Note |
|---|---|---|
| Gap detection (`DeltaApplier`, `fromVersion==currentVersion`) | **BREAKS** | A dropped delta jumps the next `fromVersion` → edge reads it as data loss → re-snapshot storm. Needs an authenticated cursor-advance / skip token. |
| Global version cursor / frontier (`VersionCursor`, ADR-0039) | **WEAKENS** | Server must assert the cursor value (relay-asserted, not chain-derived). Sound iff server trusted. |
| Suppression-detectability / chain completeness | **WEAKENS (by design)** | Downgrades from cryptographic to operational (trust server; staleness SM + metrics catch wholesale stalls). Acceptable only in the operator trust domain. |
| Per-record authenticity (Ed25519) + anti-replay (epoch+nonce) | **PRESERVED** | iff the fix drops whole signed deltas and never coalesces/rewrites. |
| `PoisonPillDetector`, `BloomFilter` negative cache | **UNAFFECTED** | Edge-local, per-subscription; simply see fewer keys. |
| Edge SUBSCRIBE hydration | **UNAFFECTED in v1** | Hydration is edge-FROM-server, not edge-from-edge (no Plumtree). Would matter only if edge-to-edge hydration existed — not v1. |
| Snapshot replay | **PRESERVED** | A filtered-snapshot path already exists (`FilteringReplaySource`). |
| Watch plane, audit trail | **UNAFFECTED** | Watch already filtered; audit lives on the control plane, not the edge. |

### 1.6 Verdict

**Configurable-both, defaulting to trusted-domain for v1.** v1 is unambiguously a trusted, in-process, mTLS single-hop fan-out. The cheap fix is sound and merely realigns the SUBSCRIBE plane with the watch plane's existing trust model. **Non-negotiable carve-outs:** (1) never coalesce/rewrite — leg (a) stands; (2) provide an authenticated cursor-advance/skip token; (3) `secure/`/`GLOBAL` stay on the linearizable root-read path (already true). **Guardrail:** the moment a separate distribution tier or edge-to-edge forwarding is deployed, the untrusted-relay adversary becomes real and the no-suppression guarantee (or leader-signed skip-evidence) must be restored — hence a **posture flag**, not a one-way door.

---

## 2. The real scale cost (reliability)

### 2.1 The math and the record size

Cost is **O(E × W)** network + **O(E × W)** Ed25519 verifies vs the ideal **O(E × f × W)**; waste ratio ≈ **1/f** (an edge wanting 1% of the keyspace moves and verifies ~100× what it needs).

Record size, computed exactly from the wire codec (`EdgeFrameCodec.encodeNotificationInto:320-343`): fixed header 52 B + batch (≈556 B for a 32 B key + 512 B value) + Ed25519 signature 64 B + nonce 8 B ≈ **680 B / write / edge** at the measured 512 B value size; ≈**1.2 KB** at a 1 KB value. Overhead floor is ~168 B/record regardless of value (signature + framing dominate small values). Cross-check: ADR-0038 §3 uses "~1 KB typical → 80 Mbit/s per subscriber at 10k w/s," consistent with W × recordSize.

### 2.2 What is measured vs computed

- **Measured on metal (write/consensus plane only):** closed-loop clean knee N=1 **656**, N=2 1075, N=3 **1607** w/s (2.45× on 3 machines); single-box plateau ~1100; single-group knee ~800 (`ec2-horizontal-2026-07-01/02-scaling-curve.md`). **These runs used no `--edge-port` — the fan-out plane was never exercised;** the 18.5 MB/s per-box tx there is Raft replication, not fan-out. Cluster-bound by per-group heartbeat-starvation churn at ~62% CPU / **<1% NIC**.
- **Measured (JMH micro):** NOTIFY frame encode `prodEncodeIntoByteBufPooled` **7.3 µs/frame**; ADR-0043 shows io_uring *regresses* ~2× at 1024 subscriber streams (Epoll is the default) — the only high-fan-out data point.
- **Not measured anywhere in the repo:** end-to-end fan-out throughput vs edge count (`FanOutLoadClientMain` takes a `subscribers` arg but no recorded results exist); **Ed25519 verify cost (no benchmark at all)**; combined fan-out+verify; per-edge egress on metal.

### 2.3 Ed25519 verify cost — external estimate (labelled; not measured in repo)

Code uses the **JDK built-in SunEC `Signature.getInstance("Ed25519")`** (pure-Java, re-instantiated per delta). Published JDK figures: ~40–150 µs/core, central **~70 µs ≈ ~14k verifies/s/core**. (Native libsodium/OpenSSL would be ~10–30 µs, but the code does not use them.)

### 2.4 Where it bites

**(a) Edge verify-CPU — NOT the near-term ceiling.** Each edge verifies the whole chain (W verifies/s, independent of E). At 70 µs/verify: 800 w/s → 5.6% of one core; 1600 → 11%; 5000 → 35%. A single edge saturates one core only near **W ≈ 14k w/s (~9× the measured write ceiling)**. The waste is real (at f=1%, ~99% of that CPU verifies discarded deltas) but small in absolute terms and distributed one-W-per-edge, so no single machine feels it.

**(b) Server/fleet egress NIC — the BINDING ceiling.** There is **no distribution tree built** (ADR-0011 Plumtree Layer 2 is not implemented); edges hang off a fan-out server as 1:1 sessions, so egress is centralized. Aggregate = E × W × 680 B:

| E \ W | 800 w/s | 1600 w/s | 5000 w/s |
|--:|--:|--:|--:|
| 10 | 0.044 Gbps | 0.087 | 0.27 |
| 100 | 0.44 | 0.87 | 2.72 |
| 1000 | 4.35 | 8.7 ⚠️ | 27.2 ❌ |
| 5000 | 21.8 ❌ | 43.5 ❌ | 136 ❌ |

(❌ infeasible on one box; ⚠️ at the single-NIC ceiling.) Per-fan-out-node edge ceiling at 10 Gbps sustained: W=800 → ~2300 edges; W=1600 → ~1150; W=5000 → ~370. **A single fan-out node tops out at ~1–2k edges at the measured write rate** — far short of ADR-0011's "10k edges/node" (which assumed the un-built tree). Adding fan-out servers does not reduce total egress; each re-ingests + re-emits the full chain.

**(c) Server fan-out CPU — secondary, batching-sensitive.** Encoding is per-edge (not serialize-once). At 64 notifs/frame, E=1000/W=1600 ≈ 18% of a core; at the worst-case 1 notif/frame (caught-up real-time edges) the same point ≈ 6 cores. NIC-bound first, but degrades sharply if batching is thin.

### 2.5 Verdict

**Network egress dominates the waste; edge verify-CPU does not bite until ~9× above the measured write ceiling.** Benign/theoretical for a *small* fleet (≤ a few hundred edges at ≤1.6k w/s stays under ~1 Gbps aggregate and ~11% edge-core). A **now-problem at the stated target scale** (ADR-0011 targets 10k–1M edges at 10k w/s): on the built topology a single fan-out node saturates its NIC at ~1–2k edges, and the target needs the tree that does not exist. ADR-0038 §3 itself declares "sustained burst-rate writes to large edge fleets is outside this design's envelope."

**The crucial implication for the fix:** server-side prefix filtering cuts the binding egress ceiling by the factor `f` (E × W → E × f × W). For narrow subscriptions (f = 1%) that is a ~100× egress reduction — it attacks the exact binding ceiling. It does not help the pathological all-edges-want-everything case (f = 1), which only the distribution tree addresses.

---

## 3. Can the signed chain support verifiable filtering? (protocol)

### 3.1 The load-bearing structural fact: the signature omits the position

`ConfigStateMachine.signCommand` signs `CommandCodec.encodeBatch(mutations) || BE(epoch,8) || nonce` (`ConfigDelta.signingPayload():127-137`). **The signature does NOT cover `fromVersion`, `toVersion`, or `seq`** — those are separate *unsigned* wire fields (`EdgeFrameCodec.encodeNotificationInto:328-331`). The "chain" is therefore not a hash chain and has no Merkle/accumulator: it is a set of independently Ed25519-signed mutation records, ordered by an **unsigned** version link, plus a **signed monotone `epoch`**. The edge does not even check `toVersion == fromVersion + 1`, and the replay check is monotone (`epoch > highestSeen`), not dense.

**Consequence — today's anti-suppression leans on TLS, not the signature.** ADR-0038's claim "a relay cannot drop a single delta without the edge seeing a chain break" holds only because `fromVersion` is unsigned *and* protected by TLS. A key-less relay that terminates TLS and re-originates the stream can **drop D_k, forward D_{k+1} with `fromVersion` rewritten to `k-1`**: the signature still verifies (covers only mutations+epoch+nonce), the monotone-epoch check passes, `fromVersion == currentVersion` passes — **D_k is silently suppressed with no gap**. Enforcing `toVersion==fromVersion+1` does not fix it (the relay rewrites every subsequent link consistently). In the built co-located mTLS system this needs breaking TLS, so it is **not a live vuln today** — but it means the property the per-delta signature was sold as providing *standalone* is actually TLS-dependent, and it is why "just add a gap-marker" is harder than it looks. **Any real skip-proof must sign the position.**

### 3.2 The four mechanisms

**(a) Signed gap-markers / skip-proofs — theater unless produced at the leader.** If the fan-out tier signs them, it holds no key (ADR-0038) so it cannot; and if it did, the same party doing the filtering would be certifying "I filtered nothing" — theater. If the *leader* signs them, the leader signs at apply time and by design does not know each edge's prefix set, so per-subscription skip-proofs cannot be precomputed — to sign "P has no other key in this commit" the leader must authenticate the commit's key set canonically, which **is mechanism (b)**.

**(b) Per-commit leader-signed sorted Merkle / accumulator — the only sound general answer = redesign.** Leader builds a key-ordered authenticated commitment at apply time and includes the root in the signed payload (reuses the cluster key); edge gets the filtered subset + inclusion proofs + adjacent-leaf range/absence proofs → completeness without the full chain. Write-path cost is the hard part: the store is a HAMT (unsorted); range proofs need a **persistent authenticated dictionary** (Merkle-ized sorted trie/B-tree keyed by path) plus proof-gen on the commit hot path, plus a wire version bump. **v1.x = no; chain redesign = yes** (this is ADR-0038's named "signed per-range Merkle summaries" upgrade path). Size: **multi-month, ~1000s LOC.** Buys real suppression-evidence against a key-less relay *and* fixes the §3.1 position gap in one move.

**(c) Per-prefix / per-shard sub-chains — blocked by hash routing; salvageable only via declared topics.** Per-*group* sub-chains already exist at N>1 (each group has its own signed chain; the cursor is already a `(gid,S)` vector), **but routing is hash-of-full-path (INV-PATH), so a prefix scatters across all N groups** — "subscribe to groups covering my prefix" = subscribe to all groups. Per-group ≠ per-prefix. A **second-level dense per-prefix seq** would give completeness for free by arithmetic (no Merkle) — but a dense per-prefix seq must be assigned at commit time, so the leader must know the partition at *write* time. That is feasible **only** by restricting subscriptions to a **declared, disjoint topic partition** (Kafka-subject style) — a **product-model change**, not a transport tweak — and only sound if the per-topic seq is signed (§3.1).

**(d) Dense per-subscription seq — redundant with TLS, useless vs. the filterer.** A counter assigned by the filtering tier proves only that the transport between filterer and edge dropped nothing, which mTLS already guarantees. Says nothing about server-side omission.

### 3.3 Feasibility verdict

| Mechanism | Over existing chain? | Defeats key-less relay? | Size | Buys |
|---|---|---|---|---|
| (d) dense per-sub seq | trivial | **no** (redundant w/ TLS) | days | nothing new |
| (a) fan-out-signed gap-marker | yes | **no** (theater) | 1–2 pw | nothing |
| (a) leader-signed gap-marker | no — collapses to (b) | yes | = (b) | = (b) |
| (c) per-group sub-chains | already exist | n/a (prefix scatters all groups) | 0 | doesn't reduce prefix volume |
| (c) per-declared-topic dense signed chains | product-model change | **yes, iff topic seq signed** | weeks–months | per-topic skip-evidence, no Merkle |
| (b) leader-signed sorted Merkle | **redesign** (auth-dictionary + wire bump) | **yes** | multi-month, ~1000s LOC | true completeness proof; ADR-0038's named path |

**There is no cheap sound completeness proof over the existing chain.** The cheapest thing that actually delivers suppression-evidence is (c) declared-topic dense signed sub-chains — and its cost is a *product* decision (give up arbitrary runtime prefixes), not crypto. **Cross-cutting must-fix for any of them: the signed payload must start covering the position.**

---

## 4. Prior art (reference-researcher)

The industry splits cleanly into two regimes that map onto the trusted-cheap / untrusted-verifiable fork.

**Regime A — trusted server, dense sequence, no crypto (== the cheap path).** Every production change-feed that filters server-side in an operator-run domain uses a dense monotonic sequence/revision for gap detection and **no** cryptographic completeness:
- **etcd Watch** — server-side key-*range* filter over the revision-ordered MVCC log; client fully trusts the server; gap/coverage via dense `revision` + `compact_revision`/`ErrCompacted` forcing re-sync; `progress_notify` emits idle empty responses so a subscriber distinguishes "caught up" from "stalled." (https://etcd.io/docs/v3.5/learning/api/) **This is the closest fit for the cheap path.**
- **Kafka** — per-partition dense offsets; gap detection = offset arithmetic; brokers trusted, no per-record crypto. Key shape lesson: routing is at **publish** time (partition key) and content filtering is **consumer-side**. Kafka sidesteps the trust-the-filter question by pre-sharding at write time — which is what Configd's N-group sharding already does; sub-shard prefix filtering is the only place the (a)/(b) fork actually bites.
- **Consul** blocking queries + `X-Consul-Index`; **ZooKeeper** notify+refetch (no stream); **Kinesis/DynamoDB Streams** per-shard sequence — all trusted, no crypto completeness.

**Regime B — untrusted server, crypto completeness (== the real path), and why it fits poorly:**
- **CT / RFC 6962** — a Merkle log proves whole-log append-only + consistency but gives **no per-subset completeness proof**; a monitor wanting "all certs for domain X" must scan the entire log or trust a third party. That is literally what Configd does today, and why ADR-0038 chose it.
- **CONIKS / Google Key Transparency / Trillian map** — do give per-*key* completeness via a sparse Merkle prefix tree, but are **point-lookup-shaped, not streaming**, and a whole expensive subsystem (Trillian is in maintenance mode; map mode de-emphasized in favor of logs-only Tessera — a "don't build a verifiable map unless you must" signal).
- **Bitcoin light clients — the on-point precedent.** BIP-37 (client sends a filter, server returns matching txns + Merkle proofs) was **deprecated**, and BIP-157 names why: privacy, DoS, and — critically — "malicious full nodes can **omit** critical data with little risk of detection." Merkle inclusion proofs prove what *is* sent but nothing against omission — the exact hole in a naive "server filters + proves what it sends" design. The replacement, **BIP-158**, has the server compute a **deterministic committed compact filter** (every node produces byte-identical output) and the **client filter client-side** and cross-check peers. The industry moved *away* from trusted server-side filtering toward "server sends a commitment, client decides" — which is essentially Configd's *current* full-chain-to-edge design.

**The standard answer:** in operator-run trusted domains, everyone does plain server-side filtering with dense sequence/revision numbers for gap detection and no crypto completeness. Crypto completeness appears only when the server is genuinely untrusted, and then it is expensive and usually not streaming-shaped. **For an mTLS operator-run edge fan-out, the etcd model (server-side filter + dense cursor + compaction/progress-notify) is the right-sized, mainstream choice.** A bespoke streaming per-subset completeness proof is something nobody ships; even the untrusted-server precedent (BIP-158) is "commit + client-filters," not per-query proofs.

---

## 5. The recommended fix path

### 5.1 Track 1 — v1.x cheap path (RECOMMENDED first step): server-side prefix filtering, edge trusts server

Given the actual trust model (§1) this is sound, and given the scale cost (§2.5) it directly relieves the **binding** ceiling (egress → E × f × W) and the 1/f verify waste. It realigns the SUBSCRIBE plane with the watch plane's already-shipped trust model. Shape (etcd-style, Regime A):

- Wire the subscribed prefix set into the drain (`FanOutSessionCore.drainStreaming` / `FanOutConnectionDriver`), which today ignore it, filtering whole signed deltas server-side.
- Replace the edge's `fromVersion==currentVersion` chain check on filtered streams with a **dense per-subscription cursor** + an **authenticated cursor-advance / "range advanced, nothing matched" token** so a filtered gap surfaces as an advance, not false data-loss. Add an etcd-style compaction/`ErrCompacted` re-sync and progress-notify so "caught up" ≠ "stalled."
- **Non-negotiable carve-outs:** (1) never coalesce/rewrite — per-delta Ed25519 authenticity is trust-independent (ADR-0038 leg (a) stands); filtering = dropping whole signed deltas only. (2) The authenticated cursor-advance token is where the trust is spent — the edge trusts the server's assertion "(A,B] had nothing under your prefixes." (3) `secure/`/`GLOBAL` keys stay on the linearizable root-read path (already true) — filtering the bounded-stale plane cannot affect security decisions.
- Ship it as a **posture flag**: default server-side-filter **on** for the co-located trusted deployment; **off / full-chain** when a separate relay tier is configured. Not a one-way door.

**Rough size — weeks, not months (a v1.x fix).** The building blocks exist and are inert: `SubscriptionManager.matchingNodes` is a ready reverse `prefix→nodes` index (currently dead code — its only live caller is a metric gauge); the SUBSCRIBE wire frame already carries `prefixes()`; `FilteringReplaySource` already demonstrates a server-side filtered snapshot on the watch plane. Missing: thread the prefix set into the drain, the authenticated cursor-advance primitive + edge-side handling, the posture flag, and tests. Snapshot hydration filters the same way `FilteringReplaySource` already does.

### 5.2 Track 2 — v2 real path (only if a separate distribution tier is deployed): leader-signed Merkle skip-evidence

If and when an independently-deployable distribution tier (ADR-0011) or edge-to-edge forwarding is actually deployed, the untrusted-relay adversary becomes real and completeness must be restored cryptographically. The only sound general mechanism is **(b) leader-signed sorted-Merkle per-commit roots + range/absence proofs** (§3.2) — ADR-0038's own named upgrade path — a **multi-month chain redesign** (authenticated-dictionary storage engine + wire version bump + proof-gen). Note the prior-art caution: even here the faithful precedent (BIP-158) is "leader emits a deterministic signed commitment, edge filters client-side," not a bespoke per-query completeness proof. Do not build a verifiable map unless the threat model forces it.

### 5.3 Track 0 — do regardless: sign the position

Independently of the filtering decision, **extend the signed payload to cover the version position** (`seq`/`fromVersion`/`toVersion`, or a Merkle root). Today's full-chain anti-suppression leans on TLS (§3.1), so the property ADR-0038 advertises is weaker than stated; a key-less relay could rewrite the unsigned linkage. This is a **cheap, standalone hardening** (extend `ConfigStateMachine.signCommand`'s payload + edge verify) that strengthens the *current* design and is a prerequisite for any future skip-proof. It is a wire-format change (needs a version bump), so schedule it with other wire work.

### 5.4 The honest call

**Do Track 1 (posture-flagged server-side filtering, trusted-domain, etcd-shaped) as a v1.x fix** — it matches Configd's actual trust model, it is the mainstream industry answer for an operator-run mTLS edge, it directly cuts the binding egress ceiling by `f`, and it costs weeks because the routing index, the prefix wire field, and a filtered-snapshot path already exist. **Defer Track 2 (Merkle skip-evidence) to v2, gated on an actually-deployed untrusted relay tier** — building it now would be over-engineering against an adversary that does not exist in v1, exactly the mistake BIP-37 → BIP-158 documents the industry unwinding. **Schedule Track 0 (sign the position) with the next wire-format change** regardless of the above.

### 5.5 Interaction with the multi-shard-watch gap

Per-group sub-chains already exist (N>1) but **do not solve** cross-shard ordering (RFC §2: no cross-shard/global order) — they carry/formalize it, because there is no cross-shard sequencer. **Server-side prefix filtering neither unblocks nor worsens the multi-shard watch gap — it is orthogonal.** A watch is the client-facing projection of this same fan-out plane, and its server-side filter is done by an already-verified trusted filterer (categorically different from the key-less relay ADR-0038 fears), so the watch plane inherits the same guarantees and the same gap identically. The one thing that *would* move the boundary is Track 2's declared-topic model (c): it reshapes the partition axis from hash-shard to a subscriber-meaningful topic axis for **both** the SUBSCRIBE and watch planes — a better mental model, but still no global order and a product-model change. If a topic-partition redesign is ever on the table, it should be scoped as a joint SUBSCRIBE + multi-shard-watch fix, not two separate efforts.

---

## 6. Adjacent live finding (not part of this scope, flagged for the operator)

The in-flight 2026-07-02 EC2 run (`docs/measurement/ec2-drive-to-green-2026-07-02/README.md:40-84`) found a **separate reliability bug on this same fan-out plane**: under sustained writes past the buffer capacity (10000), `FanOutBuffer.readSince` returns conservative self-healing GAPs at the lock-free eviction boundary, and `FanOutSessionCore` counts each toward the gap-demote quarantine limit — so a **perfectly caught-up edge (`cursor == lastAckedSeq`) is spuriously demoted and quarantined** (~28 min cooldown) on an idle box at 50 w/s. This blocked the INV-S2 staleness distribution measurement. It is orthogonal to the efficiency question but bears on the same edge-hydration story; the operator already owns its disposition decision (fix before v1 / document as known limitation / investigate further). Worth resolving alongside any fan-out drain change, since Track 1 touches the same `FanOutSessionCore` drain path.

---

## 7. Definition-of-done checklist

- [x] **Trust model determined** — trusted-domain, operator-run, mTLS single-hop, relay==signer in v1; the untrusted-relay tier (Plumtree) is dormant; the no-filtering rule is belt-and-suspenders + forward-compat, a **configurable posture**, not a hard v1 requirement. Evidence §1.
- [x] **Scale cost quantified** — O(E×W) vs O(E×f×W), waste 1/f; ~680 B/record; **egress is the binding ceiling (~1–2k edges/node at measured rates), verify-CPU is not (~9× headroom)**; benign at hundreds, now-problem at the ADR-0011 target. §2.
- [x] **Signature-chain verdict** — no cheap sound proof over the existing chain; (b) leader-signed Merkle = redesign (ADR-0038's named path); (c) declared-topic dense signed chains = product change; **the signature omits the position, so today's guarantee leans on TLS**. §3.
- [x] **Prior art** — etcd/Kafka/Consul (trusted, dense-cursor, no crypto) vs CT/Key-Transparency/BIP-158 (untrusted, expensive, not streaming); etcd model fits the cheap path. §4.
- [x] **Recommended fix path** — Track 1 v1.x posture-flagged server-side filtering (weeks); Track 2 v2 Merkle skip-evidence (gated on a real untrusted tier); Track 0 sign-the-position (cheap, do regardless); multi-shard-watch interaction (orthogonal). §5.
- [x] **Findings doc written; read-only; nothing else changed.**
