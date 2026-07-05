# Peer-Quorum `AnchorWitness` — R-a′ Closure Design Addendum

**Status: AS-BUILT / SHIPPED — 2026-07-04 (Gate 3c). Closes ratification item 11 (R-a′).**
Addendum to `docs/design/frozen-format-v1-2026-07-03.md`. This design shipped in Gate 3c as the
peer-quorum `AnchorWitness` (`PeerQuorumAnchorWitness` + the `RaftNode` witness state, wire types
`RAFT_WITNESS`/`RAFT_WITNESS_REPLY`, and `ConfigdServer` arming when real peers exist), so the
witness protocol is part of the frozen wire from the start. **As-built deviations from this design,
after two operator rulings on 2026-07-04:** strict is SPLIT into an always-on **boot** gate
(unconditional peer-majority — closes R-a′ at N=3) and an OPT-IN **vote** deferral
(`-Dconfigd.raft.witnessStrict=true`, default fast-vote, because full-strict-default broke 3-node
failover in the CI smoke); the witness is **armed only when `tcpTransport != null`** (a configured
multi-node cluster), inert otherwise; a latched node advertises its FROZEN `bootAnchorSeq` to avoid a
false-refuse on a rolling restart. The N≥5 fast-vote grant→witnessed residual is documented in the
top-of-doc AS-BUILT block of the main frozen-format doc. This document is design prose; the shipped
code is authoritative where they differ.

This addendum realizes the frozen `AnchorWitness` SPI (frozen-format §A1.7,
`frozen-format-v1-2026-07-03.md:881-886`) as a peer-quorum witness that closes the R-a′
Election-Safety residual (`frozen-format-v1-2026-07-03.md:107-113`, matrix row 4b line 1052). Every
load-bearing claim is grounded in a file:line I read.

---

## §0 Verdict

**SOUND & BUILDABLE** as an R-a′ closer for the ratified threat model (adversary = filesystem write
access to one node's data directory, **no key material**, and — per the frozen threat model — **not
able to orchestrate peer crashes**, which are non-adversarial faults;
`frozen-format-v1-2026-07-03.md:127-142`). It requires **no change to any frozen at-rest byte
layout**: the witnessed quantity is the anchor's already-frozen strictly-monotone `anchorSeq`
(`frozen-format-v1-2026-07-03.md:265`, `724-725`), the mechanism is a runtime cross-node protocol on
the existing raft channel, and the boot/vote gates are logic in `RaftNode`. The frozen §A1.7 SPI
signature is realized **unmodified** (see §5 for why the vote dimension needs no new SPI field).

**One honest boundary the operator must see (the strict dimension is SPLIT: boot vs vote).** The witness
has two strict dimensions, decoupled by operator ruling (2026-07-04) after a single "full strict" default
broke leader failover (the CI smoke test caught a 3-node cluster failing to re-elect after the leader was
killed). **(1) The strict BOOT gate is the default and always on:** the boot gate requires a
**peer-majority** of QUERY replies, so a witnessing peer is always in the boot-reply set and the R-a′
boot-reply race is REFUSED out of the box at N=3
(`AnchorWitnessRedteamTest.defaultBoot_singleNonWitnessReplyRace_refusedByPeerMajorityBootGate`). Its only
cost is a node **rebooting into a partition** (it stays latched until it can reach a peer-majority —
correct), never a running survivor, so single-fault failover is preserved. The old self-counting boot
quorum (self + one peer) — which had the packet-loss-reachable false-pass an earlier draft wrongly called
"non-adversarial / vanishing" — is **removed**. **(2) Strict VOTE (deferring `voteGranted` until a
peer-majority acks) is an explicit opt-in** (`-Dconfigd.raft.witnessStrict=true`): it is the N≥5 absolute
close of the *grant → witnessed* window, but it DEFERS votes, which breaks single-fault failover — so the
default is **fast vote** (grant immediately; failover preserved). This is strictly better than today
(R-a′ wholly un-witnessed) and closes R-a′ at N=3 by default.

I did **not** find a frozen-format change to be required. No operator escalation on freeze grounds.

---

## §1 Mechanism

### 1.1 What is witnessed, and why `anchorSeq` alone suffices

The witnessed quantity is the per-scope **`anchorSeq`** — the strictly-monotone anti-rollback index
that the frozen write protocol bumps on **every** anchor write, picking the lower-seq slot and
writing `anchorSeq = maxValid+1` (`frozen-format-v1-2026-07-03.md:274-275`, `738-739`).

The crux that makes the vote dimension free: **under the ratified ⟦SEC-MERGE⟧, casting a vote IS an
anchor write.** `currentTerm`/`votedFor` are merged into `ANCHOR_PAYLOAD`
(`frozen-format-v1-2026-07-03.md:264-272`), so the three code paths that persist a vote today —
`DurableRaftState.vote` (`DurableRaftState.java:122-132`, reached from `handleRequestVote`,
`RaftNode.java:1725`), `setTermAndVote` at `startElection` (`RaftNode.java:1915`), and the term step
in `becomeFollower` (`RaftNode.java:1829`) — each become an anchor write that raises `anchorSeq`.
Therefore:

