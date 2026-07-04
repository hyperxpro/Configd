package io.configd.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent red-team pass over the {@link WalContainer} 8-byte header and the {@link FileStorage}
 * frame scan that runs behind it.
 *
 * <p>The header is UNAUTHENTICATED by design (a key-less corruption / foreign-file guard). The lead's
 * sharpest ask: prove a flipped header can ONLY produce a clean REFUSE — never an out-of-bounds read,
 * a wrong-offset frame parse, or acceptance of a tampered frame. The exhaustive single-bit-flip test
 * below walks every one of the 64 header bits and asserts each one fails closed and never yields a
 * parsed entry. The remaining tests pin the crash-consistency boundary (torn tail must NOT
 * false-positive) and probe the post-header frame-length field for allocation attacks.
 */
class WalContainerRedteamTest {

    @TempDir
    Path tempDir;

    private static final String LOG = "raft-log";

    private Path writeRealWal(String sub, String... entries) {
        Path dir = tempDir.resolve(sub);
        Storage storage = Storage.file(dir);
        for (String e : entries) {
            storage.appendToLog(LOG, e.getBytes());
        }
        return dir.resolve(LOG + ".wal");
    }

    // ---------------------------------------------------------------------------------------------
    // The exhaustive header-tamper proof: every single-bit flip in the 8-byte header fails closed.
    // ---------------------------------------------------------------------------------------------

