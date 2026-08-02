package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-team attack suite for the peer-quorum {@link AnchorWitness} election-safety guarantee: closes a
 * within-term votedFor rollback that would otherwise let a node double-vote and cause a split-brain.
 * These go beyond {@link AnchorWitnessPeerQuorumTest}: every test performs an attack on real file-backed
 * {@link RaftNode}s with a genuine on-disk anchor rollback, and - crucially - drives the network with
 * per-frame selective delivery so a boot-reply race can be reproduced (the synchronous lock-step
 * {@code settle()} in the builder test cannot express one peer answering before another).
 *
 * <p>Two classes of claim are attacked:
 * <ul>
 *   <li>SAFETY: a real rollback is refused and the victim never casts a second, conflicting vote.</li>
 *   <li>NO-FALSE-REFUSE: a legal reboot / partition / Raft rewrite is not bricked.</li>
 * </ul>
 *
 * <p>The boot gate is always strict (peer-majority of QUERY replies). An earlier design cleared it on a
 * self-counting cluster quorum (self + a single peer), which had an adversary-reachable boot-reply race:
 * a peer that missed the vote announce (ordinary packet loss - not a peer crash) answering first cleared
 * the gate before a healthy-but-slower witness replied, so a rolled-back node false-passed. The
 * peer-majority boot gate is the default (two witness quorums then always intersect, so a witness is
 * always in the boot-reply set), so that race is now refused by default
 * ({@link #defaultBoot_singleNonWitnessReplyRace_refusedByPeerMajorityBootGate}). Vote deferral (full
 * strict) is a separate opt-in ({@link #strictMode_singleNonWitnessReplyRace_closed} /
 * {@link #strictMode_voteDeferredUntilPeerMajorityAcks}); it is not the default because deferring
 * voteGranted breaks single-fault leader failover.
 *
 * <p>See {@code docs/architecture/anchor-witness-peer-quorum.md}.
 */
class AnchorWitnessRedteamTest {

    private static final NodeId V = NodeId.of(1);
    private static final NodeId X = NodeId.of(2);
    private static final NodeId P = NodeId.of(3);
    private static final NodeId Y = NodeId.of(4); // a 4th voter used as the SECOND candidate in N>=4 cases

    private static IntegrityEnvelope keyed() {
        return SnapshotIntegrityTest.keyedEnvelope();
    }


    private record Rollback(int gid, long bootAnchorSeq, long witnessedSeq, NodeId reporter) {}

    private record Sent(NodeId target, RaftMessage message) {}

    private record Env(NodeId from, NodeId to, RaftMessage msg) {}

    private static final class Node {
        final NodeId id;
        final Set<NodeId> peers;
        final Path dir;
        final Cluster cluster;
        final boolean strict;
        Storage storage;
        RaftLog log;
        RaftNode raft;
        boolean down;
        final List<Sent> sent = new ArrayList<>();
        Rollback rollback;

        Node(NodeId id, Set<NodeId> peers, Path dir, Cluster cluster, boolean strict) {
            this.id = id;
            this.peers = peers;
            this.dir = dir;
            this.cluster = cluster;
            this.strict = strict;
            build();
        }

        void build() {
            this.storage = Storage.file(dir);
            this.log = new RaftLog(storage, keyed(), 0);
            RaftTransport transport = (target, message) -> {
                sent.add(new Sent(target, message));
                cluster.wire.addLast(new Env(id, target, message));
            };
            this.raft = new RaftNode(RaftConfig.of(id, peers),
                    log, transport, new NoopStateMachine(), new Random(id.id() * 7L + 11L),
                    storage, RaftNode.InvariantChecker.NOOP, keyed());
            this.raft.armAnchorWitness(strict, (gid, bootSeq, witnessedSeq, reporter) ->
                    this.rollback = new Rollback(gid, bootSeq, witnessedSeq, reporter));
        }

        long anchorSeq() {
            return log.anchorSeq();
        }

        boolean grantedVoteTo(NodeId candidate) {
            return sent.stream().anyMatch(s -> s.target().equals(candidate)
                    && s.message() instanceof RequestVoteResponse r && r.voteGranted() && !r.preVote());
        }

        /** Index in {@link #sent} of the first granted RequestVoteResponse to {@code candidate}, or -1. */
        int grantIndex(NodeId candidate) {
            for (int i = 0; i < sent.size(); i++) {
                Sent s = sent.get(i);
                if (s.target().equals(candidate) && s.message() instanceof RequestVoteResponse r
                        && r.voteGranted() && !r.preVote()) {
                    return i;
                }
            }
            return -1;
        }

        /** Index in {@link #sent} of the first witness announce (non-QUERY) to any peer, or -1. */
        int firstAnnounceIndex() {
            for (int i = 0; i < sent.size(); i++) {
                if (sent.get(i).message() instanceof WitnessMessage w && !w.query()) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static final class Cluster {
        final Path base;
        final Map<NodeId, Node> nodes = new LinkedHashMap<>();
        final Deque<Env> wire = new ArrayDeque<>();

        Cluster(Path base) {
            this.base = base;
        }

        Node add(NodeId id, Set<NodeId> peers, boolean strict) {
            Node n = new Node(id, peers, base.resolve("node-" + id.id()), this, strict);
            nodes.put(id, n);
            return n;
        }

        void tick(Node n) {
            if (!n.down) {
                n.raft.tick();
            }
        }

        void tickAll() {
            for (Node n : nodes.values()) {
                tick(n);
            }
        }

        /** Delivers exactly the frames queued at call time that match {@code pred}; re-queues the rest. */
        void deliver(Predicate<Env> pred) {
            int n = wire.size();
            for (int i = 0; i < n; i++) {
                Env e = wire.pollFirst();
                Node target = nodes.get(e.to());
                if (pred.test(e) && target != null && !target.down) {
                    target.raft.handleMessage(e.msg());
                } else {
                    wire.addLast(e);
                }
            }
        }

        void deliverAll() {
            deliver(e -> true);
        }

        /** Deliver only frames from a specific origin (models "peer P answers, peer X is still silent"). */
        void deliverFrom(NodeId from) {
            deliver(e -> e.from().equals(from));
        }

        /** Drop every queued frame from {@code from} (models announce packet-loss on the V->P link). */
        void dropFrom(NodeId from) {
            wire.removeIf(e -> e.from().equals(from));
        }

        void dropAll() {
            wire.clear();
        }

        /** tickAll + deliverAll, {@code rounds} times - drives the healthy boot gate to a fixed point. */
        void settle(int rounds) {
            for (int i = 0; i < rounds; i++) {
                tickAll();
                deliverAll();
            }
        }

        Map<String, byte[]> snapshotDisk(Node n) {
            Map<String, byte[]> image = new HashMap<>();
            try (Stream<Path> files = Files.list(n.dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        image.put(f.getFileName().toString(), Files.readAllBytes(f));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return image;
        }

        void kill(Node n) {
            n.log.closeAnchor();
            n.down = true;
        }

        /** REAL byte rollback: overwrite a killed node's dir with a captured earlier durable image. */
        void rollbackDisk(Node n, Map<String, byte[]> image) {
            try {
                for (Map.Entry<String, byte[]> e : image.entrySet()) {
                    Files.write(n.dir.resolve(e.getKey()), e.getValue());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        void reboot(Node n) {
            n.sent.clear();
            n.rollback = null;
            n.down = false;
            n.build();
        }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static AppendEntriesRequest heartbeat(long term, NodeId leader) {
        return new AppendEntriesRequest(term, leader, 0, 0, List.of(), 0);
    }

    private static RequestVoteRequest voteReq(long term, NodeId candidate) {
        return new RequestVoteRequest(term, candidate, 0, 0, false);
    }


    @Test
    void headline_realRollbackRefused_andAdoptForwardCannotUnlatch(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        c.add(X, Set.of(V, P), false);
        c.add(P, Set.of(V, X), false);
        c.settle(6);
        assertTrue(v.raft.votingClearedForTest(), "healthy V clears its boot gate");

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        Map<String, byte[]> prior = c.snapshotDisk(v);

        v.raft.handleMessage(voteReq(T, X));
        long s1 = v.anchorSeq();
        assertTrue(s1 > s0, "granting a vote raises anchorSeq");
        assertTrue(v.grantedVoteTo(X), "V granted its term-" + T + " vote to X");
        c.deliverAll();

        c.kill(v);
        c.rollbackDisk(v, prior);
        c.reboot(v);
        assertEquals(s0, v.anchorSeq(), "V booted from the rolled-back s0");
        assertNull(v.raft.votedFor(), "the rollback restored votedFor=null (the double-vote setup)");

        c.settle(6);
        assertNotNull(v.rollback, "boot gate MUST detect the rollback (a peer witnessed s1 > s0)");
        assertEquals(s1, v.rollback.witnessedSeq());
        assertFalse(v.raft.votingClearedForTest(), "V stays latched after a detected rollback");

        // The double vote: a DIFFERENT candidate P asks V at the SAME term T. Only the latch can stop it.
        v.sent.clear();
        v.raft.handleMessage(voteReq(T, P));
        assertFalse(v.grantedVoteTo(P), "SPLIT-BRAIN BLOCKED: V must not grant a 2nd vote at term " + T);

        for (long t = T + 1; t <= T + 4; t++) {
            v.raft.handleMessage(heartbeat(t, X));
            c.settle(3);
            v.sent.clear();
            v.raft.handleMessage(voteReq(t, P));
            assertFalse(v.grantedVoteTo(P), "still latched at term " + t + " - adopting terms never re-arms voting");
        }
        assertFalse(v.raft.votingClearedForTest(), "V is latched forever until an operator intervenes");
    }

    // Announce-before-grant ordering is load-bearing: the witness announce to peers is emitted
    // before the voteGranted to the candidate. A crash in that window leaves the vote unusable.

    @Test
    void announceBeforeGrant_orderingIsLoadBearing(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        c.add(X, Set.of(V, P), false);
        c.add(P, Set.of(V, X), false);
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        v.sent.clear(); // isolate exactly the frames emitted by the grant
        v.raft.handleMessage(voteReq(T, X));

        int announceIdx = v.firstAnnounceIndex();
        int grantIdx = v.grantIndex(X);
        assertTrue(announceIdx >= 0, "grant must emit a witness announce to peers");
        assertTrue(grantIdx >= 0, "grant must emit voteGranted to the candidate");
        assertTrue(announceIdx < grantIdx,
                "announce (idx " + announceIdx + ") MUST precede voteGranted (idx " + grantIdx
                        + ") - a crash between them must leave the vote unusable, not un-witnessed");
    }

    @Test
    void grantAnnounceCrashRace_unusableVoteHasNoDoubleUse(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        Node x = c.add(X, Set.of(V, P), false);
        c.add(P, Set.of(V, X), false);
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        Map<String, byte[]> prior = c.snapshotDisk(v);

        v.raft.handleMessage(voteReq(T, X));
        assertTrue(v.anchorSeq() > s0);
        c.dropAll();                          // crash before ANY of it is delivered: nothing witnessed, X never counts
        assertNull(x.raft.votedFor(), "X never counted V's never-delivered vote");

        // Roll back + reboot. No peer witnessed s1, so the gate PASSES - but that is SAFE: the first vote
        // was never usable. V now casts its single usable vote (for P).
        c.kill(v);
        c.rollbackDisk(v, prior);
        c.reboot(v);
        c.settle(6);
        assertNull(v.rollback, "no peer witnessed s1 -> the gate correctly does not refuse");
        assertTrue(v.raft.votingClearedForTest());
        v.raft.handleMessage(voteReq(T, P));
        assertTrue(v.grantedVoteTo(P), "V's ONE usable vote goes to P - the never-delivered vote is not a double-USE");
    }


    @Test
    void defaultBoot_singleNonWitnessReplyRace_refusedByPeerMajorityBootGate(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);   // DEFAULT: fast-vote (strictVote=false); boot is always strict
        c.add(X, Set.of(V, P), false);
        c.add(P, Set.of(V, X), false);
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        Map<String, byte[]> prior = c.snapshotDisk(v);

        v.raft.handleMessage(voteReq(T, X));
        long s1 = v.anchorSeq();

        // ORDINARY PACKET LOSS (not a peer crash): the announce to P is dropped; only X witnesses s1.
        c.dropFrom(V);
        v.raft.witnessAnnounce();
        c.deliver(e -> e.to().equals(X));

        c.kill(v);
        c.rollbackDisk(v, prior);
        c.reboot(v);
        assertEquals(s0, v.anchorSeq());
        c.dropAll();

        // Boot-reply RACE: P (the non-witness) answers first; X is healthy but slower.
        c.tick(v);
        c.deliver(e -> e.from().equals(V) && e.to().equals(P)); // only P is queried (X's QUERY withheld = slower)
        c.deliverFrom(P);                       // P's reply (seenOfYou = s0) reaches V
        c.tick(v);                              // evaluateBootGate: responders={P}, self+P is NOT a peer-majority

        assertFalse(v.raft.votingClearedForTest(),
                "DEFAULT: self + one non-witness peer is NOT a peer-majority - the gate does NOT clear (race closed)");
        assertNull(v.rollback, "not yet refused - the witnessing peer (X) has not replied yet");

        // X's reply (s1) now lands and completes the peer-majority; the gate trips REFUSE.
        c.settle(6);
        assertNotNull(v.rollback, "once the witnessing peer's reply lands, the rollback is REFUSED (absolute close)");
        assertEquals(s1, v.rollback.witnessedSeq());
        assertFalse(v.raft.votingClearedForTest());

        v.sent.clear();
        v.raft.handleMessage(voteReq(T, P));
        assertFalse(v.grantedVoteTo(P),
                "DEFAULT closes the boot-reply race: no double-vote at term " + T + " even in fast-vote mode");
    }

    @Test
    void strictMode_singleNonWitnessReplyRace_closed(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), true);
        Node x = c.add(X, Set.of(V, P), true);
        c.add(P, Set.of(V, X), true);
        c.settle(8);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        Map<String, byte[]> prior = c.snapshotDisk(v);

        v.raft.handleMessage(voteReq(T, X));
        long s1 = v.anchorSeq();
        c.dropFrom(V);
        v.raft.witnessAnnounce();
        c.deliver(e -> e.to().equals(X));        // only X witnesses s1 (P misses the announce)

        c.kill(v);
        c.rollbackDisk(v, prior);
        c.reboot(v);
        assertEquals(s0, v.anchorSeq());
        c.dropAll();

        // Same race: P answers first. Strict boot gate needs a peer-MAJORITY of replies (2 of 2 peers),
        // so self+P is NOT enough - it does NOT clear on P alone.
        c.tick(v);
        c.deliver(e -> e.from().equals(V) && e.to().equals(P));
        c.deliverFrom(P);
        c.tick(v);
        assertFalse(v.raft.votingClearedForTest(), "strict does NOT clear on a single peer - the race is closed");

        // X's reply (s1) now completes the peer-majority; the gate trips REFUSE.
        c.settle(6);
        assertNotNull(v.rollback, "strict REFUSES once the witnessing peer's reply lands (absolute close)");
        assertFalse(v.raft.votingClearedForTest());
        v.sent.clear();
        v.raft.handleMessage(voteReq(T, P));
        assertFalse(v.grantedVoteTo(P), "strict never accepts the double vote");
    }

    @Test
    void strictMode_voteDeferredUntilPeerMajorityAcks(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), true);
        Node x = c.add(X, Set.of(V, P), true);
        Node p = c.add(P, Set.of(V, X), true);
        c.settle(8);
        assertTrue(v.raft.votingClearedForTest(), "strict boot gate clears on a peer-majority of replies");

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        v.raft.handleMessage(voteReq(T, X));
        assertEquals(X, v.raft.votedFor(), "the vote is persisted immediately (durability unchanged)");
        assertFalse(v.grantedVoteTo(X), "strict DEFERS voteGranted until a peer-majority acks the announce");

        c.deliverAll();
        x.raft.witnessAnnounce();
        c.deliverAll();
        assertFalse(v.grantedVoteTo(X), "one peer ack is below the peer-majority - still deferred");
        p.raft.witnessAnnounce();
        c.deliverAll();
        assertTrue(v.grantedVoteTo(X), "peer-majority acked s1 -> the deferred voteGranted is released");
    }


    @Test
    void w1_cleanReboot_passesAndVotes(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        c.add(X, Set.of(V, P), false);
        c.add(P, Set.of(V, X), false);
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        v.raft.handleMessage(voteReq(T, X));
        c.deliverAll();

        c.kill(v);
        c.reboot(v);
        c.settle(6);
        assertNull(v.rollback, "a clean reboot from the latest anchor is never a rollback");
        assertTrue(v.raft.votingClearedForTest());
        v.raft.handleMessage(voteReq(T + 1, P));
        assertTrue(v.grantedVoteTo(P), "after a clean reboot V votes normally at a fresh term");
    }

    @Test
    void w2_advancedPastPeers_passes(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        c.add(X, Set.of(V, P), false);
        c.add(P, Set.of(V, X), false);
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        v.raft.handleMessage(voteReq(T, X));
        long s1 = v.anchorSeq();
        assertTrue(s1 > s0);
        c.dropAll();                             // the announce lands NOWHERE - V is simply ahead of peers

        c.kill(v);
        c.reboot(v);                             // clean reboot from s1 (no rollback), peers reachable
        c.settle(6);
        assertNull(v.rollback, "being AHEAD of the witnessed floor is legal (writes lead witnessing), never a rollback");
        assertTrue(v.raft.votingClearedForTest());
    }

    @Test
    void w4_partitionAtBoot_latchedNotBricked_thenHeals(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        Node x = c.add(X, Set.of(V, P), false);
        Node p = c.add(P, Set.of(V, X), false);

        x.down = true;
        p.down = true;
        c.settle(6);
        assertNull(v.rollback, "a partition is not a rollback");
        assertFalse(v.raft.votingClearedForTest(), "no quorum -> latched (refuse-to-vote), NOT bricked");
        assertEquals(RaftRole.FOLLOWER, v.raft.role(), "a latched node never self-promotes");

        x.down = false;
        p.down = false;
        c.settle(6);
        assertTrue(v.raft.votingClearedForTest(), "clears the instant a quorum is reachable - no operator action");
    }

    @Test
    void w6_tornHigherSlot_recoversIntactAndPasses(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        c.add(X, Set.of(V, P), false);
        c.add(P, Set.of(V, X), false);
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        v.raft.handleMessage(voteReq(T, X));
        long latest = v.anchorSeq();
        c.deliverAll();

        Map<String, byte[]> intact = c.snapshotDisk(v);
        c.kill(v);
        c.rollbackDisk(v, intact);               // restore the intact image (models a torn NEXT write never landing)
        c.reboot(v);
        assertEquals(latest, v.anchorSeq(), "recovery picks the intact slot's seq (dual-slot)");
        c.settle(6);
        assertNull(v.rollback, "a torn write that never lowered the recovered seq is not a rollback");
        assertTrue(v.raft.votingClearedForTest());
    }

    @Test
    void w7_conflictTruncationAndCompaction_onlyRaiseSeq_noTrip(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        c.add(X, Set.of(V, P), false);
        c.add(P, Set.of(V, X), false);
        c.settle(6);

        long before = v.anchorSeq();
        v.raft.handleMessage(new AppendEntriesRequest(1, X, 0, 0,
                List.of(new LogEntry(1, 1, "a".getBytes(StandardCharsets.UTF_8)),
                        new LogEntry(2, 1, "b".getBytes(StandardCharsets.UTF_8))), 2));
        v.raft.handleMessage(new AppendEntriesRequest(1, X, 1, 1,   // conflicting suffix at idx 2 -> truncate+rewrite
                List.of(new LogEntry(2, 1, "b2".getBytes(StandardCharsets.UTF_8))), 2));
        long after = v.anchorSeq();
        assertTrue(after >= before, "anchorSeq is monotone across a legal Raft rewrite: " + before + " -> " + after);
        c.settle(6);
        assertNull(v.rollback, "a legal truncation/compaction never trips the witness");
        assertTrue(v.raft.votingClearedForTest());
    }

    /**
     * The refuse test is {@code W > bootAnchorSeq} - STRICTLY greater. A {@code >=} would false-refuse
     * EVERY healthy node (a peer always witnesses at least the booted-from seq). Attack the exact boundary:
     * a peer reporting seenOfYou == bootAnchorSeq must PASS; == bootAnchorSeq+1 must REFUSE.
     */
    @Test
    void boundary_refuseIsStrictlyGreater_equalPasses_plusOneRefuses(@TempDir Path base) {
        // The gate only re-evaluates while LATCHED, so inject the boundary report before it clears (do NOT
        // settle the healthy cluster first - that would clear the gate and freeze it).

        // w == bootAnchorSeq -> PASS (equal is legal - a peer always witnesses at least our booted-from
        // seq). The boot gate clears on a peer-MAJORITY, so BOTH peers must report == boot.
        Cluster c1 = new Cluster(base.resolve("eq"));
        Node v1 = c1.add(V, Set.of(X, P), false);
        c1.add(X, Set.of(V, P), false);
        c1.add(P, Set.of(V, X), false);
        long boot1 = v1.anchorSeq();
        assertFalse(v1.raft.votingClearedForTest(), "V is latched at boot (not yet cleared)");
        v1.raft.handleMessage(new WitnessReply(X, boot1, 0L, -1, boot1)); // seenOfYou == boot
        v1.raft.handleMessage(new WitnessReply(P, boot1, 0L, -1, boot1)); // peer-majority: both peers report == boot
        c1.tick(v1);                                                      // evaluateBootGate: w==boot, peer-majority
        assertNull(v1.rollback, "seenOfYou == bootAnchorSeq is NOT a rollback");
        assertTrue(v1.raft.votingClearedForTest(), "the boundary w==boot PASSES (would false-refuse if it were >=)");

        // w == bootAnchorSeq + 1 -> REFUSE. The refuse fires on W > boot regardless of the responder count
        // (quorum-independent), so ONE peer reporting boot+1 is enough.
        Cluster c2 = new Cluster(base.resolve("gt"));
        Node v2 = c2.add(V, Set.of(X, P), false);
        c2.add(X, Set.of(V, P), false);
        c2.add(P, Set.of(V, X), false);
        long boot2 = v2.anchorSeq();
        v2.raft.handleMessage(new WitnessReply(X, boot2 + 1, 0L, -1, boot2 + 1)); // seenOfYou == boot+1
        c2.tick(v2);
        assertNotNull(v2.rollback, "seenOfYou == bootAnchorSeq+1 MUST refuse (a peer saw us one higher)");
        assertFalse(v2.raft.votingClearedForTest());
    }

    /**
     * W5 fail-closed asymmetry: a single (authenticated) peer lying with a bogus HIGH seenOfYou can only
     * force a SAFE refuse-and-escalate, never a false PASS. A lying LOW report cannot mask a truthful high
     * witness because the gate takes the MAX over responders.
     */
    @Test
    void w5_lyingPeerHighSeq_failsClosedRefuse_neverFalsePass(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        c.add(X, Set.of(V, P), false);
        c.add(P, Set.of(V, X), false);
        c.settle(6);
        long boot = v.anchorSeq();
        // Re-latch by rebooting so the boot gate is live again (there was no rollback).
        c.kill(v);
        c.reboot(v);

        // A compromised-but-authenticated peer X lies: it reports witnessing V far above reality.
        v.raft.handleMessage(new WitnessReply(X, boot, 0L, -1, boot + 1_000_000L));
        c.settle(2);
        assertNotNull(v.rollback, "a lying-HIGH peer forces a safe refuse (fail-closed) - never a false pass");
        assertFalse(v.raft.votingClearedForTest(), "the safe direction: refuse-and-escalate, not silent accept");
    }


    @Test
    void n1_gateDisabled_selfElects(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node solo = c.add(V, Set.of(), false);
        assertTrue(solo.raft.votingClearedForTest(), "N=1 clears immediately - a single voter cannot split-brain");
        for (int i = 0; i < 400 && solo.raft.role() != RaftRole.LEADER; i++) {
            solo.raft.tick();
            c.deliverAll();
        }
        assertEquals(RaftRole.LEADER, solo.raft.role(), "the single node still self-elects (gate disabled)");
    }

    /**
     * At N=2 the ONLY quorum is both nodes, so the default-mode self+1 shortcut collapses to "the one peer
     * must answer" - which is the witness. So N=2 default mode has NO boot-reply-race false pass: a rollback
     * is caught exactly when the single peer is available (which is already the precondition for progress).
     */
    @Test
    void n2_rollbackCaughtWhenPeerAvailable_noFalsePassRace(@TempDir Path base) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X), false);
        c.add(X, Set.of(V), false);
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        Map<String, byte[]> prior = c.snapshotDisk(v);
        v.raft.handleMessage(voteReq(T, X));
        long s1 = v.anchorSeq();
        c.deliverAll();

        c.kill(v);
        c.rollbackDisk(v, prior);
        c.reboot(v);
        assertEquals(s0, v.anchorSeq());
        c.settle(6);
        assertNotNull(v.rollback, "N=2: the single peer IS the quorum, so the rollback is caught");
        assertEquals(s1, v.rollback.witnessedSeq());
        assertFalse(v.raft.votingClearedForTest());
    }

    // advertisedAnchorSeq() freezes during the boot window: while latched, a node advertises its
    // frozen bootAnchorSeq, not its live (possibly caught-up) seq, so a healthy node replicated to
    // during its boot window is not falsely refused. Must not (a) brick a healthy caught-up node,
    // nor (b) let a real rollback hide behind a boot-window catch-up.

    /** A leader AppendEntries a booting follower applies - raises its LIVE anchorSeq above bootAnchorSeq. */
    private static AppendEntriesRequest catchUpAppend(long term, NodeId leader) {
        return new AppendEntriesRequest(term, leader, 0, 0,
                List.of(new LogEntry(1, term, "c1".getBytes(StandardCharsets.UTF_8)),
                        new LogEntry(2, term, "c2".getBytes(StandardCharsets.UTF_8))), 2);
    }

    @Test
    void brickFix_catchUpDuringBoot_healthyNode_notFalseRefused_default(@TempDir Path base) {
        catchUpDuringBoot_notFalseRefused(base, false);
    }

    @Test
    void brickFix_catchUpDuringBoot_healthyNode_notFalseRefused_strict(@TempDir Path base) {
        catchUpDuringBoot_notFalseRefused(base, true);
    }

    private void catchUpDuringBoot_notFalseRefused(Path base, boolean strict) {
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), strict);
        c.add(X, Set.of(V, P), strict);
        c.add(P, Set.of(V, X), strict);
        long boot = v.anchorSeq();
        assertFalse(v.raft.votingClearedForTest(), "V is latched at boot (fresh, armed, N>1)");

        // Caught up DURING the boot window: a leader AppendEntries (ungated) raises V's LIVE seq above boot,
        // BEFORE the gate clears. Pre-fix V advertised that live seq -> peers witnessed W>boot -> false-refuse.
        v.raft.handleMessage(catchUpAppend(1, X));
        long live = v.anchorSeq();
        assertTrue(live > boot, "catch-up raised the live seq above bootAnchorSeq: boot=" + boot + " live=" + live);
        assertFalse(v.raft.votingClearedForTest(), "the gate has not run yet - still latched while caught up");

        // Force the exact interleaving the brick needs: V's QUERY reaches the peers (they witness whatever V
        // ADVERTISES) and their replies carry that value back to V BEFORE the gate forms its first quorum. A
        // plain settle() would instead clear the gate on the peers' initial zero-valued QUERY exchange before
        // the caught-up value ever propagates back, masking whether the freeze is even load-bearing.
        c.tick(v);                            // V broadcasts a QUERY advertising (frozen boot) / (live if defeated)
        c.deliver(e -> e.from().equals(V));   // peers witness V at its advertised seq...
        c.deliver(e -> e.to().equals(V));     // ...and reply that seq back to V (peerAckOfSelf = advertised)
        c.tick(v);                            // evaluateBootGate now sees the peers' report of V's advertised seq

        // With the FREEZE, the advertised seq is the frozen `boot` -> W == boot -> PASS. Defeating the freeze
        // (advertise `live`) makes W == live > boot -> the brick (false refuse) returns.
        assertNull(v.rollback,
                "BRICK FIX: a healthy caught-up node must NOT be false-refused (strict=" + strict + ")");
        assertTrue(v.raft.votingClearedForTest(), "the caught-up healthy node clears normally");
    }

    @Test
    void bootGate_peerMajorityRequired_onePeerUpStaysLatched_thenClearsWhenBothReachable(@TempDir Path base) {
        // The unconditional peer-majority boot gate's ONLY cost (N=3): a node rebooting while ONE peer is
        // down cannot reach a peer-majority (peerMajority(2)=2), so it stays LATCHED (refuse-to-vote, the
        // SAFE direction) - it does NOT brick, and clears the instant BOTH peers are reachable. Making the
        // gate stricter can only WITHHOLD a clear, never manufacture a false-pass, so it introduces no new
        // security hole; this pins the fail-closed availability trade.
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), false);
        Node p = c.add(P, Set.of(V, X), false);
        c.add(X, Set.of(V, P), false);

        p.down = true;
        c.settle(6);
        assertNull(v.rollback, "no rollback - just an unreachable peer");
        assertFalse(v.raft.votingClearedForTest(),
                "self + one peer is NOT a peer-majority -> V stays latched (availability cost, safe direction)");
        assertEquals(RaftRole.FOLLOWER, v.raft.role(), "a latched node never self-promotes");

        p.down = false;
        c.settle(6);
        assertTrue(v.raft.votingClearedForTest(),
                "clears once BOTH peers (the peer-majority at N=3) are reachable - no brick, no operator action");
    }

    @Test
    void newHole_rollbackWithCatchUpDuringBoot_stillRefused_strict(@TempDir Path base) {
        // Production default (strict). The freeze must NOT let a genuine rollback hide behind a boot-window
        // catch-up: the peers' memory of the pre-crash s1 predates the reboot and monotone-max persists, so
        // advertising the lower frozen s0 cannot erase it.
        Cluster c = new Cluster(base);
        Node v = c.add(V, Set.of(X, P), true);
        c.add(X, Set.of(V, P), true);
        c.add(P, Set.of(V, X), true);
        c.settle(8);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        Map<String, byte[]> prior = c.snapshotDisk(v);
        v.raft.handleMessage(voteReq(T, X));
        long s1 = v.anchorSeq();
        c.deliverAll();                              // BOTH peers witness s1 (pre-crash, in-memory)

        c.kill(v);
        c.rollbackDisk(v, prior);
        c.reboot(v);
        assertEquals(s0, v.anchorSeq(), "V booted from the rolled-back s0");

        // Adversary tries to MASK the rollback: catch V up during boot so its LIVE seq climbs back above s1.
        v.raft.handleMessage(catchUpAppend(T + 5, X));
        assertTrue(v.anchorSeq() > s1, "catch-up climbed the live seq back above s1");
        assertFalse(v.raft.votingClearedForTest(), "still latched while caught up");

        c.settle(8);
        assertNotNull(v.rollback,
                "NEW-HOLE HUNT: the freeze does NOT hide a real rollback - peers' pre-crash s1 memory persists");
        assertEquals(s1, v.rollback.witnessedSeq(),
                "the witnessed floor is the pre-rollback s1, not the frozen s0 nor the caught-up live seq");
        assertFalse(v.raft.votingClearedForTest());
        v.sent.clear();
        v.raft.handleMessage(voteReq(T + 5, P));
        assertFalse(v.grantedVoteTo(P), "split-brain stays BLOCKED despite the boot-window catch-up");
    }
}