> A within-term `votedFor` rollback (the R-a′ attack, row 4b) **is** an `anchorSeq` rollback: the
> vote's write raised `anchorSeq` to some `s1`; replaying the pre-vote slot image restores a strictly
> lower `s0 < s1`. Witnessing `anchorSeq` monotonicity therefore witnesses the vote.

This is why the frozen SPI's scalar `long anchorSeq` (`frozen-format-v1-2026-07-03.md:883`) is
sufficient and needs no `votedFor` extension. The announce additionally carries `currentTerm` for a
diagnostic term cross-check and for the operator-visible refuse reason; `votedFor` is carried
diagnostic-only (never load-bearing for the gate).

Witnessing is **per scope**: each per-shard anchor (`scopeId = gid`) has its own `anchorSeq`, and the
node-anchor (`scopeId = NODE_SCOPE`) has `nodeAnchorSeq`. A node witnesses each peer per
`(peer, scopeId)`. This maps cleanly onto the existing per-group message routing — inbound frames are
already demuxed by `frame.groupId()` onto each group's owner thread
(`RaftTransportAdapter.java:86-115`). The vote path that R-a′ targets is per-shard, so the normative
gate below is per-shard `anchorSeq`; the node-anchor rides the identical mechanism (freshness only, no
vote — informational for R-a).

### 1.2 In-memory state (no persistence — this is the freeze-safe part)

Per node, per scope, all **in-memory** (rebuilt at boot; never a new on-disk format):

- `bootAnchorSeq` — the `anchorSeq` loaded from the highest-valid anchor slot at boot
  (`frozen-format-v1-2026-07-03.md:276-277`). Captured once at load; the rollback comparison is
  against this booted-from value.
- `witnessOfPeer[P]` — the highest `anchorSeq` this node has seen peer `P` announce (what *we*
  witness about `P`). Monotone-raise only.
- `peerAckOfSelf[P]` — the highest of *our own* `anchorSeq` that peer `P` has confirmed receiving (what
  `P` witnesses about *us*, as reported back). Monotone-raise only. Used to compute how widely our
  latest write has spread (leader-`matchIndex`-style, mirroring the quorum computation at
  `RaftNode.java:1787` / `ClusterConfig.isQuorum`).
- `votingCleared` — a boolean latch, `false` at boot. While `false`, the node grants no vote and
  starts no election. Set `true` only by the boot gate (§1.3) or immediately at N=1 (no peers).

### 1.3 Wire — a dedicated additive raft message (rides the existing channel)

Two new `MessageType` codes on the raft wire (`MessageType.java` currently maxes at `0x11`;
`BY_CODE` sizing at `MessageType.java` grows to admit `0x12`/`0x13`). Additive: **no existing frame
layout, no existing payload, and NOT the dormant `epoch` field are touched** — so all existing raft
golden fixtures stay byte-identical (`frozen-format-v1-2026-07-03.md:425-428` keeps the raft wire at
`ver=0x02` with `epoch` MBZ; we do not use `epoch`). The messages ride the same `FrameCodec` frame
(`FrameCodec.java:154-177`), the same `RaftWireProtocol` 4-byte authenticated sender-id prefix
(`RaftWireProtocol.java:43-44,100-111`), and the same mTLS peer authentication the raft transport
already enforces (`TcpRaftTransport.java:542-543`, `setNeedClientAuth(true)`). The witness therefore
inherits: an adversary without a valid peer cert cannot inject or suppress witness traffic, and
cannot impersonate a peer to feed a false floor. (In the keyless/plaintext posture there are no
adversarial guarantees anywhere — `frozen-format-v1-2026-07-03.md:147-148` — and the witness is
likewise advisory there.)

```
RAFT_WITNESS        (0x12)   sender→peers   symmetric gossip, per group (frame.groupId() = gid)
    [selfAnchorSeq:8]   sender's current anchorSeq for this gid
    [selfTerm:8]        sender's currentTerm  (diagnostic term cross-check)
    [selfVotedFor:4]    sender's votedFor, -1=null  (diagnostic only, never gates)
    [seenOfYouSeq:8]    the highest anchorSeq the sender has witnessed FROM the recipient (this gid)
    [flags:1]           bit0 = QUERY  (recipient should reply promptly, boot path)

RAFT_WITNESS_REPLY  (0x13)   peer→sender   — identical body; a WITNESS with QUERY set is answered by
                                              a WITNESS_REPLY carrying the peer's witnessOfPeer[sender].
```

