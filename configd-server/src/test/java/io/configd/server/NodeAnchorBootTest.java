package io.configd.server;

import io.configd.api.AuditLog;
import io.configd.common.Clock;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.Storage;
import io.configd.raft.NodeAnchorFile;
import io.configd.raft.NodeAnchorRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeAnchorBootTest {

    private static final long EPOCH = 5L;

    private static IntegrityEnvelope keyed() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 1);
        }
        return new IntegrityEnvelope(new SecretKeySpec(key, "HmacSHA256"));
    }

    private static Map<Integer, Long> durable(int shardCount, long... indexes) {
        Map<Integer, Long> m = new java.util.LinkedHashMap<>();
        for (int gid = 0; gid < shardCount; gid++) {
            m.put(gid, indexes[gid]);
        }
        return m;
    }

    private static NodeAnchorFile mint(Path dir, IntegrityEnvelope env, long epoch, int n,
            Map<Integer, Long> boot, AuditLog auditLog) {
        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(
                dir, env, epoch, n, boot, Set.of(), auditLog);
        na.close(); // persist + release; a later enforce re-opens it
        return na;
    }


    @Test
    void firstBootMintsTheNodeAnchor(@TempDir Path dir) {
        IntegrityEnvelope env = keyed();
        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(
                dir, env, EPOCH, 2, durable(2, 100, 200), Set.of(), null);
        NodeAnchorRecord r = na.current();
        assertEquals(1L, r.nodeAnchorSeq(), "first boot mints seq 1");
        assertEquals(EPOCH, r.topologyEpoch());
        assertEquals(2, r.shardCount());
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(durable(2, 100, 200)),
                r.shardAnchorDigest());
        na.close();
    }

    @Test
    void firstBootWithAllShardsFreshMintsAndDoesNotTripTheWipeBranch(@TempDir Path dir) {
        // First node boot: the node-anchor is ABSENT and EVERY shard bootstraps FRESH together (all
        // lastDurableIndex 0). The wipe-detection branch requires the node-anchor to EXIST, so an absent
        // node-anchor takes the mint path - it must NEVER be mistaken for a wipe. This is the boundary
        // that separates "brand-new node" from "an initialized node whose shard was wiped".
        IntegrityEnvelope env = keyed();
        Set<Integer> allFresh = new HashSet<>(Set.of(0, 1, 2));
        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(
                dir, env, EPOCH, 3, durable(3, 0, 0, 0), allFresh, null);
        assertEquals(1L, na.current().nodeAnchorSeq(), "first boot mints even when all shards are FRESH");
        assertEquals(3, na.current().shardCount());
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(durable(3, 0, 0, 0)),
                na.current().shardAnchorDigest());
        na.close();
    }

    @Test
    void cleanSecondBootProceedsWithoutReanchor(@TempDir Path dir) {
        IntegrityEnvelope env = keyed();
        Map<Integer, Long> boot = durable(2, 100, 200);
        mint(dir, env, EPOCH, 2, boot, null);

        NodeAnchorFile reopened = NodeAnchorService.enforceNodeAnchor(
                dir, env, EPOCH, 2, boot, Set.of(), null);
        assertEquals(1L, reopened.current().nodeAnchorSeq(), "a clean boot must not churn the node-anchor");
        reopened.close();
    }

    @Test
    void topologyEpochRollbackRefuses(@TempDir Path dir) {
        IntegrityEnvelope env = keyed();
        Map<Integer, Long> boot = durable(2, 100, 200);
        mint(dir, env, EPOCH, 2, boot, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dir, env, EPOCH + 1, 2, boot, Set.of(), null));
        assertTrue(ex.getMessage().contains("topology"), ex.getMessage());
    }

    @Test
    void shardCountMismatchRefuses(@TempDir Path dir) {
        IntegrityEnvelope env = keyed();
        mint(dir, env, EPOCH, 2, durable(2, 100, 200), null);

        // A reshard to N=3 (a third shard appears). Topology binds N=2 -> REFUSE.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(
                        dir, env, EPOCH, 3, durable(3, 100, 200, 0), Set.of(), null));
        assertTrue(ex.getMessage().contains("topology"), ex.getMessage());
    }

    @Test
    void shardWipedToFreshRefuses_Rf(@TempDir Path dir) {
        // FULL wipe: shard 1's raft-anchor + WAL + snapshot all deleted, so it boots FRESH (anchor
        // ABSENT, head reset to 0). The node-anchor EXISTS (node was initialized) => digest differs AND
        // a shard is FRESH => the wipe signature => REFUSE.
        //
        // The PARTIAL wipe (anchor deleted but WAL/snapshot intact => the shard dir is NON-empty) never
        // reaches this digest branch: RaftLog's per-shard presence gate (recoverWithAnchor) throws
        // "anchor was deleted" during buildRaftGroup, so the bring-up loop fails BEFORE enforceNodeAnchor
        // runs. Proven by RaftAnchorRecoveryTest.deletedAnchorOverNonEmptyShardRefuses.
        IntegrityEnvelope env = keyed();
        mint(dir, env, EPOCH, 2, durable(2, 100, 200), null);

        Set<Integer> fresh = new HashSet<>(Set.of(1));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(
                        dir, env, EPOCH, 2, durable(2, 100, 0), fresh, null));
        assertTrue(ex.getMessage().contains("R-f") || ex.getMessage().contains("shard-liveness"),
                ex.getMessage());
    }

    @Test
    void forwardAdvanceAcceptForwardReanchors_NoFalseRefuse(@TempDir Path dir) {
        IntegrityEnvelope env = keyed();
        mint(dir, env, EPOCH, 2, durable(2, 100, 200), null);

        // Both shards advanced since the last node-anchor tick (a legal crash restart under load). No
        // shard is FRESH. Must NOT refuse - accept-forward and re-anchor the new digest.
        Map<Integer, Long> advanced = durable(2, 150, 260);
        NodeAnchorFile reopened = NodeAnchorService.enforceNodeAnchor(
                dir, env, EPOCH, 2, advanced, Set.of(), null);
        assertEquals(2L, reopened.current().nodeAnchorSeq(), "accept-forward re-anchors (seq advances)");
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(advanced),
                reopened.current().shardAnchorDigest(), "the re-anchor binds the current liveness digest");
        reopened.close();

        // And the re-anchored digest is durable: a subsequent clean boot at the advanced state proceeds.
        NodeAnchorFile third = NodeAnchorService.enforceNodeAnchor(
                dir, env, EPOCH, 2, advanced, Set.of(), null);
        assertEquals(2L, third.current().nodeAnchorSeq(), "the advanced digest persisted; no further churn");
        third.close();
    }

    @Test
    void n1CrashRestartUnderLoadAcceptForward_NoBrickOnLegalCrash(@TempDir Path dir) {
        // THE load-bearing case (the one a literal "any digest change => REFUSE" would brick). At the
        // N=1 default, the single shard's lastDurableIndex advances between node-anchor ticks; a crash
        // restart recomputes a DIFFERENT digest with NO shard FRESH. This is a legal crash, so it must
        // accept-forward and PROCEED, never REFUSE - a boot cross-check must never brick a healthy node
        // for having simply made progress.
        IntegrityEnvelope env = keyed();
        mint(dir, env, EPOCH, 1, durable(1, 100), null);

        Map<Integer, Long> advanced = durable(1, 137);
        NodeAnchorFile reopened = NodeAnchorService.enforceNodeAnchor(
                dir, env, EPOCH, 1, advanced, Set.of(), null);
        assertEquals(2L, reopened.current().nodeAnchorSeq(),
                "an N=1 crash restart with an advanced head must accept-forward, NOT brick");
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(advanced),
                reopened.current().shardAnchorDigest());
        reopened.close();
    }

    @Test
    void bothSlotsInvalidRefuses(@TempDir Path dir) throws Exception {
        IntegrityEnvelope env = keyed();
        mint(dir, env, EPOCH, 1, durable(1, 42), null);

        // Corrupt both slots so neither authenticates -> present-but-invalid -> REFUSE (tamper).
        Path file = dir.resolve(NodeAnchorFile.NODE_ANCHOR_FILE_NAME);
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        bytes[8 + 4 + 20] ^= 0x5A;          // inside slot 0's envelope
        bytes[8 + 512 + 4 + 20] ^= 0x5A;    // inside slot 1's envelope
        java.nio.file.Files.write(file, bytes, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dir, env, EPOCH, 1, durable(1, 42), Set.of(), null));
        assertTrue(ex.getMessage().contains("both slots invalid"), ex.getMessage());
    }


    private static AuditLog newAuditLog(Storage storage) {
        byte[] auditKey = new byte[32];
        for (int i = 0; i < auditKey.length; i++) {
            auditKey[i] = (byte) (i * 3 + 5);
        }
        return new AuditLog(storage, Clock.system(), new SecretKeySpec(auditKey, "HmacSHA256"));
    }

    @Test
    void auditHeadReachableProceeds_UnanchoredTailIsResidualRe(@TempDir Path dir) {
        IntegrityEnvelope env = keyed();
        Storage storage = Storage.inMemory();
        AuditLog auditLog = newAuditLog(storage);
        for (int i = 0; i < 3; i++) {
            auditLog.record("alice", "PUT", "k" + i, "committed");
        }
        Map<Integer, Long> boot = durable(1, 10);
        mint(dir, env, EPOCH, 1, boot, auditLog); // anchors head = 3rd record

        // Two more records land AFTER the anchor (the un-anchored tail).
        auditLog.record("bob", "DELETE", "k9", "committed");
        auditLog.record("bob", "PUT", "k10", "committed");

        // The anchored head is still present in the persisted chain -> proceed (the tail is allowed).
        NodeAnchorFile reopened = NodeAnchorService.enforceNodeAnchor(
                dir, env, EPOCH, 1, boot, Set.of(), auditLog);
        assertTrue(reopened.hasValidRecord());
        reopened.close();
    }

    @Test
    void auditChainTruncatedBelowAnchoredHeadRefuses(@TempDir Path dir) {
        IntegrityEnvelope env = keyed();
        Storage storage = Storage.inMemory();
        AuditLog auditLog = newAuditLog(storage);
        for (int i = 0; i < 5; i++) {
            auditLog.record("alice", "PUT", "k" + i, "committed");
        }
        Map<Integer, Long> boot = durable(1, 10);
        mint(dir, env, EPOCH, 1, boot, auditLog); // anchors head = 5th record

        // Truncate the persisted audit log to only the first 3 records (drops the anchored 5th head).
        List<byte[]> raw = storage.readLog(AuditLog.LOG_NAME);
        storage.truncateLog(AuditLog.LOG_NAME);
        for (int i = 0; i < 3; i++) {
            storage.appendToLog(AuditLog.LOG_NAME, raw.get(i));
        }
        storage.sync();

        AuditLog reopenedAudit = newAuditLog(storage);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dir, env, EPOCH, 1, boot, Set.of(), reopenedAudit));
        assertTrue(ex.getMessage().contains("audit-head"), ex.getMessage());
    }


    @Test
    void periodicRefreshAdvancesTheAnchorOnTheCadence(@TempDir Path dir) {
        IntegrityEnvelope env = keyed();
        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(
                dir, env, EPOCH, 1, durable(1, 0), Set.of(), null);
        assertEquals(1L, na.current().nodeAnchorSeq());

        Map<Integer, Long> live = durable(1, 500);
        Runnable refresh = NodeAnchorService.newRefresher(na, null, () -> live, 60_000L, 64);

        // First run is always due (lastWriteMs == 0): it binds the current digest and advances the seq.
        refresh.run();
        assertEquals(2L, na.current().nodeAnchorSeq(), "the first refresh advances the node-anchor");
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(live),
                na.current().shardAnchorDigest(), "the refresh binds the live shard-liveness digest");

        // An immediate second run is NOT due (well within the 60s interval, audit count unchanged).
        refresh.run();
        assertEquals(2L, na.current().nodeAnchorSeq(), "a not-due refresh must not churn the node-anchor");

        // A skipped digest source (a busy owner) is a no-op off the ack path: never an advance or throw.
        Runnable skip = NodeAnchorService.newRefresher(na, null, () -> null, 0L, 1);
        skip.run();
        assertEquals(2L, na.current().nodeAnchorSeq(), "a null digest source skips (retry next tick)");
        na.close();
    }
}