    @Test
    void everySingleBitHeaderFlipFailsClosedAndNeverParses() throws Exception {
        Path wal = writeRealWal("bitflip", "alpha", "bravo", "charlie");
        Path dir = wal.getParent();
        byte[] pristine = Files.readAllBytes(wal);

        // Sanity: the pristine file loads its three frames.
        assertEquals(3, Storage.file(dir).readLog(LOG).size(), "precondition: pristine WAL replays");

        for (int off = 0; off < WalContainer.HEADER_SIZE; off++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] tampered = pristine.clone();
                tampered[off] ^= (byte) (1 << bit);
                Files.write(wal, tampered);

                // Every header field is magic / fileVersion / MBZ-flags / MBZ-reserved — there is no
                // header-derived offset, so a single-bit flip can only turn "load" into "refuse".
                // A fresh Storage each iteration avoids any cached channel; readLog opens the file anew.
                assertThrows(UncheckedIOException.class, () -> Storage.file(dir).readLog(LOG),
                        "header bit-flip at byte " + off + " bit " + bit
                                + " must fail closed (never parse, never OOB)");
            }
        }
    }

    @Test
    void fileVersionZeroRejected() throws Exception {
        // version 0 is reserved-illegal ("unset/torn"), distinct from the higher-version case the
        // builder covers. Set the fileVersion byte (offset 4) to 0 and confirm the refusal.
        Path wal = writeRealWal("verzero", "entry");
        byte[] b = Files.readAllBytes(wal);
        b[4] = 0;
        Files.write(wal, b);
        assertThrows(UncheckedIOException.class, () -> Storage.file(wal.getParent()).readLog(LOG),
                "container fileVersion 0 must be refused (reserved-illegal)");
    }

    @Test
    void reservedHighAndLowBytesRejectedSeparately() throws Exception {
        // The builder's MBZ test flips the flags byte (offset 5). Cover the two reserved bytes
        // (offsets 6 and 7) individually so every MBZ byte is proven, not just the first.
        for (int off : new int[]{6, 7}) {
            Path wal = writeRealWal("mbz-" + off, "entry");
            byte[] b = Files.readAllBytes(wal);
            b[off] = 1;
            Files.write(wal, b);
            assertThrows(UncheckedIOException.class, () -> Storage.file(wal.getParent()).readLog(LOG),
                    "non-zero MBZ reserved byte at offset " + off + " must fail closed");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sub-header files are FRESH (torn/first-boot), not a refuse. The builder covers 0 and 8 bytes;
    // cover the whole 1..7 gap.
    // ---------------------------------------------------------------------------------------------

    @Test
    void subHeaderFilesAreFreshNotRefused() throws Exception {
        for (int len = 1; len <= WalContainer.HEADER_SIZE - 1; len++) {
            Path dir = tempDir.resolve("subhdr-" + len);
            Files.createDirectories(dir);
            Files.write(dir.resolve(LOG + ".wal"), new byte[len]);
            assertTrue(Storage.file(dir).readLog(LOG).isEmpty(),
                    "a " + len + "-byte (< header) .wal must read as fresh, not throw");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Crash-consistency: a valid header + good frames + a torn trailing frame must replay the good
    // frames and DROP the torn tail — NOT a false-positive refuse.
    // ---------------------------------------------------------------------------------------------

    @Test
    void tornTrailingFrameReplaysGoodFramesAndDropsTail() throws Exception {
        Path wal = writeRealWal("torn", "good-1", "good-2");
        byte[] intact = Files.readAllBytes(wal);

        // Append a torn frame: a 4-byte length claiming 100 bytes, but only 3 bytes follow. The scan
        // must stop at the torn boundary and return the two durable frames — a crash after a partial
        // append is normal, not tamper.
        ByteBuffer torn = ByteBuffer.allocate(intact.length + 4 + 3);
        torn.put(intact);
        torn.putInt(100);
        torn.put(new byte[]{1, 2, 3});
        Files.write(wal, torn.array());

        List<byte[]> entries = Storage.file(wal.getParent()).readLog(LOG);
        assertEquals(2, entries.size(), "the torn trailing frame must be dropped, not cause a refuse");
        assertArrayEquals("good-1".getBytes(), entries.get(0));
        assertArrayEquals("good-2".getBytes(), entries.get(1));
    }

    @Test
    void tamperedFrameDataWithStaleCrcIsRefused() throws Exception {
        // A complete frame whose DATA byte is flipped (frame CRC left stale) is caught by the
        // container's corruption CRC — a refuse, not a silent accept. (Authentication of the record
        // content is the inner IntegrityEnvelope's job; this only asserts the container corruption
        // guard still fires for a complete-but-corrupt frame.)
        Path wal = writeRealWal("datacorrupt", "payload-value");
        byte[] b = Files.readAllBytes(wal);
        // First frame data begins at header(8) + length(4) = 12.
        b[12] ^= 0x01;
        Files.write(wal, b);
        assertThrows(UncheckedIOException.class, () -> Storage.file(wal.getParent()).readLog(LOG),
                "a corrupt (stale-CRC) complete frame must be refused");
    }

    // ---------------------------------------------------------------------------------------------
    // Post-header frame-length field: allocation-attack probes.
    // ---------------------------------------------------------------------------------------------

    @Test
    void largeButNonOverflowingFrameLengthIsCleanlyDropped() throws Exception {
        // CONTROL: a frame length far larger than the file (0x40000000 = 1 GiB) — but small enough
        // that `length + 4` does NOT overflow — is correctly treated as a torn tail and dropped.
        // This is the behavior the near-MAX case below SHOULD share but does not.
        Path wal = writeRealWal("biglen", "real");
        byte[] intact = Files.readAllBytes(wal);
        ByteBuffer b = ByteBuffer.allocate(intact.length + 4 + 8);
        b.put(intact);
        b.putInt(0x4000_0000); // 1 GiB, positive, length+4 does not overflow
        b.put(new byte[8]);
        Files.write(wal, b.array());

        List<byte[]> entries = Storage.file(wal.getParent()).readLog(LOG);
        assertEquals(1, entries.size(), "an over-long-but-non-overflowing length must drop cleanly");
        assertArrayEquals("real".getBytes(), entries.get(0));
    }

    @Test
    void nearMaxFrameLengthIsCleanlyDropped() throws Exception {
        // REGRESSION for FINDING G1 (fixed): FileStorage.readLog guards a torn/oversized frame.
        // The guard was `remaining < length + 4`; for a declared length in
        // [Integer.MAX_VALUE-3, Integer.MAX_VALUE] the `length + 4` overflowed to a negative int,
        // the guard was BYPASSED, and `new byte[length]` (~2 GiB) drove an OutOfMemoryError on the
        // recovery path - a filesystem-write adversary turning a boot into a crash. The guard is
        // now overflow-safe (`length > remaining - 4`, subtracting on the trusted side), so an
        // over-long forged length is dropped as a torn tail exactly like any other truncated frame.
        //
        // This exercises the worst case (length = Integer.MAX_VALUE) and asserts the CLEAN DROP:
        // the one good preceding frame replays and the forged trailing frame is discarded, with no
        // allocation and no crash.
        Path wal = writeRealWal("overflow", "real");
        byte[] intact = Files.readAllBytes(wal);
        ByteBuffer b = ByteBuffer.allocate(intact.length + 4 + 8);
        b.put(intact);
        b.putInt(0x7FFF_FFFF); // Integer.MAX_VALUE: the pre-fix `length + 4` overflowed and bypassed the guard
        b.put(new byte[8]);
        Files.write(wal, b.array());

        List<byte[]> entries = Storage.file(wal.getParent()).readLog(LOG);
        assertEquals(1, entries.size(), "a forged near-MAX frame length must drop cleanly, not allocate");
        assertArrayEquals("real".getBytes(), entries.get(0));
    }
}