Encoding lives in `RaftMessageCodec` alongside the existing vote/append codecs
(`RaftMessageCodec.java:176-214`), payloads carrier-versioned by the frame exactly like every other
raft message (`frozen-format-v1-2026-07-03.md:428`). Fixed 29-byte body; the decoder bounds it with
the same `checkRemaining` discipline as the rest of the codec (`RaftMessageCodec.java:108-114`). These
are added to the `RaftMessage` sealed permit-list (`RaftMessage.java:14-16`) and the `handleMessage`
switch (`RaftNode.java:591-603`).

On receiving a `RAFT_WITNESS`/`_REPLY` from `P` (sender id from `InboundMessage.from`,
`InboundMessage.java:13`), a node does, on the group's owner thread:
`witnessOfPeer[P] = max(witnessOfPeer[P], selfAnchorSeq)` and
`peerAckOfSelf[P] = max(peerAckOfSelf[P], seenOfYouSeq)`. Pure monotone raises; no fsync, no
allocation beyond the message.

**Cadence.** (a) Piggyback cadence: broadcast a `RAFT_WITNESS` to all peers on the existing heartbeat
tick (`tickHeartbeat`, `RaftNode.java:1502`) — cheap, already a per-tick fan-out, and it continuously
re-spreads the latest `anchorSeq`. (b) Event cadence: broadcast immediately after a vote-granting
anchor write, **before** the `voteGranted` response is sent (§1.5 ordering). (c) Boot cadence: the
boot gate below broadcasts `RAFT_WITNESS(QUERY)`.

### 1.4 The boot gate (the R-a′ closer)

Because in-memory `votedFor` governs a *running* node (a disk rollback is inert until the node
re-reads disk at boot — see §2 for why the double-vote strictly requires a reboot), the **boot gate is
where R-a′ is closed**; the vote gate (§1.5) is the latch that forbids voting until the boot gate has
run.

At boot, per shard:

1. Load `bootAnchorSeq = s` from the highest-valid anchor slot (existing recovery read,
   `frozen-format-v1-2026-07-03.md:276-277`). Set `votingCleared = false`.
2. If `peersOf(self)` is empty (N=1, `ClusterConfig.peersOf`, `ClusterConfig.java:145-151`): set
   `votingCleared = true` and skip — N=1 cannot split-brain (§3). Otherwise:
3. Broadcast `RAFT_WITNESS(QUERY)` to all peers. Collect `RAFT_WITNESS_REPLY`s (driven by the normal
   tick/message loop — **never a blocking call on the owner thread**; replies accumulate into
   `witnessOfPeer`/a boot-reply set across ticks).
4. Let `W = max over responding peers of their reported seenOfYou` (= what each peer witnessed of *us*).
5. **If `W > s` ⇒ REFUSE TO START.** A peer holds evidence we existed at a higher `anchorSeq` than the
   disk we booted from — the disk was rolled back. Halt this shard (fail-closed), emit a diagnostic +
   an audit record `{action=anchor.rollback.detected, gid, bootAnchorSeq=s, witnessedSeq=W,
   reportingPeer=P}`, and escalate to the operator. This is the exact frozen semantics
   `storedSeq < lastSeen ⇒ REFUSE` (`frozen-format-v1-2026-07-03.md:884-885`) with `storedSeq = s`,
   `lastSeen = W`.
6. **If a quorum responded and `W ≤ s` ⇒ set `votingCleared = true`.** Quorum = `ClusterConfig.isQuorum`
   over the responders including self (`ClusterConfig.java:117-123`). The node may now vote/elect.
7. **If a quorum has not responded within a bounded time ⇒ stay `votingCleared = false`**, keep
   re-issuing the QUERY each tick. This is refuse-to-vote-until-witnessed, **not** a brick: it clears
   the instant a quorum becomes reachable. During it the node is exactly as unavailable-for-election as
   a partitioned minority already is under Raft (§3, row 21).

Timeout/quorum knobs follow the existing `-D` convention
(`RaftWireProtocol.java:65-88`; e.g. `configd.raft.witnessBootQuorumTimeoutMs`, default a few election
timeouts). No new file, no `data-directory`-read control (which the frozen doc forbids for exactly
this reason, `frozen-format-v1-2026-07-03.md:475-477`).

### 1.5 The vote gate (the latch + the announce-before-grant ordering)

Two mechanically-small changes on the vote path:

- **Latch.** In `handleRequestVote` (`RaftNode.java:1694-1734`), `startElection`
  (`RaftNode.java:1909-1946`) and `startPreVote` (`RaftNode.java:1867-1903`): if `!votingCleared`,
  do **not** grant / do **not** start (reply `voteGranted=false`, or drop). A higher term may still be
  *adopted* for liveness (`becomeFollower`, `RaftNode.java:1716,1829`) — adopting a term is not a vote,
  and it raises `anchorSeq`, only ever moving the node *forward*. The grant itself is gated.
