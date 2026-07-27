package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.Storage;
import io.configd.common.WalContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Independent, real-on-disk rollback / truncation attacks on the dual-slot {@code raft-anchor}
 * recovery path ({@link AnchorFile} + {@link RaftLog#recoverWithAnchor}).
 *
 * <p>Every attack here mutates the actual bytes of {@code raft-anchor} / {@code raft-log.wal} on a
 * real {@link Storage#file} directory and then drives the real {@code new RaftLog(...)} recovery,
 * asserting the recovery decision (refuse vs accept). This goes beyond {@link AnchorFileTest} /
 * {@link RaftAnchorRecoveryTest} (which use the writer's own API) by crafting adversarial bytes the
 * writer would never emit: forged slot-length prefixes, whole-file image rollbacks captured at an
 * earlier durable point, and a genuine fresh image overwritten onto a live shard.
 *
 * <p>The suite is organised as:
 * <ul>
 *   <li><b>CLOSED</b> - attacks the anchor exists to catch; recovery must refuse.</li>
 *   <li><b>SAFE</b> - legal crash / Raft interleavings that must not spuriously refuse (false
 *       positives are as serious as misses: a spurious refuse bricks a healthy node).</li>
 *   <li><b>RESIDUAL</b> - the one documented boundary of a purely local anchor: a within-term
 *       rollback of the durable floor to a prior authenticated state. This test asserts the current
 *       accepting behaviour (it documents the gap, not a weakening) and is the reason the design
 *       mandates an external {@code AnchorWitness}, which is not wired here.</li>
 * </ul>
 */
class AnchorRollbackRedteamTest {

    private static final int GID = 0;
    private static final String WAL = "raft-log.wal";
    private static final String ANCHOR = FileAnchorIO.ANCHOR_FILE_NAME;

    private static IntegrityEnvelope keyed() {
        return SnapshotIntegrityTest.keyedEnvelope();
    }

    private static LogEntry entry(long index, long term, String cmd) {
        return new LogEntry(index, term, cmd.getBytes(StandardCharsets.UTF_8));
    }


    /**
     * Attack 1 - tail-truncation of committed data, swept across EVERY W' in [snapshotIndex, A).
     * The anchor asserts A=5 was the committed-and-acked durable floor; any WAL head strictly below
     * it means a committed entry vanished => W&lt;A REFUSE. W'=0 (the whole WAL truncated to the
     * header) is the extreme and must refuse too (an anchor present over an emptied WAL is NOT a
     * FRESH node).
     */
    @Test
    void tailTruncationBelowFloorRefuses_everyWprime(@TempDir Path base) throws Exception {
        for (int wPrime = 4; wPrime >= 0; wPrime--) {
            Path dir = Files.createDirectories(base.resolve("w" + wPrime));
            RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
            for (int i = 1; i <= 5; i++) {
                log.append(entry(i, 1, "v" + i));
            }
            log.closeAnchor();

            truncateWalToFrameCount(dir, wPrime);

            IntegrityException ex = assertThrows(IntegrityException.class,
                    () -> new RaftLog(Storage.file(dir), keyed(), GID),
                    "W'=" + wPrime + " < A=5 must REFUSE (committed entry vanished)");
            assertTrue(ex.getMessage().contains("head-rollback"),
                    "expected a W<A head-rollback refuse for W'=" + wPrime + ", got: " + ex.getMessage());
        }
    }

    /**
     * Attack 2b - whole-file anchor rollback to an EARLIER captured image (floor A=3) AND the WAL
     * dropped BELOW that rolled-back floor (W'=2). Even though the anchor was rolled to a genuine
     * prior authenticated image (both slots validate), the WAL now sits below the rolled floor =>
     * W&lt;A REFUSE. Rolling the anchor DOWN does not let the adversary drop committed data below
     * whatever floor the anchor still asserts.
     */
    @Test
    void wholeFileRollbackThenTruncateBelowRolledFloorRefuses(@TempDir Path dir) throws Exception {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.append(entry(3, 1, "c"));
        log.closeAnchor();
        byte[] earlierImage = Files.readAllBytes(dir.resolve(ANCHOR));

        RaftLog log2 = new RaftLog(Storage.file(dir), keyed(), GID);
        log2.append(entry(4, 1, "d"));
        log2.append(entry(5, 1, "e"));
        log2.closeAnchor();

        Files.write(dir.resolve(ANCHOR), earlierImage, StandardOpenOption.TRUNCATE_EXISTING);
        truncateWalToFrameCount(dir, 2);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), GID));
        assertTrue(ex.getMessage().contains("head-rollback"),
                "a WAL below even the rolled-back floor must REFUSE, got: " + ex.getMessage());
    }

    /**
     * Attack 3 - rollback to the FRESH bootstrap record (anchorSeq=1, all-zero) while the WAL still
     * holds committed entries. Achieved with a GENUINE fresh image (bootstrapped in a scratch dir,
     * byte-for-byte a real fresh anchor for this gid) overwritten onto the live shard. Recovery must
     * NOT silently bootstrap-empty: the fresh record's currentTerm=0 is below the WAL's witnessed
     * term => Step-2.5 term-witness REFUSE.
     */
    @Test
    void rollbackToGenesisImageOverLiveWalRefuses(@TempDir Path base) throws Exception {
        Path dir = Files.createDirectories(base.resolve("victim"));
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.append(entry(3, 1, "c"));
        log.closeAnchor();

        Path scratch = Files.createDirectories(base.resolve("scratch"));
        AnchorFile fresh = AnchorFile.openInDirectory(scratch, GID, keyed());
        fresh.bootstrapFresh();
        fresh.close();
        byte[] genesisImage = Files.readAllBytes(scratch.resolve(ANCHOR));

        Files.write(dir.resolve(ANCHOR), genesisImage, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), GID),
                "a genesis anchor over a committed WAL must NOT silently bootstrap-empty");
        assertTrue(ex.getMessage().contains("term-witness"),
                "genesis (currentTerm=0) under a term>=1 WAL must trip Step-2.5, got: " + ex.getMessage());
    }

    /**
     * Attack 4 - term-boundary anchor rollback via a whole-file image captured at term 1, restored
     * after the node advanced to term 5 (the WAL witnesses term 5). anchor.currentTerm=1 &lt;
     * lastWALTerm=5 => Step-2.5 REFUSE. This is an independent whole-image variant of the baseline
     * writeTermVote test.
     */
    @Test
    void termRollbackWholeImageBelowWalWitnessedTermRefuses(@TempDir Path dir) throws Exception {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        log.append(entry(1, 1, "a"));
        log.closeAnchor();
        byte[] term1Image = Files.readAllBytes(dir.resolve(ANCHOR));

        RaftLog log2 = new RaftLog(Storage.file(dir), keyed(), GID);
        log2.append(entry(2, 5, "b"));
        log2.closeAnchor();

        Files.write(dir.resolve(ANCHOR), term1Image, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), GID));
        assertTrue(ex.getMessage().contains("term-witness"),
                "a WAL term above the rolled-back anchor.currentTerm must REFUSE (Step-2.5), got: "
                        + ex.getMessage());
    }

    /**
     * Attack 5 - tail-content rollback at the durable head: index unchanged (W==A==3) but the WAL[A]
     * frame carries an OLDER term than the anchor's lastDurableTerm. Built by committing index 3 at
     * term 2 (anchor.lastDurableTerm=2), then overwriting the whole WAL with a genuine, self-
     * consistent term-1 chain for indices 1..3. The hash chain validates (it is a real prior chain),
     * so the HEAD-TERM gate is the sole closer => "head-term mismatch" REFUSE.
     */
    @Test
    void tailContentRollbackHeadTermMismatchRefuses(@TempDir Path dir) throws Exception {
        RaftLog t1 = new RaftLog(Storage.file(dir), keyed(), GID);
        t1.append(entry(1, 1, "a"));
        t1.append(entry(2, 1, "b"));
        t1.append(entry(3, 1, "c"));
        t1.closeAnchor();
        byte[] term1Wal = Files.readAllBytes(dir.resolve(WAL));

        RaftLog t2 = new RaftLog(Storage.file(dir), keyed(), GID);
        t2.truncateFrom(3);
        t2.append(entry(3, 2, "c"));            // same command length -> identical frame length
        assertEquals(2, t2.lastTerm());
        t2.closeAnchor();

        Files.write(dir.resolve(WAL), term1Wal, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), GID));
        assertTrue(ex.getMessage().contains("head-term mismatch"),
                "a rolled-back head TERM (index unchanged) must trip the head-term gate, got: "
                        + ex.getMessage());
    }

    /**
     * Attack 6a - forge/zero BOTH slots' unauthenticated recordLen prefixes: neither slot parses,
     * so the file is present-but-both-invalid (a tamper distinct from a FRESH node) => REFUSE.
     */
    @Test
    void bothSlotsForgedRefuses(@TempDir Path dir) throws Exception {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.closeAnchor();

        zeroSlotLenPrefix(dir, AnchorFile.SLOT0_OFFSET);
        zeroSlotLenPrefix(dir, AnchorFile.SLOT1_OFFSET);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), GID));
        assertTrue(ex.getMessage().contains("both slots invalid"),
                "both slots forged must REFUSE (present-but-invalid, not FRESH), got: " + ex.getMessage());
    }

    /**
     * Attack 6b - delete the anchor over a non-empty shard: a shard with a WAL MUST carry its anchor;
     * an absent anchor over data is a deleted-anchor tamper => REFUSE (never a silent FRESH boot).
     */
    @Test
    void deletedAnchorOverNonEmptyShardRefuses(@TempDir Path dir) throws Exception {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.closeAnchor();

        Files.delete(dir.resolve(ANCHOR));

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), GID));
        assertTrue(ex.getMessage().contains("anchor was deleted") || ex.getMessage().contains("no raft-anchor"),
                "a deleted anchor over a non-empty shard must REFUSE, got: " + ex.getMessage());
    }

    /**
     * Attack 6c - a foreign-gid anchor spliced into this shard. Its slots carry scopeId=1, so a
     * gid=0 reader authenticates neither slot: present-but-both-invalid refuse fires at anchor-open
     * before any WAL scope assert. Still a refuse.
     */
    @Test
    void foreignGidAnchorImageRefuses(@TempDir Path dir) throws Exception {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.closeAnchor();

        Path scratch = Files.createDirectories(dir.resolve("foreign"));
        AnchorFile foreign = AnchorFile.openInDirectory(scratch, 1, keyed());
        foreign.bootstrapFresh();
        foreign.writeDurableHead(2, 1);
        foreign.close();
        byte[] foreignImage = Files.readAllBytes(scratch.resolve(ANCHOR));
        Files.write(dir.resolve(ANCHOR), foreignImage, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), GID));
        assertTrue(ex.getMessage().contains("both slots invalid"),
                "a foreign-gid anchor authenticates no slot for this gid => REFUSE, got: " + ex.getMessage());
    }


    /**
     * False-positive 7a - the legal leader-flush crash between the WAL fsync and the anchor fsync:
     * the WAL is durable to index 4 but the anchor still names index 3 (W&gt;A). Recovery adopts the
     * durable WAL head (entries (A,W] were never committed-and-acked) and rewrites the anchor
     * forward - NO refuse, NO loss.
     */
    @Test
    void crashBetweenWalFsyncAndAnchorFsyncAcceptsForward() {
        CrashStorage storage = new CrashStorage();
        RaftLog log = new RaftLog(storage, keyed(), GID);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.append(entry(3, 1, "c"));
        log.appendNoSync(entry(4, 1, "d"));

        RaftLog recovered = new RaftLog(storage.recoveredView(), keyed(), GID);
        assertEquals(4, recovered.lastIndex(), "W>A must accept-forward and adopt the durable WAL head");
        assertEquals(4, recovered.anchor().current().lastDurableIndex(), "anchor rewritten forward");
        recovered.closeAnchor();
    }

    /**
     * False-positive 7b - a legal Raft conflict truncation (INV-ANCHOR-LOWER lowers the anchor to
     * conflictPoint-1 BEFORE the WAL rewrite, then the re-append raises it). Must recover cleanly
     * with the re-appended tail, NOT trip a spurious W&lt;A.
     */
    @Test
    void legalConflictTruncationReappendRecovers(@TempDir Path dir) {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        log.append(entry(1, 1, "v1"));
        log.append(entry(2, 1, "v2"));
        log.append(entry(3, 1, "v3"));
        log.truncateFrom(2);
        log.append(entry(2, 2, "v2b"));
        log.append(entry(3, 2, "v3b"));
        log.closeAnchor();

        RaftLog recovered = new RaftLog(Storage.file(dir), keyed(), GID);
        assertEquals(3, recovered.lastIndex());
        assertEquals(2, recovered.lastTerm());
        assertEquals("v2b", new String(recovered.entryAt(2).command(), StandardCharsets.UTF_8));
        recovered.closeAnchor();
    }

    /**
     * False-positive 7c - a legal compaction whose anchor snapshot-advance was lost to a crash
     * (blob@3 durable, WAL rewritten to start at 4, but the anchor still names the OLD snapshot
     * boundary 0). Recovery adopts the WAL-implied boundary (firstIndex-1=3) because the matching
     * authenticated blob@3 IS present, and recovers - NO refuse.
     */
    @Test
    void legalCompactionWithLaggingAnchorRecovers(@TempDir Path dir) {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        for (int i = 1; i <= 5; i++) {
            log.append(entry(i, 1, "v" + i));
        }
        log.persistSnapshot(new SnapshotState(new byte[]{1}, 3, 1));
        log.compact(3, 1);

        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        AnchorRecord cur = a.current();
        a.writeSnapshot(0, 0, cur.lastDurableIndex(), cur.lastDurableTerm());
        a.close();
        log.closeAnchor();

        RaftLog recovered = new RaftLog(Storage.file(dir), keyed(), GID);
        assertEquals(5, recovered.lastIndex(), "lagging-anchor compaction must recover, not refuse");
        assertEquals(3, recovered.snapshotIndex(), "recovery adopts the WAL+blob snapshot boundary");
        recovered.closeAnchor();
    }

    /**
     * Bonus (front-truncation) - the SAME lagging-anchor shape as 7c but with the authenticated
     * blob@3 DELETED: now the WAL starting at 4 fabricates a phantom compaction (committed indices
     * 1..3 silently dropped) with no blob to justify it => REFUSE.
     */
    @Test
    void frontTruncationPhantomCompactionRefuses(@TempDir Path dir) throws Exception {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        for (int i = 1; i <= 5; i++) {
            log.append(entry(i, 1, "v" + i));
        }
        log.persistSnapshot(new SnapshotState(new byte[]{1}, 3, 1));
        log.compact(3, 1);
        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        AnchorRecord cur = a.current();
        a.writeSnapshot(0, 0, cur.lastDurableIndex(), cur.lastDurableTerm());
        a.close();
        log.closeAnchor();

        // FileStorage stores a value key as "<key>.dat"; delete (not deleteIfExists) so a wrong name
        // would fail loudly rather than silently leaving the blob in place.
        Files.delete(dir.resolve("raft-log.snapshot.dat"));

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), GID));
        assertTrue(ex.getMessage().contains("front-truncation") || ex.getMessage().contains("phantom compaction"),
                "a front-truncated WAL with no matching blob must REFUSE, got: " + ex.getMessage());
    }

    /** False-positive 7d - a genuinely fresh node (no anchor, empty shard dir) boots FRESH, no refuse. */
    @Test
    void freshEmptyShardBootstraps(@TempDir Path dir) {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        assertEquals(0, log.lastIndex());
        assertTrue(log.anchor().hasValidRecord(), "a fresh shard lays down a bootstrap anchor");
        log.closeAnchor();
        RaftLog reopened = new RaftLog(Storage.file(dir), keyed(), GID);
        assertEquals(0, reopened.lastIndex());
        reopened.closeAnchor();
    }

    /**
     * SAFE (the benign half of the one-step-rollback primitive) - zeroing the NEWER slot's
     * recordLen prefix forces the dual-slot fallback to the seq-1 slot (floor 3), but the WAL is
     * left INTACT at 6. W&gt;A => accept-forward re-adopts the durable WAL head; NO committed data is
     * lost. This proves the free slot-corruption ALONE is harmless - it is the composition with a
     * matching WAL truncation (see the RESIDUAL test) that loses data.
     */
    @Test
    void oneStepSlotCorruptionWithWalIntactAcceptsForwardNoLoss(@TempDir Path dir) throws Exception {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.append(entry(3, 1, "c"));
        log.appendAll(List.of(entry(4, 1, "d"), entry(5, 1, "e"), entry(6, 1, "f")));
        log.closeAnchor();

        int newer = higherSeqSlotOffset(dir);
        zeroSlotLenPrefix(dir, newer);
        assertEquals(3, reopenAnchorFloor(dir), "the fallback slot must hold the seq-1 floor (3)");

        RaftLog recovered = new RaftLog(Storage.file(dir), keyed(), GID);
        assertEquals(6, recovered.lastIndex(), "slot corruption alone must not lose committed data");
        recovered.closeAnchor();
    }


    /**
     * Residual finding - a within-term rollback of the durable floor to a prior authenticated
     * state, composed with a matching WAL truncation, is accepted by the local anchor and silently
     * loses committed-and-acked data. This test performs the strongest, cheapest realisation and
     * asserts the accepting behaviour (it documents the gap - it is deliberately not written as an
     * expected-refuse).
     *
     * <p>Mechanism (all on real bytes, no key needed for the rollback step):
     * <ol>
     *   <li>Commit indices 1..3 (per-entry), then a batch of 4..6 in one anchor write. The live slot
     *       now holds (seq S, floor 6); the stale slot holds (seq S-1, floor 3) - a genuine prior
     *       authenticated floor sitting one seq back.</li>
     *   <li>Zero the live slot's unauthenticated 4-byte recordLen prefix. Dual-slot recovery
     *       ("read highest valid seq, tolerate a torn other slot" - required for crash-atomicity)
     *       now promotes the seq-1 slot: a free, keyless one-step rollback of the floor 6 to 3,
     *       dropping three committed indices in a single step.</li>
     *   <li>Truncate the WAL to index 3 to match the rolled-back floor.</li>
     * </ol>
     * Recovery sees W==A==3, same term, contiguous, valid chain, so every gate passes and it
     * accepts. Committed indices 4, 5, 6 are gone with no refusal.
     *
     * <p>This is not a code defect: the design explicitly carves this out as a residual, closable
     * only by external monotonic storage or a peer-quorum {@code AnchorWitness}, which is not wired
     * here, so the gap is live in this test. On a multi-replica cluster the rolled-back node
     * re-syncs from the quorum; on a single replica this is silent committed-data loss. The test
     * stands as the executable proof that the local anchor's anti-rollback guarantee ends exactly
     * at "the adversary cannot also roll the anchor".
     */
    @Test
    void RESIDUAL_R_a_oneStepFloorRollbackThenTruncateSilentlyLosesCommittedData(@TempDir Path dir)
            throws Exception {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), GID);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.append(entry(3, 1, "c"));
        log.appendAll(List.of(entry(4, 1, "d"), entry(5, 1, "e"), entry(6, 1, "f")));
        assertEquals(6, log.lastIndex());
        log.closeAnchor();

        int newer = higherSeqSlotOffset(dir);
        zeroSlotLenPrefix(dir, newer);
        assertEquals(3, reopenAnchorFloor(dir), "the promoted seq-1 slot must carry floor 3");

        truncateWalToFrameCount(dir, 3);

        RaftLog recovered;
        try {
            recovered = new RaftLog(Storage.file(dir), keyed(), GID);
        } catch (IntegrityException unexpected) {
            // If this ever throws, the local anchor closed the residual gap on its own - a welcome
            // surprise meaning the gap is narrower than documented. Surface it loudly rather than
            // silently passing the try/catch.
            fail("R-a UNEXPECTEDLY refused - the local anchor closed a within-term one-step floor "
                    + "rollback the design says only AnchorWitness can catch: " + unexpected.getMessage());
            return;
        }
        assertEquals(3, recovered.lastIndex(),
                "R-a: recovery accepts the rolled-back+truncated state, silently dropping committed 4,5,6");
        assertNull(recovered.entryAt(6),
                "index 6 was committed-and-acked but is silently absent after the R-a attack");
        assertNull(recovered.entryAt(4), "index 4 (committed) silently absent after the R-a attack");
        recovered.closeAnchor();
    }


    /**
     * Truncates {@code raft-log.wal} to keep exactly the first {@code keepFrames} complete frames
     * (header + those frames), dropping the committed tail - a lost-tail / adversarial-truncation
     * simulation on real bytes. One entry == one frame in these tests (no snapshot), so
     * keepFrames == the highest surviving index.
     */
    private static void truncateWalToFrameCount(Path dir, int keepFrames) throws Exception {
        Path wal = dir.resolve(WAL);
        byte[] bytes = Files.readAllBytes(wal);
        int pos = WalContainer.HEADER_SIZE;
        int kept = 0;
        while (kept < keepFrames && pos + 4 <= bytes.length) {
            int len = ByteBuffer.wrap(bytes, pos, 4).getInt();
            int frameEnd = pos + 4 + len + 4; // [len:4][data:len][crc32c:4]
            if (len < 0 || frameEnd > bytes.length) {
                break;
            }
            pos = frameEnd;
            kept++;
        }
        Files.write(wal, Arrays.copyOf(bytes, pos), StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Zeroes a slot's unauthenticated 4-byte {@code recordLen} prefix so {@code parseSlot} rejects
     * it (recordLen==0) and the OTHER slot wins - the dual-slot one-step rollback primitive. The
     * prefix sits OUTSIDE the integrity envelope, so this needs no key.
     */
    private static void zeroSlotLenPrefix(Path dir, int slotOffset) throws Exception {
        Path file = dir.resolve(ANCHOR);
        byte[] bytes = Files.readAllBytes(file);
        for (int i = 0; i < AnchorFile.RECORD_LEN_PREFIX; i++) {
            bytes[slotOffset + i] = 0;
        }
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Decodes both slots' authenticated {@code anchorSeq} and returns the file offset of the slot
     * holding the HIGHER seq (the live slot / rollback target). Uses the same envelope the writer
     * used, so it is robust to the slot-write parity rather than reasoning about it.
     */
    private static int higherSeqSlotOffset(Path dir) throws Exception {
        byte[] image = Files.readAllBytes(dir.resolve(ANCHOR));
        long seq0 = slotSeq(image, AnchorFile.SLOT0_OFFSET);
        long seq1 = slotSeq(image, AnchorFile.SLOT1_OFFSET);
        return (seq1 > seq0) ? AnchorFile.SLOT1_OFFSET : AnchorFile.SLOT0_OFFSET;
    }

    /** The authenticated anchorSeq in the slot at {@code slotOffset}, or -1 if the slot is invalid. */
    private static long slotSeq(byte[] image, int slotOffset) {
        int recordLen = ByteBuffer.wrap(image, slotOffset, AnchorFile.RECORD_LEN_PREFIX).getInt();
        if (recordLen <= 0 || recordLen > AnchorFile.MAX_RECORD_LEN) {
            return -1;
        }
        byte[] env = Arrays.copyOfRange(image, slotOffset + AnchorFile.RECORD_LEN_PREFIX,
                slotOffset + AnchorFile.RECORD_LEN_PREFIX + recordLen);
        byte[] payload = keyed().unwrapOrNull(RaftArtifactMagic.ANCHOR_MAGIC, GID, env);
        if (payload == null) {
            return -1;
        }
        return AnchorRecord.decode(payload).anchorSeq();
    }

    private static long reopenAnchorFloor(Path dir) {
        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        assertNotNull(a.current(), "a valid slot must survive the one-step corruption");
        long floor = a.current().lastDurableIndex();
        a.close();
        return floor;
    }
}
