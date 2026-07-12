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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-attack proof of the peer-quorum {@link AnchorWitness}. Every test stands up file-backed
 * {@link RaftNode}s over a delivering in-memory network, arms the witness, and - for the headline -
 * performs a genuine on-disk anchor rollback (restoring a captured earlier durable image) and reboots,
 * asserting the boot gate refuses so the victim can never cast a second, conflicting vote at the same
 * term. The other cases assert the gate does not brick a healthy node on a legal crash or a legal Raft
 * transition. See {@code docs/architecture/anchor-witness-peer-quorum.md} section 6.2.
 */
class AnchorWitnessPeerQuorumTest {

    private static final NodeId V = NodeId.of(1);
    private static final NodeId X = NodeId.of(2);
    private static final NodeId P = NodeId.of(3);

    private static IntegrityEnvelope keyed() {
        return SnapshotIntegrityTest.keyedEnvelope();
    }

    // Test harness: N file-backed nodes on a shared, queue-driven "network".

    /** A captured rollback detection from a node's fail-closed handler. */
    private record Rollback(int gid, long bootAnchorSeq, long witnessedSeq, NodeId reporter) {}

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
        /** Everything this node sent, in order (target + message), for grant/deny assertions. */
        final List<Sent> sent = new ArrayList<>();
        /** Non-null once this node's boot gate refused (recording, non-halting handler). */
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
    }

    private record Sent(NodeId target, RaftMessage message) {}

    private record Env(NodeId from, NodeId to, RaftMessage msg) {}

    private static final class Cluster {
        final Path base;
        final boolean strict;
        final Map<NodeId, Node> nodes = new LinkedHashMap<>();
        final Deque<Env> wire = new ArrayDeque<>();

        Cluster(Path base, boolean strict) {
            this.base = base;
            this.strict = strict;
        }

        Node add(NodeId id, Set<NodeId> peers) {
            Node n = new Node(id, peers, base.resolve("node-" + id.id()), this, strict);
            nodes.put(id, n);
            return n;
        }

        /** One tick on every live node (dead nodes are skipped, modelling a crash / partition). */
        void tickAll() {
            for (Node n : nodes.values()) {
                if (!n.down) {
                    n.raft.tick();
                }
            }
        }

        /** Deliver exactly the frames queued at call time; replies enqueued now are delivered next round. */
        void pump() {
            int n = wire.size();
            for (int i = 0; i < n; i++) {
                Env e = wire.pollFirst();
                Node target = nodes.get(e.to());
                if (target != null && !target.down) {
                    target.raft.handleMessage(e.msg());
                }
            }
        }

        /** tickAll + pump, {@code rounds} times - drives the boot gate / cadence to a fixed point. */
        void settle(int rounds) {
            for (int i = 0; i < rounds; i++) {
                tickAll();
                pump();
            }
        }

        /** Discards all in-flight frames - models a crash that loses undelivered sends. */
        void dropWire() {
            wire.clear();
        }

        /** Captures the full durable on-disk image of a node (a genuine authenticated prior state). */
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

        /** Kills a node: releases its anchor handle and stops ticking/delivering to it. */
        void kill(Node n) {
            n.log.closeAnchor();
            n.down = true;
        }

        /** Overwrites a killed node's data dir with a captured earlier image (the real byte rollback). */
        void rollbackDisk(Node n, Map<String, byte[]> image) {
            try {
                for (Map.Entry<String, byte[]> e : image.entrySet()) {
                    Files.write(n.dir.resolve(e.getKey()), e.getValue());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** Reboots a killed node from whatever bytes are now on disk (fresh RaftLog/RaftNode, re-armed). */
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

    // The headline: a double vote blocked by a real on-disk rollback + reboot (N=3).

    @Test
    void headline_raPrimeDoubleVoteBlocked_realDiskRollback(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node v = c.add(V, Set.of(X, P));
        c.add(X, Set.of(V, P));
        c.add(P, Set.of(V, X));

        // Boot gates clear across the healthy cluster (QUERY -> quorum of replies).
        c.settle(6);
        assertTrue(v.raft.votingClearedForTest(), "V must clear its boot gate in a healthy cluster");

        // Step V to term T with votedFor=null (a heartbeat from X). This is the genuine {T, null, s0}
        // image the adversary will later restore.
        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        Map<String, byte[]> priorImage = c.snapshotDisk(v);

        // V grants a vote to X at T: persist raises anchorSeq to s1, then announce-before-grant broadcasts
        // s1 to the peers BEFORE voteGranted is sent.
        v.raft.handleMessage(voteReq(T, X));
        long s1 = v.anchorSeq();
        assertTrue(s1 > s0, "granting a vote must raise anchorSeq: s0=" + s0 + " s1=" + s1);
        assertEquals(X, v.raft.votedFor(), "V granted its term-" + T + " vote to X");
        assertTrue(v.grantedVoteTo(X), "V sent voteGranted=true to X");

        // Deliver V's announce(s1) to the peers - they now witness V at s1 (in-memory, survives V's crash).
        c.pump();

        // The adversary owns V's disk: while V is down, restore the genuine earlier {T, null, s0} image.
        c.kill(v);
        c.rollbackDisk(v, priorImage);
        c.reboot(v);
        assertEquals(s0, v.anchorSeq(), "V rebooted from the rolled-back anchorSeq s0");
        assertNull(v.raft.votedFor(), "the rolled-back image restores votedFor=null (the double-vote setup)");

        // V boots, QUERYs, a peer reports witnessing s1 > s0 -> boot gate REFUSES.
        c.settle(6);
        assertNotNull(v.rollback, "boot gate must detect the rollback");
        assertEquals(s0, v.rollback.bootAnchorSeq());
        assertEquals(s1, v.rollback.witnessedSeq(), "the peer-witnessed floor is the pre-rollback s1");
        assertFalse(v.raft.votingClearedForTest(), "V stays latched after a detected rollback");

        // The double vote: a DIFFERENT candidate Y=P now asks V for a vote at the SAME term T. Its log is
        // up to date, so ONLY the witness latch can stop the grant.
        v.sent.clear();
        v.raft.handleMessage(voteReq(T, P));
        assertFalse(v.grantedVoteTo(P),
                "V must NOT grant a second, conflicting vote at term " + T + " - split-brain blocked");
    }

    // 2. Grant -> announce crash race: a crash before the announce+voteGranted leaves the vote UNUSABLE.

    @Test
    void grantAnnounceCrashRace_unusableVoteHasNoDoubleUse(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node v = c.add(V, Set.of(X, P));
        Node x = c.add(X, Set.of(V, P));
        c.add(P, Set.of(V, X));
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        Map<String, byte[]> priorImage = c.snapshotDisk(v);

        // V grants X: vote persisted (s1), announce + voteGranted ENQUEUED. Then V crashes BEFORE any of
        // them is delivered (dropWire) - so X never counted V's vote and no peer witnessed s1.
        v.raft.handleMessage(voteReq(T, X));
        assertTrue(v.anchorSeq() > s0);
        c.dropWire();
        assertFalse(x.grantedVoteTo(X), "sanity: X is not the one granting");
        boolean xSawVoteFromV = x.sent.isEmpty()
                || x.raft.votedFor() == null; // X never received V's voteGranted
        assertTrue(xSawVoteFromV, "X never counted V's (never-delivered) vote");

        // Roll back + reboot. The peers never witnessed s1, so the boot gate PASSES - but that is safe,
        // because V's first vote was never usable (never delivered). V casts a single usable vote for Y.
        c.kill(v);
        c.rollbackDisk(v, priorImage);
        c.reboot(v);
        c.settle(6);
        assertNull(v.rollback, "no peer witnessed s1, so the gate correctly does not refuse");
        assertTrue(v.raft.votingClearedForTest(), "V clears and may cast its single usable vote");
        v.raft.handleMessage(voteReq(T, P));
        assertTrue(v.grantedVoteTo(P), "V's ONE usable vote goes to Y - the earlier vote was never used");
    }

    // 3. W1 - legit reboot, no rollback: PASSES and votes (no false positive).

    @Test
    void w1_legitReboot_passesAndVotes(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node v = c.add(V, Set.of(X, P));
        c.add(X, Set.of(V, P));
        c.add(P, Set.of(V, X));
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        v.raft.handleMessage(voteReq(T, X));
        c.pump();

        // Clean crash + reboot from the SAME (latest) bytes - no rollback.
        c.kill(v);
        c.reboot(v);
        c.settle(6);
        assertNull(v.rollback, "a legit reboot from the latest anchor must not be flagged");
        assertTrue(v.raft.votingClearedForTest(), "V clears its boot gate on a clean reboot");
        // It re-votes normally at a new term (votedFor recovered as X at T; a fresh term T+1 clears it).
        v.raft.handleMessage(voteReq(T + 1, P));
        assertTrue(v.grantedVoteTo(P), "after a clean reboot V votes normally");
    }

    // 4. W2 - advanced past peers (local ahead of the witnessed floor): PASSES (writes lead witnessing).

    @Test
    void w2_advancedPastPeers_passes(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node v = c.add(V, Set.of(X, P));
        c.add(X, Set.of(V, P));
        c.add(P, Set.of(V, X));
        c.settle(6);

        // V writes s1 (a vote) but the announce is DROPPED before any peer sees it (partition at the moment
        // of the write). No rollback - V is simply ahead of what peers witnessed.
        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        v.raft.handleMessage(voteReq(T, X));
        long s1 = v.anchorSeq();
        assertTrue(s1 > s0);
        c.dropWire(); // the announce never lands anywhere

        // Clean reboot with peers reachable. Peers' seenOfYou <= s0 < s1 = bootAnchorSeq -> W < s -> PASS.
        c.kill(v);
        c.reboot(v);
        c.settle(6);
        assertNull(v.rollback, "being AHEAD of the witnessed floor is legal, never a rollback");
        assertTrue(v.raft.votingClearedForTest(), "an advanced-past-peers node still clears");
    }

    // 5. W4 - partition at boot: refuse-to-vote (no brick), then heals when the quorum returns.

    @Test
    void w4_partitionAtBoot_noBrick_thenHeals(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node v = c.add(V, Set.of(X, P));
        Node x = c.add(X, Set.of(V, P));
        Node p = c.add(P, Set.of(V, X));

        // Partition V from both peers at boot: its QUERYs reach no one.
        x.down = true;
        p.down = true;
        c.settle(6);
        assertNull(v.rollback, "a partition is not a rollback");
        assertFalse(v.raft.votingClearedForTest(), "V stays latched (refuse-to-vote) with no quorum - NOT bricked");

        // Heal: peers return, the QUERY quorum forms, V clears - no operator action, no brick.
        x.down = false;
        p.down = false;
        c.settle(6);
        assertTrue(v.raft.votingClearedForTest(), "V clears the instant a quorum becomes reachable");
    }

    // 6. W6 - torn higher slot on crash: dual-slot recovery picks the intact slot; the witness PASSES.

    @Test
    void w6_tornHigherSlot_recoversIntactAndPasses(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node v = c.add(V, Set.of(X, P));
        c.add(X, Set.of(V, P));
        c.add(P, Set.of(V, X));
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        v.raft.handleMessage(voteReq(T, X)); // writes the latest slot; peers witness this seq
        long latest = v.anchorSeq();
        c.pump();

        // Capture the current (intact) durable image, then simulate a torn NEXT write by rebooting from
        // exactly these bytes: recovery reads the highest VALID slot = `latest`, which is what peers saw.
        Map<String, byte[]> intact = c.snapshotDisk(v);
        c.kill(v);
        c.rollbackDisk(v, intact); // restore the intact image (models the torn write never landing)
        c.reboot(v);
        assertEquals(latest, v.anchorSeq(), "recovery picks the intact slot's seq");
        c.settle(6);
        assertNull(v.rollback, "a torn write that never lowered the recovered seq is not a rollback");
        assertTrue(v.raft.votingClearedForTest());
    }

    // 7. W7 - legal conflict truncation / compaction only RAISES anchorSeq: the witness never trips.

    @Test
    void w7_truncationAndCompaction_onlyRaiseSeq_noTrip(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node v = c.add(V, Set.of(X, P));
        c.add(X, Set.of(V, P));
        c.add(P, Set.of(V, X));
        c.settle(6);

        long before = v.anchorSeq();
        // A follower conflict truncation (a legal Raft rewrite) followed by a compaction. Both write the
        // anchor, so anchorSeq only ever RISES; the witness (which compares anchorSeq) cannot trip.
        v.raft.handleMessage(new AppendEntriesRequest(1, X, 0, 0,
                List.of(new LogEntry(1, 1, "a".getBytes(StandardCharsets.UTF_8)),
                        new LogEntry(2, 1, "b".getBytes(StandardCharsets.UTF_8))), 2));
        // conflicting suffix at index 2 - truncate + rewrite
        v.raft.handleMessage(new AppendEntriesRequest(1, X, 1, 1,
                List.of(new LogEntry(2, 1, "b2".getBytes(StandardCharsets.UTF_8))), 2));
        long after = v.anchorSeq();
        assertTrue(after >= before, "anchorSeq is monotone across legal Raft rewrites: " + before + " -> " + after);
        c.settle(6);
        assertNull(v.rollback, "a legal truncation/compaction never trips the witness");
        assertTrue(v.raft.votingClearedForTest());
    }

    // 8. Strict mode - a granted vote is unusable until a peer-majority acks the announce.

    @Test
    void strictMode_voteDeferredUntilPeerMajorityAcks(@TempDir Path base) {
        Cluster c = new Cluster(base, true); // strict
        Node v = c.add(V, Set.of(X, P));
        Node x = c.add(X, Set.of(V, P));
        Node p = c.add(P, Set.of(V, X));
        c.settle(8); // strict boot gate needs a peer-majority of replies
        assertTrue(v.raft.votingClearedForTest(), "strict boot gate clears on a peer-majority of replies");

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        // V decides to grant X, but strict mode must NOT send voteGranted before a peer-majority acks.
        v.raft.handleMessage(voteReq(T, X));
        assertEquals(X, v.raft.votedFor(), "the vote is persisted immediately (durability unchanged)");
        assertFalse(v.grantedVoteTo(X), "strict mode defers voteGranted until peers ack the announce");

        // Deliver V's announce(s1) to the peers; they now witness V at s1.
        c.pump();
        // ONE peer acks its witness back to V: a peer-majority (of 2 peers) needs BOTH, so one is not enough.
        x.raft.witnessAnnounce();
        c.pump();
        assertFalse(v.grantedVoteTo(X), "a single peer ack is below the peer-majority - still deferred");
        // The second peer acks: now a peer-majority has witnessed s1, so the deferred voteGranted is sent.
        p.raft.witnessAnnounce();
        c.pump();
        assertTrue(v.grantedVoteTo(X), "once a peer-majority acked s1, the deferred voteGranted is released");
    }

    // 9. N=1 - the gate is disabled (a single voter cannot split-brain); it self-elects normally.

    @Test
    void n1_gateDisabled_selfElectsNormally(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node solo = c.add(V, Set.of()); // no peers
        assertTrue(solo.raft.votingClearedForTest(), "N=1 clears immediately - no peer can witness, none needs to");
        // It elects itself with no witness traffic at all.
        for (int i = 0; i < 400 && solo.raft.role() != RaftRole.LEADER; i++) {
            solo.raft.tick();
            c.pump();
        }
        assertEquals(RaftRole.LEADER, solo.raft.role(), "the single node still becomes leader (gate disabled)");
    }

    // 9b. NO-FALSE-REFUSE (rolling restart): a HEALTHY node that CATCHES UP (replication advances its
    //     anchorSeq) DURING its boot window - before the gate clears - must PASS, not brick. While
    //     latched a node advertises its FROZEN bootAnchorSeq, so its own legitimate post-boot advance
    //     is not reflected back by peers as a phantom rollback.

    @Test
    void catchUpDuringBoot_healthyNode_notFalseRefused(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node v = c.add(V, Set.of(X, P));
        c.add(X, Set.of(V, P));
        c.add(P, Set.of(V, X));

        // All three are latched at boot. BEFORE the gate clears, the leader replicates to V: a higher-term
        // AppendEntries with an entry advances V's durable head, raising anchorSeq above bootAnchorSeq.
        // This is ordinary catch-up, NOT a rollback.
        long boot = v.anchorSeq();
        v.raft.handleMessage(new AppendEntriesRequest(2, X, 0, 0,
                List.of(new LogEntry(1, 2, "x".getBytes(StandardCharsets.UTF_8))), 1));
        long caughtUp = v.anchorSeq();
        assertTrue(caughtUp > boot, "catch-up advanced anchorSeq " + boot + " -> " + caughtUp);

        // While latched, V advertises its FROZEN bootAnchorSeq (not the caught-up value), so peers do not
        // witness it above bootAnchorSeq and reflect a phantom rollback. The peer-majority boot gate then
        // clears cleanly.
        c.settle(6);
        assertNull(v.rollback, "a healthy node catching up during boot must NOT be flagged as a rollback");
        assertTrue(v.raft.votingClearedForTest(),
                "the caught-up healthy node clears its boot gate (no brick on rolling restart)");
    }

    // Default (fast-vote) failover: a running survivor grants a vote immediately with a peer down, so a
    //     leader can be re-elected on a single fault. Strict-vote mode defers voteGranted until a
    //     peer-majority ack, which would deadlock a failover with a peer down.

    @Test
    void defaultFastVote_survivorGrantsImmediatelyWithPeerDown_failoverWorks(@TempDir Path base) {
        Cluster c = new Cluster(base, false); // fast-vote default (strictVote=false)
        Node v = c.add(V, Set.of(X, P));
        c.add(X, Set.of(V, P));
        Node p = c.add(P, Set.of(V, X));
        c.settle(6);
        assertTrue(v.raft.votingClearedForTest(), "the running survivor cleared its boot gate while healthy");

        // The leader P is killed. A survivor (X) campaigns at a new term; V must grant IMMEDIATELY even
        // though P is down - fast-vote does NOT defer voteGranted, so failover is not blocked on the dead
        // peer. (A running survivor's boot gate already cleared, so it is unaffected by the peer-majority
        // BOOT rule.)
        p.down = true;
        v.sent.clear();
        v.raft.handleMessage(voteReq(5, X));
        assertTrue(v.grantedVoteTo(X),
                "fast-vote default: a survivor grants immediately with a peer down (single-fault failover works)");
    }

    // 10. DEFAULT handler halts fail-closed: it throws AnchorRollbackException with the right fields.

    @Test
    void defaultHandler_throwsAnchorRollbackException(@TempDir Path base) {
        Cluster c = new Cluster(base, false);
        Node v = c.add(V, Set.of(X, P));
        c.add(X, Set.of(V, P));
        c.add(P, Set.of(V, X));
        c.settle(6);

        long T = 1;
        v.raft.handleMessage(heartbeat(T, X));
        long s0 = v.anchorSeq();
        Map<String, byte[]> priorImage = c.snapshotDisk(v);
        v.raft.handleMessage(voteReq(T, X));
        long s1 = v.anchorSeq();
        c.pump();

        c.kill(v);
        c.rollbackDisk(v, priorImage);
        // Reboot WITHOUT the recording handler - use the production DEFAULT (throws).
        v.down = false;
        v.storage = Storage.file(v.dir);
        v.log = new RaftLog(v.storage, keyed(), 0);
        RaftTransport transport = (target, message) -> c.wire.addLast(new Env(v.id, target, message));
        v.raft = new RaftNode(RaftConfig.of(v.id, v.peers),
                v.log, transport, new NoopStateMachine(), new Random(99),
                v.storage, RaftNode.InvariantChecker.NOOP, keyed());
        v.raft.armAnchorWitness(false, null); // null handler keeps the DEFAULT (throws)

        // Drive a query round so a peer reports s1, then the next tick fires the DEFAULT handler.
        v.raft.tick();
        c.pump();
        RaftNode.AnchorRollbackException ex = assertThrows(RaftNode.AnchorRollbackException.class,
                () -> { for (int i = 0; i < 4; i++) { v.raft.tick(); c.pump(); } });
        assertEquals(s0, ex.bootAnchorSeq());
        assertEquals(s1, ex.witnessedSeq());
    }
}