- **Announce-before-grant ordering.** When the node decides to grant (the `canVote && logOk` branch,
  `RaftNode.java:1724-1729`): (1) persist the vote → anchor write bumps `anchorSeq` to `s1` (as today,
  persist-before-memory, `DurableRaftState.java:129-131`); (2) **broadcast `RAFT_WITNESS(announce,
  s1)` to all peers**; (3) **only then** send `RequestVoteResponse(voteGranted=true)`
  (`RaftNode.java:1728`). This ordering is the cheap safety hinge (§2): a crash between (1) and (3)
  means the vote was never *usable* (the candidate never received `voteGranted`), so losing its witness
  is harmless.

The exact interleaving the vote gate + boot gate close is walked in §2.

---

## §2 Safety proof — R-a′ closure for N ≥ 3

### 2.1 The double-vote requires a reboot (why the boot gate is sufficient)

A *running* node answers `handleRequestVote` from **in-memory** `votedFor`
(`RaftNode.java:1721`, `boolean canVote = (votedFor == null || votedFor.equals(req.candidateId()))`).
An adversary who rewrites the on-disk anchor slot does **not** change the running node's in-memory
`votedFor`; the rolled-back image is inert until the node next reads disk, which happens only at boot
(`RaftNode.java:368-369`, constructor loads `durableState`). Therefore a second, conflicting vote at
the same term `T` can only be cast **after a reboot**. Every reboot runs the boot gate (§1.4) and the
node grants no vote until the gate clears the latch (§1.5). The boot gate is thus the complete locus
of R-a′ closure.

### 2.2 The concrete attack, blocked

Setup: N ≥ 3, node `V` a voter, adversary owns `V`'s data dir, no keys, cannot crash peers.

1. Term `T`. Candidate `X` requests a vote. `V` grants: persists `{currentTerm=T, votedFor=X}` → anchor
   write raises `anchorSeq` to `s1` (§1.1). By the §1.5 ordering, `V` **broadcasts `RAFT_WITNESS(s1)`
   before** sending `voteGranted=true` to `X`. `X` may collect a quorum incl. `V` and lead `T`.
2. `V` crashes/restarts (required, §2.1). While `V` is down the adversary overwrites `V`'s
   higher-`anchorSeq` slot with a valid **earlier same-term** image
   `{currentTerm=T, votedFor=null, anchorSeq=s0}`, `s0 < s1` (this image really existed: `V` had it
   right after `becomeFollower(T)` set `votedFor=null`, `RaftNode.java:1831`, before granting). Term is
   unchanged, so Step-2.5's term-witness gate is silent by construction
   (`frozen-format-v1-2026-07-03.md:461-467`, `1052`) — this is exactly row 4b.
3. `V` reboots with `bootAnchorSeq = s0`. Boot gate: broadcast QUERY, collect a quorum of replies,
   `W = max reported seenOfYou`.
4. **Closure.** `V`'s vote at (1) became usable only because `voteGranted` was sent, which — by the
   §1.5 ordering — happened **after** the `RAFT_WITNESS(s1)` broadcast. Under continuous re-announce
   (§1.3 cadence-a) over `V`'s uptime, a set `Wit` of peers holds `witnessOfPeer[V] ≥ s1`. Any of them
   in the boot-reply quorum reports `seenOfYou ≥ s1 > s0 = bootAnchorSeq` ⇒ **`W > s` ⇒ REFUSE**
   (§1.4 step 5). `V` never re-enters voting, so it cannot grant a second, conflicting vote at `T`.
   Split-brain from `V`'s double vote is prevented. ∎

### 2.3 What quorum intersection does and does not give

The clean intersection argument: if the witnessing set `Wit` is itself a **quorum of peers**, then any
boot-reply quorum `R` intersects `Wit` in an honest peer (two quorums intersect;
`ClusterConfig.isQuorum` majority semantics, `ClusterConfig.java:117-123,164`), and that peer reports
`≥ s1` ⇒ guaranteed REFUSE. This is **absolute** and is what **strict mode** (§3) buys by making the
vote unusable until a peer-majority has acked `s1`.

The honest subtlety for **default mode**: `Wit` is whoever received `V`'s announces and has not
crash-forgotten. Continuous re-announce spreads `s1` to all connected peers within one heartbeat, so
in steady state `Wit` is the whole reachable peer set. The gate can only **miss** if, at `V`'s
post-reboot QUERY, *every* peer that held `s1` is simultaneously unreachable or has itself
crash-restarted (losing in-memory `witnessOfPeer[V]`) — and the responding quorum `R` therefore
contains none of them. Under the ratified threat model the adversary **cannot cause** those peer
crashes (non-adversarial faults, `frozen-format-v1-2026-07-03.md:139-142`); it can only *wait and
hope* for a coincidence it cannot induce. So default mode **closes R-a′ against the adversary**; the
residual is a pure coincidental-crash window, the same non-adversarial class R-a itself already lives
in. The §1.5 announce-before-grant ordering removes the one window the adversary *could* have exploited
(a `V`-crash between vote-persist and any announce), because a vote that was never announced was also
never sent to the candidate, hence never usable.

### 2.4 Why the on-disk vote can't be forged instead of rolled back

