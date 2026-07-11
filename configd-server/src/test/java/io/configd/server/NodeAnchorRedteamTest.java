package io.configd.server;

import io.configd.api.AuditLog;
import io.configd.common.Clock;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.Storage;
import io.configd.raft.LogEntry;
import io.configd.raft.NodeAnchorFile;
import io.configd.raft.NodeAnchorRecord;
import io.configd.raft.RaftLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-TEAM lane for the node-anchor boot cross-check. Every guarantee here is exercised by
 * PERFORMING the attack on ACTUAL on-disk bytes and asserting
 * {@link NodeAnchorService#enforceNodeAnchor} DETECTS-and-REFUSES it - AND that a legal crash does
 * NOT false-refuse (a spurious REFUSE bricks a healthy node; equally serious).
 *
 * <p>Unlike {@code NodeAnchorBootTest} (which hand-feeds {@code bootDurableIndex}/{@code freshShards}
 * maps at the service seam), the shard-wipe / forward-advance / no-false-refuse cases here drive REAL
 * per-shard {@link RaftLog}s over {@link Storage#file} directories, delete/rewrite the real
 * {@code raft-anchor}/{@code raft-log.wal}/{@code raft-log.snapshot} bytes, and recompute
 * {@code freshShards} from the genuine {@link RaftLog#anchorExistedAtOpen()} recovery signal - so the
 * FRESH-vs-forward discriminator is proven end to end, not asserted.
 *
 * <p>Three buckets: (A) attacks that MUST REFUSE, (B) legal crashes that MUST PROCEED (no false
 * refuse), (C) residuals (node-anchor rollback / deletion) demonstrated concretely and classified -
 * they PROCEED by design and are closable only by an external witness (see {@code AnchorWitness}),
 * not by this boot cross-check.
 */
class NodeAnchorRedteamTest {

    private static final long EPOCH = 9L;

    private static IntegrityEnvelope keyed() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 11 + 3);
        }
        return new IntegrityEnvelope(new SecretKeySpec(key, "HmacSHA256"));
    }

    private static AuditLog keyedAudit(Storage storage) {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (i * 5 + 2);
        }
        return new AuditLog(storage, Clock.system(), new SecretKeySpec(k, "HmacSHA256"));
    }

    private static Map<Integer, Long> map(long... indexes) {
        Map<Integer, Long> m = new LinkedHashMap<>();
        for (int gid = 0; gid < indexes.length; gid++) {
            m.put(gid, indexes[gid]);
        }
        return m;
    }

    /** Mint (or accept-forward) a node-anchor and release the file handle so a later enforce reopens. */
    private static long mint(Path dataDir, IntegrityEnvelope env, long epoch, int n,
            Map<Integer, Long> boot, Set<Integer> fresh, AuditLog audit) {
        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(dataDir, env, epoch, n, boot, fresh, audit);
        long seq = na.current().nodeAnchorSeq();
        na.close();
        return seq;
    }

    private static Path shardDir(Path dataDir, int gid) {
        Path d = dataDir.resolve("shard-" + gid);
        try {
            Files.createDirectories(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return d;
    }

    /** Builds a real per-shard log: appends entries 1..head over a real raft-anchor + WAL, then releases it. */
    private static void buildShard(Path shardDir, int gid, IntegrityEnvelope env, long head) {
        RaftLog log = new RaftLog(Storage.file(shardDir), env, gid);
        for (long i = 1; i <= head; i++) {
            log.append(new LogEntry(i, 1L, ("v" + i).getBytes(StandardCharsets.UTF_8)));
        }
        releaseLog(log);
    }

    /** Reopen a real shard (recovering {@code from}) and append forward to {@code to}, then release. */
    private static void advanceShard(Path shardDir, int gid, IntegrityEnvelope env, long from, long to) {
        RaftLog log = new RaftLog(Storage.file(shardDir), env, gid);
        assertEquals(from, log.lastDurableIndex(), "advanceShard precondition: recovered head");
        for (long i = from + 1; i <= to; i++) {
            log.append(new LogEntry(i, 1L, ("v" + i).getBytes(StandardCharsets.UTF_8)));
        }
        releaseLog(log);
    }

    /**
     * Recover every shard from its REAL on-disk anchor and build the node-anchor inputs exactly as
     * {@code ConfigdServer}'s bring-up loop does: {@code gid -> lastDurableIndex} + the set of gids
     * whose {@code raft-anchor} was ABSENT at open ({@link RaftLog#anchorExistedAtOpen()} == false).
     */
    private static void bootInputs(Path dataDir, int n, IntegrityEnvelope env,
            Map<Integer, Long> durableOut, Set<Integer> freshOut) {
        for (int gid = 0; gid < n; gid++) {
            RaftLog log = new RaftLog(Storage.file(shardDir(dataDir, gid)), env, gid);
            durableOut.put(gid, log.lastDurableIndex());
            if (!log.anchorExistedAtOpen()) {
                freshOut.add(gid);
            }
            releaseLog(log);
        }
    }

    /** FULL wipe: delete every regular file in a shard dir (raft-anchor + WAL + snapshot) => FRESH. */
    private static void wipeShard(Path shardDir) {
        try (Stream<Path> files = Files.list(shardDir)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Path naFile(Path dataDir) {
        return dataDir.resolve(NodeAnchorFile.NODE_ANCHOR_FILE_NAME);
    }

    /** RaftLog#closeAnchor() is package-private and cross-module; reflection releases the file handle. */
    private static void releaseLog(RaftLog log) {
        try {
            Method m = RaftLog.class.getDeclaredMethod("closeAnchor");
            m.setAccessible(true);
            m.invoke(log);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // (A) attacks that MUST REFUSE

    @Test
    void mechanism_boundEpochMismatchRefuses_matchingEpochAccepts(@TempDir Path dir) throws Exception {
        // MECHANISM of the topology cross-check: the node-anchor binds a COPY of the descriptor's
        // (epoch, N); boot compares them for EQUALITY. Here the on-disk node-anchor is swapped for a
        // legitimately-MAC'd image binding epoch=7 while the descriptor value passed in is epoch=9 =>
        // mismatch => REFUSE.
        //
        // HONESTY: with a static shard count the epoch is invariant, so a keyless attacker cannot produce
        // a mismatching-but-valid node-anchor - there is no prior-epoch valid image to roll to, and forging
        // one needs the key. The swap here is synthesized WITH the key purely to prove the comparison
        // fires, not to demonstrate a live keyless attack. The correct dual (a same-(epoch,N) rollback is
        // ACCEPTED) is asserted below.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        Map<Integer, Long> boot = map(100, 200);
        mint(dataDir, env, EPOCH, 2, boot, Set.of(), null); // node-anchor binds epoch=9

        Path scratch = Files.createDirectories(dir.resolve("scratch"));
        mint(scratch, env, EPOCH - 2, 2, boot, Set.of(), null); // a valid node-anchor binding epoch=7
        Files.write(naFile(dataDir), Files.readAllBytes(naFile(scratch)), StandardOpenOption.TRUNCATE_EXISTING);

        // descriptor epoch=9 vs node-anchor epoch=7 => REFUSE.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, boot, Set.of(), null));
        assertTrue(ex.getMessage().contains("topology"), ex.getMessage());

        // No-false-refuse: booting against the MATCHING epoch (7) PROCEEDS - it is an equality
        // cross-check, not a blanket refuse. A same-(epoch,N) descriptor rollback passes.
        NodeAnchorFile ok = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH - 2, 2, boot, Set.of(), null);
        assertTrue(ok.hasValidRecord(), "matching (epoch,N) must proceed - no false refuse");
        ok.close();
    }

    @Test
    void attack_boundEpochTamper_macCaught_refuses(@TempDir Path dir) throws Exception {
        // Tamper the bound topologyEpoch bytes in place (no key). Slot0's envelope payload starts at
        // file offset 8(container hdr)+4(recordLen)+8(env hdr)+4(scopeId)+4(keyTerm)=28; the
        // topologyEpoch field is payload bytes [8..16) => file offset 36. A flipped MAC-covered byte
        // fails the HMAC => slot0 invalid; a freshly-minted node-anchor has a zero slot1 => both invalid
        // => REFUSE. Proves the epoch is authenticated, not merely stored.
        IntegrityEnvelope env = keyed();
        Map<Integer, Long> boot = map(42);
        mint(dir, env, EPOCH, 1, boot, Set.of(), null);

        byte[] bytes = Files.readAllBytes(naFile(dir));
        bytes[36] ^= 0x40; // flip a byte inside the authenticated topologyEpoch field of slot 0
        Files.write(naFile(dir), bytes, StandardOpenOption.TRUNCATE_EXISTING);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dir, env, EPOCH, 1, boot, Set.of(), null));
        assertTrue(ex.getMessage().contains("both slots invalid"), ex.getMessage());
    }

    @Test
    void mechanism_boundShardCountMismatchRefuses(@TempDir Path dir) throws Exception {
        // The node-anchor binds a copy of N; a node-anchor binding N=3 booted against an N=2 descriptor
        // triggers the topology cross-check REFUSE. Same honesty caveat as the epoch case: the mismatch
        // is synthesized with the key, since a keyless attacker has no valid alternate-N image to swap
        // in. The bound N exists precisely to stop editing a plaintext shard-count file to bypass a
        // reshard refusal; the descriptor itself is enveloped.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        mint(dataDir, env, EPOCH, 2, map(100, 200), Set.of(), null);

        Path scratch = Files.createDirectories(dir.resolve("scratch"));
        mint(scratch, env, EPOCH, 3, map(100, 200, 300), Set.of(), null); // binds N=3
        Files.write(naFile(dataDir), Files.readAllBytes(naFile(scratch)), StandardOpenOption.TRUNCATE_EXISTING);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, map(100, 200), Set.of(), null));
        assertTrue(ex.getMessage().contains("topology"), ex.getMessage());
    }

    @Test
    void attack_bothSlotsForged_presentButInvalid_refuses(@TempDir Path dir) throws Exception {
        // Forge BOTH dual-slot envelopes (flip an authenticated byte in each). Neither slot
        // authenticates => present-but-invalid => REFUSE (distinct from a first boot with NO file,
        // which mints).
        IntegrityEnvelope env = keyed();
        Map<Integer, Long> boot = map(7);
        // Give the file two live slots first (mint => slot0 seq1; a forward re-anchor => slot1 seq2).
        mint(dir, env, EPOCH, 1, boot, Set.of(), null);
        mint(dir, env, EPOCH, 1, map(8), Set.of(), null); // digest differs, no fresh => accept-forward (slot1)

        byte[] bytes = Files.readAllBytes(naFile(dir));
        bytes[8 + 4 + 30] ^= 0x5A;         // inside slot 0's envelope
        bytes[8 + 512 + 4 + 30] ^= 0x5A;   // inside slot 1's envelope
        Files.write(naFile(dir), bytes, StandardOpenOption.TRUNCATE_EXISTING);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dir, env, EPOCH, 1, map(8), Set.of(), null));
        assertTrue(ex.getMessage().contains("both slots invalid"), ex.getMessage());
    }

    @Test
    void attack_containerHeaderTamper_refuses(@TempDir Path dir) throws Exception {
        // Flip an MBZ container-header byte (flags). The unauthenticated header is validated fail-closed
        // on open => IntegrityException REFUSE before any slot is trusted.
        IntegrityEnvelope env = keyed();
        mint(dir, env, EPOCH, 1, map(1), Set.of(), null);

        byte[] bytes = Files.readAllBytes(naFile(dir));
        bytes[5] = 0x01; // flags byte (offset 4=fileVersion, 5=flags MBZ) -> non-zero MBZ
        Files.write(naFile(dir), bytes, StandardOpenOption.TRUNCATE_EXISTING);

        assertThrows(IntegrityException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dir, env, EPOCH, 1, map(1), Set.of(), null));
    }

    @Test
    void attack_truncatedBelowContainerHeader_refuses(@TempDir Path dir) throws Exception {
        // Truncate the node-anchor below its 8-byte container header => refuse (torn/tamper), NOT
        // mistaken for a first boot (a first boot has NO file at all).
        IntegrityEnvelope env = keyed();
        mint(dir, env, EPOCH, 1, map(1), Set.of(), null);
        Files.write(naFile(dir), new byte[]{0x52, 0x4E}, StandardOpenOption.TRUNCATE_EXISTING);

        assertThrows(RuntimeException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dir, env, EPOCH, 1, map(1), Set.of(), null));
    }

    @Test
    void attack_singleShardWipeToFresh_endToEnd_refuses_Rf(@TempDir Path dir) throws Exception {
        // End to end on real bytes: two real shards with real raft-anchors at heads (3, 4). Mint the
        // node-anchor over the genuine recovered heads. Then FULLY wipe shard 1 (delete its raft-anchor +
        // WAL + snapshot) so it boots FRESH at index 0. Recompute freshShards from the REAL RaftLog
        // recovery: shard 1's anchorExistedAtOpen() is now false => freshShards={1}. The digest differs
        // AND a shard is FRESH => the wipe signature => REFUSE.
        //
        // PRECONDITION: the anchored digest MUST be non-trivial (over the committed heads), not the
        // all-zero first-boot mint - otherwise a wipe-to-0 could coincidentally match. Here that holds
        // because the shards are built to (3,4) BEFORE the mint, so enforceNodeAnchor binds the digest
        // over (3,4). A boot mint over non-zero heads writes the same record a periodic refresher tick
        // would (identical dual-slot write path); the explicit refresher tick is exercised in
        // attack_wipeAfterPeriodicTick_endToEnd_refuses_Rf below.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        buildShard(shardDir(dataDir, 0), 0, env, 3);
        buildShard(shardDir(dataDir, 1), 1, env, 4);

        Map<Integer, Long> boot = new HashMap<>();
        Set<Integer> fresh = new HashSet<>();
        bootInputs(dataDir, 2, env, boot, fresh);
        assertEquals(Map.of(0, 3L, 1, 4L), boot, "both shards recovered their real durable heads");
        assertTrue(fresh.isEmpty(), "no shard is fresh before the wipe");
        NodeAnchorFile minted = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, boot, Set.of(), null);
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(map(3, 4)), minted.current().shardAnchorDigest(),
                "precondition: a NON-trivial digest over the committed heads is anchored (not the all-zero mint)");
        minted.close();

        // the attack: physically wipe shard 1
        wipeShard(shardDir(dataDir, 1));

        Map<Integer, Long> boot2 = new HashMap<>();
        Set<Integer> fresh2 = new HashSet<>();
        bootInputs(dataDir, 2, env, boot2, fresh2);
        assertEquals(0L, boot2.get(1), "the wiped shard boots FRESH at index 0");
        assertEquals(Set.of(1), fresh2, "the wiped shard's absent raft-anchor is the FRESH signal");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, boot2, fresh2, null));
        assertTrue(ex.getMessage().contains("R-f") || ex.getMessage().contains("shard-liveness"), ex.getMessage());
    }

    @Test
    void attack_wipeAfterPeriodicTick_endToEnd_refuses_Rf(@TempDir Path dir) throws Exception {
        // Full lifecycle including the real periodic tick, proving the wipe-detection guarantee end to
        // end through the production refresher, not just the boot-mint path:
        //   1. first boot: two EMPTY shards => node-anchor MINTS the all-zero digest (pre-tick);
        //   2. commit: shard heads advance to (5, 6) on real raft-anchors;
        //   3. TICK: the real NodeAnchorService.newRefresher re-anchors the non-zero digest over (5, 6);
        //   4. shutdown; 5. wipe shard 1 to FRESH; reboot => digest differs AND fresh => REFUSE.
        // residual_Ra_fullWipe_plus_rollbackToFirstMint_isAccepted is the control showing why this
        // precondition matters: skip the tick and wipe ALL shards to 0, and the all-zero mint still
        // matches => PROCEED (a documented, bounded residual).
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));

        // 1. first boot over empty shards: mint binds the all-zero digest.
        buildShard(shardDir(dataDir, 0), 0, env, 0);
        buildShard(shardDir(dataDir, 1), 1, env, 0);
        Map<Integer, Long> boot0 = new HashMap<>();
        bootInputs(dataDir, 2, env, boot0, new HashSet<>());
        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(
                dataDir, env, EPOCH, 2, boot0, new HashSet<>(Set.of(0, 1)), null);
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(map(0, 0)), na.current().shardAnchorDigest(),
                "first-boot mint binds the all-zero digest (pre-tick)");

        // 2. commit: advance the real per-shard raft-anchors to (5, 6).
        advanceShard(shardDir(dataDir, 0), 0, env, 0, 5);
        advanceShard(shardDir(dataDir, 1), 1, env, 0, 6);

        // 3. the REAL periodic tick re-anchors the non-zero digest (first run is always due).
        Map<Integer, Long> live = map(5, 6);
        Runnable tick = NodeAnchorService.newRefresher(na, null, () -> live, 60_000L, 64);
        tick.run();
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(live), na.current().shardAnchorDigest(),
                "the periodic tick re-anchored the non-trivial digest over (5, 6)");
        na.close(); // 4. shutdown

        // 5. wipe shard 1 to FRESH and reboot.
        wipeShard(shardDir(dataDir, 1));
        Map<Integer, Long> boot2 = new HashMap<>();
        Set<Integer> fresh2 = new HashSet<>();
        bootInputs(dataDir, 2, env, boot2, fresh2);
        assertEquals(0L, boot2.get(1), "the wiped shard boots FRESH at index 0");
        assertEquals(Set.of(1), fresh2);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, boot2, fresh2, null));
        assertTrue(ex.getMessage().contains("R-f") || ex.getMessage().contains("shard-liveness"), ex.getMessage());
    }

    @Test
    void attack_auditChainTruncatedBelowAnchoredHead_realFile_refuses(@TempDir Path dir) throws Exception {
        // Audit-tail truncation. A real file-backed keyed audit chain of 6 records. Mint anchors the head
        // (record 6). Rewrite the persisted log to only the first 3 frames (drops the anchored head) =>
        // the head recordHash is no longer reachable => REFUSE.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        Path auditDir = Files.createDirectories(dir.resolve("audit"));
        Storage storage = Storage.file(auditDir);
        AuditLog audit = keyedAudit(storage);
        for (int i = 0; i < 6; i++) {
            audit.record("alice", "PUT", "k" + i, "committed");
        }
        Map<Integer, Long> boot = map(10);
        // PRECONDITION: the anchored head must be NON-genesis (the reach check is skipped for a genesis
        // head). Six records were driven before the mint, so enforceNodeAnchor binds a real head (record 6).
        NodeAnchorFile minted = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 1, boot, Set.of(), audit);
        assertFalse(Arrays.equals(minted.current().auditHeadHash(), NodeAnchorRecord.ZERO_HASH),
                "precondition: a NON-genesis audit head is anchored before the truncation");
        minted.close();

        List<byte[]> raw = storage.readLog(AuditLog.LOG_NAME);
        storage.truncateLog(AuditLog.LOG_NAME);
        for (int i = 0; i < 3; i++) {
            storage.appendToLog(AuditLog.LOG_NAME, raw.get(i));
        }
        storage.sync();

        AuditLog reopened = keyedAudit(storage);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 1, boot, Set.of(), reopened));
        assertTrue(ex.getMessage().contains("audit-head"), ex.getMessage());
    }

    // (B) legal crashes that MUST PROCEED (no false refuse)

    @Test
    void nofalse_n1CrashRestartAdvancedHead_realByte_acceptForward(@TempDir Path dir) throws Exception {
        // NO-FALSE-REFUSE, the load-bearing N=1 case a literal "any-change => REFUSE" would brick. Real
        // single shard advanced from head 3 to 8 between node-anchor ticks (a crash under load, anchor
        // present, NOT fresh). The recomputed digest DIFFERS but no shard is FRESH => accept-forward.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        buildShard(shardDir(dataDir, 0), 0, env, 3);

        Map<Integer, Long> boot = new HashMap<>();
        Set<Integer> fresh = new HashSet<>();
        bootInputs(dataDir, 1, env, boot, fresh);
        long minted = mint(dataDir, env, EPOCH, 1, boot, Set.of(), null);
        assertEquals(1L, minted);

        advanceShard(shardDir(dataDir, 0), 0, env, 3, 8); // ran forward, then "crashed"

        Map<Integer, Long> boot2 = new HashMap<>();
        Set<Integer> fresh2 = new HashSet<>();
        bootInputs(dataDir, 1, env, boot2, fresh2);
        assertEquals(8L, boot2.get(0));
        assertTrue(fresh2.isEmpty(), "a crash-restart with a present anchor is NOT fresh");

        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 1, boot2, fresh2, null);
        assertEquals(2L, na.current().nodeAnchorSeq(), "accept-forward re-anchors, never bricks a legal crash");
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(boot2), na.current().shardAnchorDigest());
        na.close();
    }

    @Test
    void nofalse_n2CrashRestartBothAdvanced_realByte_acceptForward(@TempDir Path dir) throws Exception {
        // NO-FALSE-REFUSE, N>=2. Both shards advanced (3->5, 4->7), neither fresh => accept-forward.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        buildShard(shardDir(dataDir, 0), 0, env, 3);
        buildShard(shardDir(dataDir, 1), 1, env, 4);
        Map<Integer, Long> boot = new HashMap<>();
        bootInputs(dataDir, 2, env, boot, new HashSet<>());
        mint(dataDir, env, EPOCH, 2, boot, Set.of(), null);

        advanceShard(shardDir(dataDir, 0), 0, env, 3, 5);
        advanceShard(shardDir(dataDir, 1), 1, env, 4, 7);

        Map<Integer, Long> boot2 = new HashMap<>();
        Set<Integer> fresh2 = new HashSet<>();
        bootInputs(dataDir, 2, env, boot2, fresh2);
        assertEquals(Map.of(0, 5L, 1, 7L), boot2);
        assertTrue(fresh2.isEmpty());

        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, boot2, fresh2, null);
        assertEquals(2L, na.current().nodeAnchorSeq(), "N>=2 forward advance accept-forwards");
        na.close();
    }

    @Test
    void nofalse_firstBootAllShardsFresh_mints_notWipe(@TempDir Path dir) throws Exception {
        // NO-FALSE-REFUSE, first boot. Two brand-new empty shards (both FRESH at 0) and NO node-anchor.
        // The wipe-detection branch requires the node-anchor to EXIST; an absent node-anchor takes the
        // mint path. A brand-new node must NEVER be mistaken for a wiped one.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        // Empty shard dirs => RaftLog boots FRESH and lays down a bootstrap anchor.
        buildShard(shardDir(dataDir, 0), 0, env, 0);
        buildShard(shardDir(dataDir, 1), 1, env, 0);
        Map<Integer, Long> boot = new HashMap<>();
        Set<Integer> fresh = new HashSet<>();
        bootInputs(dataDir, 2, env, boot, fresh);
        assertEquals(Map.of(0, 0L, 1, 0L), boot);
        // buildShard(...,0) already laid down bootstrap anchors, so on this recovery they are NOT fresh;
        // the point under test is the ABSENT node-anchor => mint path regardless of the fresh set.
        assertFalse(Files.exists(naFile(dataDir)), "no node-anchor yet");

        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(
                dataDir, env, EPOCH, 2, boot, new HashSet<>(Set.of(0, 1)), null);
        assertEquals(1L, na.current().nodeAnchorSeq(), "first boot mints (seq 1) even with all shards fresh");
        na.close();
    }

    @Test
    void nofalse_auditTailAboveAnchoredHead_realFile_proceeds(@TempDir Path dir) throws Exception {
        // NO-FALSE-REFUSE. Records land AFTER the anchor (the un-anchored tail); the anchored head is
        // still present => PROCEED. Truncation confined to the tail is a documented, bounded residual,
        // not a false-refuse.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        Storage storage = Storage.file(Files.createDirectories(dir.resolve("audit")));
        AuditLog audit = keyedAudit(storage);
        for (int i = 0; i < 4; i++) {
            audit.record("alice", "PUT", "k" + i, "committed");
        }
        Map<Integer, Long> boot = map(10);
        mint(dataDir, env, EPOCH, 1, boot, Set.of(), audit); // anchors head = record 4
        audit.record("bob", "DELETE", "k9", "committed");    // un-anchored tail
        audit.record("bob", "PUT", "k10", "committed");

        // Re-run against the SAME dataDir the mint used: the anchored head is still present => PROCEED.
        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 1, boot, Set.of(), audit);
        assertTrue(na.hasValidRecord(), "the un-anchored tail (R-e) must not false-refuse");
        na.close();
    }

    @Test
    void nofalse_auditReachCheckIsContentAddressed_oldestDropped_proceeds(@TempDir Path dir) throws Exception {
        // NO-FALSE-REFUSE + content-addressing. Simulate a rotation that drops the OLDEST frames but keeps
        // the anchored head (the last record). The reach check finds the head by recordHash CONTENT, not
        // by count/position, so the smaller persisted log still PROCEEDS (proves count is not the gate).
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        Storage storage = Storage.file(Files.createDirectories(dir.resolve("audit")));
        AuditLog audit = keyedAudit(storage);
        for (int i = 0; i < 6; i++) {
            audit.record("alice", "PUT", "k" + i, "committed");
        }
        Map<Integer, Long> boot = map(10);
        mint(dataDir, env, EPOCH, 1, boot, Set.of(), audit); // anchors head = record 6 (the last)

        // Rotation drops the oldest 3 frames, retains 4..6 (the anchored head, record 6, survives).
        List<byte[]> raw = storage.readLog(AuditLog.LOG_NAME);
        storage.truncateLog(AuditLog.LOG_NAME);
        for (int i = 3; i < raw.size(); i++) {
            storage.appendToLog(AuditLog.LOG_NAME, raw.get(i));
        }
        storage.sync();

        AuditLog reopened = keyedAudit(storage);
        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 1, boot, Set.of(), reopened);
        assertTrue(na.hasValidRecord(), "anchored head reachable by content after an oldest-drop rotation");
        na.close();
    }

    @Test
    void nofalse_oneSlotCorrupt_otherSlotWins_proceeds(@TempDir Path dir) throws Exception {
        // NO-FALSE-REFUSE, dual-slot torn-write recovery. Two live slots (seq1 slot0, seq2 slot1). Corrupt
        // the STALE slot (slot0). Recovery takes the higher-valid slot (slot1) and PROCEEDS - a single
        // torn/corrupt slot must not brick a node.
        IntegrityEnvelope env = keyed();
        Map<Integer, Long> a = map(5);
        Map<Integer, Long> b = map(6);
        mint(dir, env, EPOCH, 1, a, Set.of(), null);   // slot0 seq1
        mint(dir, env, EPOCH, 1, b, Set.of(), null);   // accept-forward => slot1 seq2 (live)

        byte[] bytes = Files.readAllBytes(naFile(dir));
        bytes[8 + 4 + 30] ^= 0x5A; // corrupt only the STALE slot 0
        Files.write(naFile(dir), bytes, StandardOpenOption.TRUNCATE_EXISTING);

        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(dir, env, EPOCH, 1, b, Set.of(), null);
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(b), na.current().shardAnchorDigest(),
                "the surviving live slot (seq2) wins; the node proceeds");
        na.close();
    }

    @Test
    void hardening_authOffAcceptForward_preservesAnchoredAuditHead(@TempDir Path dir) throws Exception {
        // Regression test: pre-fix, an auth-off accept-forward wrote a genesis audit head, so a later
        // auth-ON boot found genesis and SKIPPED the audit-truncation cross-check for a truncation that
        // predated the auth-off boot. The fix PRESERVES the previously anchored (auditRecordCount,
        // auditHeadHash) when auditLog == null.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        Storage storage = Storage.file(Files.createDirectories(dir.resolve("audit")));
        AuditLog audit = keyedAudit(storage);
        for (int i = 0; i < 6; i++) {
            audit.record("alice", "PUT", "k" + i, "committed");
        }

        // Boot 1 (auth ON): mint binds a NON-genesis audit head H (record 6) over heads (5, 6).
        NodeAnchorFile boot1 = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, map(5, 6), Set.of(), audit);
        byte[] anchoredHead = boot1.current().auditHeadHash().clone();
        long anchoredCount = boot1.current().auditRecordCount();
        assertFalse(Arrays.equals(anchoredHead, NodeAnchorRecord.ZERO_HASH), "boot 1 anchored a NON-genesis audit head");
        assertEquals(6L, anchoredCount);
        boot1.close();

        // Boot 2 (auth OFF, auditLog=null): a forward-advanced digest (5, 7), no fresh shard => accept-forward.
        NodeAnchorFile boot2 = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, map(5, 7), Set.of(), null);
        assertArrayEquals(anchoredHead, boot2.current().auditHeadHash(),
                "auth-off accept-forward must PRESERVE the anchored audit head, not regress it to genesis");
        assertEquals(anchoredCount, boot2.current().auditRecordCount(), "and preserve the anchored audit count");
        assertArrayEquals(NodeAnchorRecord.computeShardAnchorDigest(map(5, 7)), boot2.current().shardAnchorDigest(),
                "accept-forward really fired (the shard-liveness digest advanced forward)");
        boot2.close();

        // Boot 3 (auth ON) with the chain truncated below H: the preserved head keeps the guard LIVE => REFUSE.
        // (Pre-fix, the genesis-regressed head would have skipped this check and the boot would PROCEED.)
        List<byte[]> raw = storage.readLog(AuditLog.LOG_NAME);
        storage.truncateLog(AuditLog.LOG_NAME);
        for (int i = 0; i < 3; i++) {
            storage.appendToLog(AuditLog.LOG_NAME, raw.get(i));
        }
        storage.sync();
        AuditLog reopened = keyedAudit(storage);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, map(5, 7), Set.of(), reopened));
        assertTrue(ex.getMessage().contains("audit-head"), ex.getMessage());
    }

    // (C) residuals that PROCEED by design

    @Test
    void bypass_partialWipe_anchorDeletedWalIntact_caughtByGate3a_beforeNodeAnchor(@TempDir Path dir) throws Exception {
        // A PARTIAL wipe (delete raft-anchor, keep the WAL) must NOT launder into FRESH. RaftLog's
        // per-shard presence gate throws during recovery ("anchor was deleted" over a non-empty shard) -
        // the bring-up loop fails BEFORE enforceNodeAnchor ever runs. Confirmed here on real bytes:
        // reopening the shard THROWS, so freshShards is never even computed.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        Path s1 = shardDir(dataDir, 1);
        buildShard(s1, 1, env, 4);

        Files.delete(s1.resolve("raft-anchor")); // anchor gone, WAL/snapshot intact => shard dir non-empty
        assertTrue(Files.exists(s1.resolve("raft-log.wal")), "the WAL is still present (partial wipe)");

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(s1), env, 1));
        assertTrue(ex.getMessage().contains("anchor was deleted") || ex.getMessage().contains("no raft-anchor"),
                ex.getMessage());
    }

    @Test
    void residual_Ra_fullWipe_plus_rollbackToFirstMint_isAccepted(@TempDir Path dir) throws Exception {
        // This is a documented residual, not a bug: to HIDE a wipe the attacker must roll the node-anchor
        // to a matching-digest version. The only digest that matches an all-zeros post-wipe state is the
        // FIRST-MINT image (all shards fresh at 0). So the attack degenerates to: capture the first-mint
        // node-anchor, wipe ALL shards to 0, roll the node-anchor back to first-mint => digest matches =>
        // PROCEED (silent total loss). Closing "rollback the anchor itself to a prior valid state" needs
        // an external witness outside this node (see {@code AnchorWitness}); a SINGLE-shard wipe canNOT
        // reach a matching digest without a SHA-256 collision, so the wipe-detection guarantee for that
        // case stands.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        buildShard(shardDir(dataDir, 0), 0, env, 0); // both shards fresh at 0 on first boot
        buildShard(shardDir(dataDir, 1), 1, env, 0);
        Map<Integer, Long> boot0 = new HashMap<>();
        bootInputs(dataDir, 2, env, boot0, new HashSet<>());
        mint(dataDir, env, EPOCH, 2, boot0, new HashSet<>(Set.of(0, 1)), null);
        byte[] firstMintImage = Files.readAllBytes(naFile(dataDir)); // attacker captures the all-zeros anchor

        // The node runs: shards take writes and the node-anchor is refreshed to the nonzero heads.
        advanceShard(shardDir(dataDir, 0), 0, env, 0, 5);
        advanceShard(shardDir(dataDir, 1), 1, env, 0, 6);
        Map<Integer, Long> live = new HashMap<>();
        bootInputs(dataDir, 2, env, live, new HashSet<>());
        mint(dataDir, env, EPOCH, 2, live, Set.of(), null); // node-anchor now binds digest over (5, 6)

        // The attack: wipe BOTH shards AND roll the node-anchor back to the captured first-mint image.
        wipeShard(shardDir(dataDir, 0));
        wipeShard(shardDir(dataDir, 1));
        Files.write(naFile(dataDir), firstMintImage, StandardOpenOption.TRUNCATE_EXISTING);

        Map<Integer, Long> wiped = new HashMap<>();
        Set<Integer> fresh = new HashSet<>();
        bootInputs(dataDir, 2, env, wiped, fresh);
        assertEquals(Map.of(0, 0L, 1, 0L), wiped);
        assertEquals(Set.of(0, 1), fresh, "both shards are FRESH");

        // digestNow over (0,0) == the rolled-back first-mint digest over (0,0) => the digest-match branch
        // short-circuits (freshShards is consulted only on a MISMATCH) => PROCEED.
        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, wiped, fresh, null);
        assertTrue(na.hasValidRecord(),
                "R-a residual: a node-anchor rollback to a prior valid state is accepted (needs AnchorWitness)");
        na.close();
    }

    @Test
    void residual_Ra_nodeAnchorDeleted_plus_shardWipe_mints(@TempDir Path dir) throws Exception {
        // Another documented residual: deleting the node-anchor entirely makes the node look like a
        // first boot (no node-anchor => mint, no cross-check). Combined with a shard wipe this hides the
        // loss. Deleting the node-anchor is itself a rollback (to the never-existed state),
        // indistinguishable from a legitimate whole-datadir first boot without an external witness.
        IntegrityEnvelope env = keyed();
        Path dataDir = Files.createDirectories(dir.resolve("node"));
        buildShard(shardDir(dataDir, 0), 0, env, 3);
        buildShard(shardDir(dataDir, 1), 1, env, 4);
        Map<Integer, Long> boot = new HashMap<>();
        bootInputs(dataDir, 2, env, boot, new HashSet<>());
        mint(dataDir, env, EPOCH, 2, boot, Set.of(), null);

        // Attack: delete the node-anchor AND wipe shard 1.
        Files.delete(naFile(dataDir));
        wipeShard(shardDir(dataDir, 1));

        Map<Integer, Long> boot2 = new HashMap<>();
        Set<Integer> fresh2 = new HashSet<>();
        bootInputs(dataDir, 2, env, boot2, fresh2);
        assertEquals(Set.of(1), fresh2);

        NodeAnchorFile na = NodeAnchorService.enforceNodeAnchor(dataDir, env, EPOCH, 2, boot2, fresh2, null);
        assertEquals(1L, na.current().nodeAnchorSeq(),
                "R-a residual: a deleted node-anchor re-mints (first-boot indistinguishable; needs AnchorWitness)");
        assertNotEquals(NodeAnchorRecord.computeShardAnchorDigest(boot),
                na.current().shardAnchorDigest(), "the re-mint bound the POST-wipe (lossy) digest");
        na.close();
    }
}