Rollback (replay of an older valid slot) is the only in-scope move: the anchor slots are
MAC/tag-authenticated envelopes (`frozen-format-v1-2026-07-03.md:261`, algId 1/2), so an adversary
without keys cannot fabricate a *new* `{votedFor=null, anchorSeq=s1}` image — any in-place edit breaks
the MAC and fails the slot (`DurableRaftState.java:167-189` load path throws on tamper; frozen
`frozen-format-v1-2026-07-03.md:276-277`). The adversary is confined to restoring genuinely-earlier
images, which necessarily carry `anchorSeq < s1`. That is precisely what the witness detects.

---

## §3 False-positive / liveness analysis (first-class)

A gate that bricks a healthy node on a legal crash or a legal Raft transition fails as surely as one
that misses the attack (`frozen-format-v1-2026-07-03.md:141-142`). The gate direction is
**`W > s ⇒ REFUSE` only** — refuse strictly when a peer witnessed *higher than local*. Mirrors the
frozen false-positive discipline (rows 18–22, `frozen-format-v1-2026-07-03.md:1070-1081`).

| # | Case | Behavior | Why correct |
|---|---|---|---|
| W1 | **Legit reboot, no rollback** (local = true latest) | **PASS** — every peer's `seenOfYou ≤ s`, so `W ≤ s` | The node booted from its real latest slot; nobody ever saw it higher. |
| W2 | **Advanced past peers** (local `s` > anything peers saw; e.g. the node wrote `s1` then crashed before any peer received the announce, no rollback) | **PASS** — `W = s0 < s`, so `W ≤ s` | Not a rollback: local is ahead. The gate refuses only `W > s`; being ahead of the witnessed floor is legal and expected (writes lead witnessing). Critical non-false-positive. |
| W3 | **Real rollback** (local rolled back to `s0`; a peer saw `s1 > s0`) | **REFUSE** | The intended detection (§2.2). |
| W4 | **Partition from quorum at boot** | **REFUSE TO VOTE, retry; no brick** | `votingCleared` stays false until a quorum is reachable (§1.4 step 7). Identical availability to a partitioned Raft minority — it cannot win an election either way. Clears automatically on heal. |
| W5 | **A single lying/buggy peer** reports a bogus high `seenOfYou` | Boot gate would REFUSE (fail-closed, safe direction). A single peer cannot force a *false PASS* (PASS needs a quorum and no `W>s`). | Fail-closed asymmetry: a spurious high report only ever causes a (safe) refuse-and-escalate, never a missed rollback. Under mTLS a non-peer cannot report at all (`TcpRaftTransport.java:542-543`). |
| W6 | **Torn anchor write on crash** (non-attack) | **PASS** — the torn slot fails CRC/MAC, the intact lower-seq slot wins; `bootAnchorSeq` = that intact seq; peers saw ≤ it | Dual-slot recovery (`frozen-format-v1-2026-07-03.md:274-277`, matrix row 18) picks a valid slot; no phantom rollback. |
| W7 | **Legit conflict truncation / compaction** | **PASS** — these lower `lastDurableIndex`/advance `snapshotIndex` but only ever *raise* `anchorSeq` (every anchor write bumps it) | `anchorSeq` is monotone across all writes (`frozen-format-v1-2026-07-03.md:274-275`); witnessing is on `anchorSeq`, never on the index/term fields, so a legal Raft rewrite never trips the witness. Independent of frozen rows 19–20. |
| W8 | **Slow first election after boot** | Election delayed until `votingCleared` (one boot QUERY round-trip) | Bounded extra latency on the first post-boot election only; steady state unaffected. Acceptable — elections are rare. |

### 3.1 Mode trade (the operator's one decision — SPLIT into boot vs vote)

**Operator ruling (2026-07-04).** The witness has two strict dimensions, and they are decoupled because a
single "full strict" default broke leader failover (the CI smoke test caught a 3-node cluster failing to
re-elect after the leader was killed: in full strict a survivor cannot grant a usable vote until a
peer-majority acks its announce, and the dead node can never ack). The ruling: **the strict BOOT gate is
the default; strict VOTE (deferral) is an explicit opt-in.**

- **Strict BOOT gate — ALWAYS ON (the default, not a toggle).** The boot gate requires a **peer-majority**
  of QUERY replies to clear. Two witness quorums then always intersect, so a witnessing peer is always in
  the boot-reply set and a real rollback is always REFUSED — this is what closes the R-a′ boot-reply race
  at N=3, out of the box, in **every** mode. **Cost:** only a node **rebooting into a partition** — it
  cannot reach a peer-majority, so it stays latched (refuse-to-vote) until the cluster heals. This is
  *correct*: a node that cannot reach a peer-majority should not vote yet. It does **not** cost a running
  survivor (already cleared at a healthy boot), so single-fault leader failover is unaffected. The old
  self-counting quorum (self + a single peer) is **removed** — it was the one with the boot-reply race
  (`AnchorWitnessRedteamTest.defaultBoot_singleNonWitnessReplyRace_refusedByPeerMajorityBootGate`).
- **Fast vote — the DEFAULT vote path.** `voteGranted` is sent immediately after the announce
  (announce-before-grant preserved), so a running survivor grants a vote at once even with a peer down and
  **single-fault leader failover is preserved**
  (`AnchorWitnessPeerQuorumTest.defaultFastVote_survivorGrantsImmediatelyWithPeerDown_failoverWorks`).
  At N=3 the strict boot gate already closes R-a′, so fast vote is the correct default.
- **Strict vote — opt-in (`-Dconfigd.raft.witnessStrict=true`).** `voteGranted` is DEFERRED until a
  **peer-majority** acks the announce. Then `Wit` is always a peer-quorum for the *grant → witnessed*
  window too — the **N≥5 absolute close** of the residual below. **Cost:** deferral reduces election
  availability (at N=3 a survivor cannot elect a new leader while one peer is down — the failover break),
  which is exactly why it is opt-in, not the default. Enable only where N≥5 absolute closure outweighs
  the failover cost (`…strictMode_singleNonWitnessReplyRace_closed`,
  `…strictMode_voteDeferredUntilPeerMajorityAcks`).

**N≥5 default residual (fast vote).** With fast vote, the *grant → witnessed* window is not force-closed:
a granted vote is announced (not deferred), so if that single announce is lost to a peer-majority AND the
voter is then rolled back before its re-announce cadence re-spreads `s1`, the boot gate could miss it.
This needs **sustained multi-peer announce packet loss** (the heartbeat-cadence re-announce re-spreads
`s1` every interval, so a single drop does not suffice) and is closed by strict vote. At N=3 it is moot:
the strict boot gate alone closes R-a′ (a peer-majority is both peers, so a rollback is always witnessed).

**Boot-window advertisement (no-false-refuse hardening).** While a node's boot gate is latched it
advertises its **frozen `bootAnchorSeq`**, not its live one. Ordinary post-boot catch-up (a follower
append or a term adoption before the gate clears — both ungated, both raising the live `anchorSeq`)
would otherwise make peers witness the node *above* its booted-from seq and reflect it back as
`W > bootAnchorSeq`, false-refusing a **healthy** node on a rolling restart (a peer commonly replicates
to a rebooting node before its gate clears). The frozen value cannot weaken detection — it can only
*raise* a peer's witness via `max`, never lower a genuinely rolled-back node's peers' (higher) pre-crash
memory. Regression: `AnchorWitnessPeerQuorumTest.catchUpDuringBoot_healthyNode_notFalseRefused`.

### 3.2 N = 1 and N = 2

- **N = 1.** No peers ⇒ no witness possible; the gate is disabled (`votingCleared = true` immediately,
  §1.4 step 2). This is correct, not a gap: a single voter **cannot** split-brain (there is no second
  quorum to form), so R-a′'s *harm* is void at N=1. The freshness residual **R-a** (stale reads / lost
  recent writes from a within-term rollback, `frozen-format-v1-2026-07-03.md:103-106`) **remains** at
  N=1 and is closable only by an external-store witness through the same SPI (§5). Documented, not
  overclaimed.
- **N = 2.** One peer; `isQuorum` requires both nodes (`ClusterConfig.java:164`, `majorityOf(2)=2`), so
  there is no fault-tolerant quorum to begin with. The witness = that single peer: if it is up and
  remembers, a rollback of the other node is caught; if it is down, neither node can make progress
  anyway (no quorum). Honest guarantee: **R-a′ is closed at N=2 exactly when the peer is available**,
  which is already the precondition for any progress at N=2. No worse than Raft's own N=2 story.

---

## §4 Freeze-compatibility argument

The witness is **layered on the already-frozen anchor**; it mutates **no** frozen at-rest byte layout.

1. **At-rest formats: untouched.** The witnessed quantity is the existing `ANCHOR_PAYLOAD.anchorSeq`
   (`frozen-format-v1-2026-07-03.md:265,724-725`) and `NODE_ANCHOR_PAYLOAD.nodeAnchorSeq`
   (`frozen-format-v1-2026-07-03.md:285`) — read-only. No field is added to any envelope, anchor,
   node-anchor, keyring, WAL, snapshot, or topology record. The IntegrityEnvelope v3
   (`frozen-format-v1-2026-07-03.md:182-231`) is unchanged.
2. **No new persistent file.** The witness keeps *only in-memory* state (§1.2), rebuilt at boot by
   querying peers. There is therefore no new on-disk artifact needing a version story, and no
   `data-directory`-read control that could become the adversary's silent defeat switch (the failure
   mode the frozen doc explicitly guards against, `frozen-format-v1-2026-07-03.md:475-477`).
3. **Wire: additive, within the existing raft wire version.** Two new `MessageType` codes
   (`0x12/0x13`) on the raft frame; the frame layout `[len][ver=0x02][type][gid][term][epoch][payload]
   [CRC]` is unchanged, and the **dormant `epoch` field is NOT used** — it stays MBZ / reject-if-nonzero
   as the freeze specifies (`frozen-format-v1-2026-07-03.md:425-428`, `FrameCodec.java:32-35,168,309`).
   Because the cluster runs a single strict wire version (no rolling-version skew;
   `FrameCodec.java:54-67` is a strict tripwire, not a negotiation), adding a message type introduces
   no mixed-version hazard. Existing raft golden fixtures are for existing types and stay byte-identical
   (`frozen-format-v1-2026-07-03.md:396` "raft-wire goldens untouched"); the new types get their own new
   goldens. This lands **before** the tag, so the frozen wire includes the witness from day one — it is
   not a post-freeze mutation.
4. **Consequence.** Had closing R-a′ demanded a new anchor field or a new persistent witness store, that
   would be a frozen-format change and an operator escalation. It does not: the merge already made the
   vote an `anchorSeq`-bearing write, so the anti-rollback index the anchor *already* carries is the
   witness. **No escalation.**

---

## §5 SPI mapping + external-store seam

The frozen SPI (`frozen-format-v1-2026-07-03.md:881-886`):

```java
interface AnchorWitness { void record(int scopeId, long anchorSeq); long lastSeen(int scopeId); }
```

Realized by the peer-quorum provider **unmodified**:

- **`record(scopeId, anchorSeq)`** — invoked by the anchor writer after each fsync
  (`frozen-format-v1-2026-07-03.md:884` "the anchor writer calls `record` after each fsync"). The
  peer-quorum impl maps this to *"broadcast `RAFT_WITNESS(anchorSeq)` for this scope's group"* (and, in
  strict mode, defer the dependent `voteGranted` until a peer-majority ack). Fire-and-forget; the
  "record" durability substrate is the peers' in-memory `witnessOfPeer` tables, re-established by
  continuous re-announce.
- **`lastSeen(scopeId)`** — invoked by boot (`frozen-format-v1-2026-07-03.md:884` "boot calls
  `lastSeen`; a `storedSeq < lastSeen ⇒ REFUSE`"). The peer-quorum impl maps this to *"issue
  `RAFT_WITNESS(QUERY)`, collect a quorum of replies, return `W = max reported seenOfYou`."* The boot
  gate's `W > bootAnchorSeq ⇒ REFUSE` (§1.4) is exactly `storedSeq < lastSeen ⇒ REFUSE`.
- **`scopeId`** — `gid` for per-shard anchors, `NODE_SCOPE` for the node-anchor
  (`frozen-format-v1-2026-07-03.md:211-214`). The witness is per-scope; the boot QUERY covers every
  local group.

The vote dimension needs **no SPI extension**: casting a vote is an `anchorSeq`-raising anchor write
(§1.1), so the scalar `long anchorSeq` already carries it. This is the design's most important
freeze-alignment result — the ratifier's frozen interface is realized as-is.

**External-store seam (closes R-a, incl. at N=1).** The same SPI admits a second provider —
TPM/RPMB NV-counter or a cloud monotonic counter — as the frozen threat model names for residual
(a)/R-a (`frozen-format-v1-2026-07-03.md:136-137,144-146,881-886`). Such a provider persists
`anchorSeq` to external monotonic storage on `record` and returns it on `lastSeen`, closing
anchor-rollback *including on a single node* (which peers cannot witness, §3.2). The two providers
are interchangeable behind `AnchorWitness`; a deployment may run both (refuse if *either* reports
`lastSeen > stored`). Left as the documented seam — the peer-quorum provider is v1; the external-store
provider is the R-a closer for later, no format change needed to add it.

---

## §6 Build seams + real-attack tests

Every guarantee is proven by a test that **performs** the attack (rolls the anchor slot on disk,
drives a second vote), not by analysis — the project standard.

### 6.1 Production seams to modify (all logic/wire; no frozen-format edit)

| Seam | File:line | Change |
|---|---|---|
| Message types | `MessageType.java` (enum + `BY_CODE`) | add `RAFT_WITNESS=0x12`, `RAFT_WITNESS_REPLY=0x13` |
| Sealed permit-list | `RaftMessage.java:14-16` | permit the two new records |
| Codec | `RaftMessageCodec.java:176-214` | `encode`/`decode` for the fixed 29-B bodies, `checkRemaining`-bounded (`:108-114`) |
| Dispatch | `RaftNode.java:591-603` (`handleMessage`) | route the two types to witness handlers (owner thread) |
| Witness state | `RaftNode.java` fields near `votedFor` (`:52`) | in-memory `witnessOfPeer`, `peerAckOfSelf`, `bootAnchorSeq`, `votingCleared` (per-group) |
| Announce | `RaftNode.java:1502` (`tickHeartbeat`) + grant branch `:1724-1729` | heartbeat-cadence broadcast; announce-before-`voteGranted` ordering |
| Boot gate | `RaftNode.java:365-369` (constructor) + a boot-verify step in the group bring-up (`ConfigdServer.buildRaftGroup`, cited `frozen-format-v1-2026-07-03.md:701`) | capture `bootAnchorSeq`, run the QUERY/quorum/REFUSE gate, drive the latch |
| Vote latch | `handleRequestVote` `:1721-1733`, `startElection` `:1909-1915`, `startPreVote` `:1867-1876` | gate grant/start on `votingCleared` |
| SPI wiring | new `PeerQuorumAnchorWitness implements AnchorWitness` | `record`→broadcast, `lastSeen`→QUERY-quorum; wired where the anchor writer lives (Gate 3) |

Config: `-D` knobs only (`RaftWireProtocol.java:65-88` convention) — boot-quorum timeout, strict-mode
toggle. No data-dir-read control (`frozen-format-v1-2026-07-03.md:475-477`).

### 6.2 Real-attack test list (each PERFORMS the attack)

1. **R-a′ double-vote, blocked (the headline).** N=3. Node `V` grants a vote for `X` at `T`
   (anchorSeq→`s1`), a peer witnesses `s1`; kill `V`; on disk **overwrite `V`'s anchor slot** with the
   captured earlier `{term=T, votedFor=null, anchorSeq=s0}` image (real byte rewrite, valid MAC because
   it is a genuine prior image); reboot `V`; drive a RequestVote from `Y≠X` at `T`. **Assert: boot gate
   REFUSES (`W=s1 > s0`); `V` never grants `Y`; no two leaders at `T`.**
2. **Grant→announce crash race.** Kill `V` in the window *after* the vote-persist but *before* the
   announce+`voteGranted` (inject a fault between `DurableRaftState.java:129-131` and the response
   send). Assert `X` never counted `V`'s vote (no `voteGranted` observed), so the subsequent
   rollback+reboot+vote-`Y` produces **no double-USE** even if the boot gate PASSES. Proves the §1.5
   ordering.
3. **Legit reboot, no false-positive (W1).** Clean crash, no disk edit; reboot. Assert boot gate PASSES
   and `V` votes normally.
4. **Advanced-past-peers, no false-positive (W2).** `V` writes `s1`, partition it from all peers so the
   announce lands nowhere, clean crash (no rollback), reboot with peers reachable. Assert `W < s` ⇒
   PASS (a node ahead of the witnessed floor is legal).
5. **Partition at boot (W4).** Boot `V` with a quorum unreachable. Assert `votingCleared` stays false
   (no grant, no election, **no brick**), then heals to `true` when the quorum returns.
6. **Torn-slot crash (W6).** Crash mid anchor-write (torn higher slot); reboot. Assert dual-slot picks
   the intact slot and the gate PASSES (no phantom rollback).
7. **Conflict-truncation / compaction (W7).** Drive a legal Raft conflict truncation and a compaction;
   assert `anchorSeq` only rose and the witness never trips.
8. **mTLS / spoof rejection (W5).** A non-peer (no valid cert) attempts a `RAFT_WITNESS` injection;
   assert the transport rejects it (`TcpRaftTransport.java:542-543`) so it can neither raise a false
   floor nor suppress a true one.
9. **Strict-mode absolute close.** Enable strict mode; crash all-but-one peer such that `Wit` would be a
   sub-quorum in default mode; assert the vote is not usable until a peer-majority acked, and the boot
   gate cannot be dodged. Documents the fault-tolerance cost alongside.
10. **N=1 harmlessness + N=2 boundary.** N=1: assert the gate is disabled and R-a is documented-open.
    N=2: assert rollback is caught iff the single peer is available.

---

## Appendix — grounding index (files read for this design)

`frozen-format-v1-2026-07-03.md` §0/ratification `:7-31,54-124`, threat model `:127-148`, envelope v3
`:182-231`, anchor `:251-278,704-745`, node-anchor `:280-295,853-859`, recovery/Step-2.5
`:449-486,779-800`, INV-ANCHOR-ACK `:802-843`, SPI `:881-886`, matrix rows 4b/14/15/18-22
`:1046-1081`, residuals R-a/R-a′ `:1085-1104`. `RaftNode.java` dispatch `:591-603`, vote path
`:1694-1734`, pre-vote `:1736-1807`, transitions `:1827-1946`, becomeLeader `:1953-1964`, election
tick `:1486-1502`, constructor/load `:365-369`. `DurableRaftState.java` vote/persist `:122-189`.
`RaftMessageCodec.java` codec pattern `:108-214,432-457`. `FrameCodec.java` frame + epoch MBZ
`:32-35,52-73,154-177,300-315`. `MessageType.java` (full). `RaftMessage.java:14-16`.
`RaftWireProtocol.java:43-127`. `InboundMessage.java:13`. `RaftTransportAdapter.java:86-115`.
`ClusterConfig.java:117-171`. `RaftTransport.java` (consensus-core). `TcpRaftTransport.java:542-543`,
`TlsConfig.java:14`.
