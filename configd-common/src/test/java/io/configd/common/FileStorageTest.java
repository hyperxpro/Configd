package io.configd.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class FileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void putGetRoundTrip() {
        Storage storage = Storage.file(tempDir.resolve("store"));
        byte[] value = "hello world".getBytes();
        storage.put("mykey", value);

        byte[] retrieved = storage.get("mykey");
        assertArrayEquals(value, retrieved);
    }

    @Test
    void getReturnsNullForMissingKey() {
        Storage storage = Storage.file(tempDir.resolve("store"));
        assertNull(storage.get("nonexistent"));
    }

    @Test
    void putOverwritesExistingValue() {
        Storage storage = Storage.file(tempDir.resolve("store"));
        storage.put("key", "first".getBytes());
        storage.put("key", "second".getBytes());

        assertArrayEquals("second".getBytes(), storage.get("key"));
    }

    @Test
    void putGetEmptyValue() {
        Storage storage = Storage.file(tempDir.resolve("store"));
        storage.put("empty", new byte[0]);

        byte[] retrieved = storage.get("empty");
        assertNotNull(retrieved);
        assertEquals(0, retrieved.length);
    }

    @Test
    void appendToLogAndReadLogRoundTrip() {
        Storage storage = Storage.file(tempDir.resolve("store"));

        byte[] entry1 = "first entry".getBytes();
        byte[] entry2 = "second entry".getBytes();
        byte[] entry3 = "third entry".getBytes();

        storage.appendToLog("test-log", entry1);
        storage.appendToLog("test-log", entry2);
        storage.appendToLog("test-log", entry3);

        List<byte[]> entries = storage.readLog("test-log");
        assertEquals(3, entries.size());
        assertArrayEquals(entry1, entries.get(0));
        assertArrayEquals(entry2, entries.get(1));
        assertArrayEquals(entry3, entries.get(2));
    }

    @Test
    void readLogReturnsEmptyForMissingLog() {
        Storage storage = Storage.file(tempDir.resolve("store"));
        List<byte[]> entries = storage.readLog("nonexistent");
        assertTrue(entries.isEmpty());
    }

    @Test
    void truncateLogClearsAllEntries() {
        Storage storage = Storage.file(tempDir.resolve("store"));

        storage.appendToLog("test-log", "entry1".getBytes());
        storage.appendToLog("test-log", "entry2".getBytes());
        assertEquals(2, storage.readLog("test-log").size());

        storage.truncateLog("test-log");

        List<byte[]> entries = storage.readLog("test-log");
        assertTrue(entries.isEmpty());
    }

    @Test
    void truncateLogIsIdempotentForMissingLog() {
        Storage storage = Storage.file(tempDir.resolve("store"));
        assertDoesNotThrow(() -> storage.truncateLog("nonexistent"));
    }

    @Test
    void crc32IntegrityVerification() throws Exception {
        Path storeDir = tempDir.resolve("store");
        Storage storage = Storage.file(storeDir);

        storage.appendToLog("corrupt-log", "valid data".getBytes());

        Path walFile = storeDir.resolve("corrupt-log.wal");
        byte[] fileBytes = Files.readAllBytes(walFile);
        fileBytes[fileBytes.length - 1] ^= 0xFF;
        Files.write(walFile, fileBytes);

        assertThrows(UncheckedIOException.class, () -> storage.readLog("corrupt-log"));
    }

    @Test
    void crc32DetectsCorruptedData() throws Exception {
        Path storeDir = tempDir.resolve("store");
        Storage storage = Storage.file(storeDir);

        storage.appendToLog("corrupt-data", "some data here".getBytes());

        // Offset 12 is the first data byte: the 8-byte container header and the 4-byte frame
        // length precede it.
        Path walFile = storeDir.resolve("corrupt-data.wal");
        byte[] fileBytes = Files.readAllBytes(walFile);
        fileBytes[12] ^= 0xFF;
        Files.write(walFile, fileBytes);

        assertThrows(UncheckedIOException.class, () -> storage.readLog("corrupt-data"));
    }

    @Test
    void appendToLogWithLargeEntry() {
        Storage storage = Storage.file(tempDir.resolve("store"));

        byte[] largeEntry = new byte[64 * 1024];
        for (int i = 0; i < largeEntry.length; i++) {
            largeEntry[i] = (byte) (i & 0xFF);
        }

        storage.appendToLog("large-log", largeEntry);

        List<byte[]> entries = storage.readLog("large-log");
        assertEquals(1, entries.size());
        assertArrayEquals(largeEntry, entries.get(0));
    }

    @Test
    void createsDirectoryIfNotExists() {
        Path nested = tempDir.resolve("a/b/c");
        assertFalse(Files.exists(nested));

        Storage storage = Storage.file(nested);
        storage.put("key", "value".getBytes());

        assertTrue(Files.exists(nested));
        assertArrayEquals("value".getBytes(), storage.get("key"));
    }

    /**
     * Verifies that put() uses atomic rename so that a crash between truncation
     * and write completion does not corrupt the file. Checks that a temp file is
     * created and atomically renamed.
     */
    @Test
    void putUsesAtomicRename() throws Exception {
        Path storeDir = tempDir.resolve("atomic-test");
        Storage storage = Storage.file(storeDir);

        storage.put("raft-state", "term=5".getBytes());

        Path datFile = storeDir.resolve("raft-state.dat");
        assertTrue(Files.exists(datFile), "data file must exist after put");
        assertArrayEquals("term=5".getBytes(), Files.readAllBytes(datFile));

        Path tmpFile = storeDir.resolve("raft-state.dat.tmp");
        assertFalse(Files.exists(tmpFile),
                "temp file must not exist after successful put (atomic rename cleans it up)");

        storage.put("raft-state", "term=6".getBytes());
        assertArrayEquals("term=6".getBytes(), Files.readAllBytes(datFile));
        assertFalse(Files.exists(tmpFile));
    }

    @Test
    void syncDoesNotThrow() {
        Storage storage = Storage.file(tempDir.resolve("store"));
        assertDoesNotThrow(storage::sync);
    }

    @Test
    void factoryMethodCreatesFileStorage() {
        Storage storage = Storage.file(tempDir.resolve("factory"));
        assertInstanceOf(FileStorage.class, storage);
    }

    @Test
    void dataPersistsAcrossStorageInstances() {
        Path dir = tempDir.resolve("persist");

        Storage storage1 = Storage.file(dir);
        storage1.put("key", "value".getBytes());
        storage1.appendToLog("wal", "entry1".getBytes());
        storage1.appendToLog("wal", "entry2".getBytes());

        Storage storage2 = Storage.file(dir);
        assertArrayEquals("value".getBytes(), storage2.get("key"));

        List<byte[]> entries = storage2.readLog("wal");
        assertEquals(2, entries.size());
        assertArrayEquals("entry1".getBytes(), entries.get(0));
        assertArrayEquals("entry2".getBytes(), entries.get(1));
    }

    /**
     * WAL recovery must not crash on a truncated trailing entry.
     * <p>
     * A crash during appendToLog() can leave a partially written entry at the
     * end of the WAL file (length header written, but data and/or CRC incomplete).
     * readLog() must silently discard such trailing fragments and return the
     * previously committed (complete, CRC-valid) entries intact.
     */
    @Test
    void walRecoveryDiscardsTruncatedTrailingEntry() throws Exception {
        Path storeDir = tempDir.resolve("truncated-wal");
        Storage storage = Storage.file(storeDir);

        byte[] entry1 = "first-entry".getBytes();
        byte[] entry2 = "second-entry".getBytes();
        storage.appendToLog("recovery-log", entry1);
        storage.appendToLog("recovery-log", entry2);

        List<byte[]> beforeCorruption = storage.readLog("recovery-log");
        assertEquals(2, beforeCorruption.size());

        Path walFile = storeDir.resolve("recovery-log.wal");
        long originalSize = Files.size(walFile);

        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
            channel.truncate(originalSize - 3);
        }

        Storage freshStorage = Storage.file(storeDir);
        List<byte[]> recovered = freshStorage.readLog("recovery-log");

        assertEquals(1, recovered.size(),
                "readLog() should return 1 valid entry and discard the truncated trailing entry");
        assertArrayEquals(entry1, recovered.get(0),
                "The first (complete) entry should be preserved intact");
    }

    // The WAL recovery read must FAIL LOUD on a >= 2 GiB log rather than silently mis-size
    // the buffer via the (int) fileSize cast (which truncates/wraps and reads garbage -
    // silent committed-entry loss). Tested at the extracted size check so no 2 GiB file
    // is needed.
    @Test
    void checkedLogReadSizePassesBelowLimitAndFailsLoudAtOrAboveJvmArrayCap() {
        assertEquals(0, FileStorage.checkedLogReadSize("raft-log", 0L));
        assertEquals(1024, FileStorage.checkedLogReadSize("raft-log", 1024L));
        assertEquals((int) FileStorage.MAX_READABLE_LOG_BYTES,
                FileStorage.checkedLogReadSize("raft-log", FileStorage.MAX_READABLE_LOG_BYTES),
                "exactly at the limit still reads (no truncation)");

        long twoGiB = 1L << 31; // 2_147_483_648 > MAX_READABLE_LOG_BYTES - wraps negative under (int)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FileStorage.checkedLogReadSize("raft-log", twoGiB),
                "a >= 2 GiB WAL must refuse to load loudly, not silently truncate via the int cast");
        assertTrue(ex.getMessage().contains("raft-log") && ex.getMessage().contains("RR-005"),
                "the failure must name the log and the root cause: " + ex.getMessage());
        // Sanity: (int) 2GiB wraps negative - the exact silent-corruption cast the guard replaces.
        assertTrue((int) twoGiB < 0, "(int) 2GiB wraps negative");
    }


    private static byte[] validHeader() {
        ByteBuffer b = ByteBuffer.allocate(8);
        b.putInt(WalContainer.WAL_FILE_MAGIC);
        b.put(WalContainer.FILE_VERSION);
        b.put((byte) 0);
        b.putShort((short) 0);
        return b.array();
    }

    @Test
    void walContainerHeaderIsWrittenAndRoundTrips() throws Exception {
        Path storeDir = tempDir.resolve("hdr");
        Storage storage = Storage.file(storeDir);
        storage.appendToLog("raft-log", "entry-1".getBytes());
        storage.appendToLog("raft-log", "entry-2".getBytes());

        byte[] fileBytes = Files.readAllBytes(storeDir.resolve("raft-log.wal"));
        assertArrayEquals(validHeader(), java.util.Arrays.copyOf(fileBytes, 8),
                "the .wal file must begin with the 8-byte container header");

        List<byte[]> entries = storage.readLog("raft-log");
        assertEquals(2, entries.size(), "frames must round-trip across the header");
        assertArrayEquals("entry-1".getBytes(), entries.get(0));
        assertArrayEquals("entry-2".getBytes(), entries.get(1));
    }

    @Test
    void batchedAppendWritesHeaderAndRoundTrips() throws Exception {
        Path storeDir = tempDir.resolve("batched");
        Storage storage = Storage.file(storeDir);
        storage.appendToLogNoSync("raft-log", "b1".getBytes());
        storage.appendToLogNoSync("raft-log", "b2".getBytes());
        storage.syncLog("raft-log");

        byte[] fileBytes = Files.readAllBytes(storeDir.resolve("raft-log.wal"));
        assertArrayEquals(validHeader(), java.util.Arrays.copyOf(fileBytes, 8),
                "the batched path must also stamp the container header on a fresh file");

        List<byte[]> entries = storage.readLog("raft-log");
        assertEquals(2, entries.size());
        assertArrayEquals("b1".getBytes(), entries.get(0));
        assertArrayEquals("b2".getBytes(), entries.get(1));
    }

    @Test
    void emptyWalFileIsFresh() throws Exception {
        Path storeDir = tempDir.resolve("empty-wal");
        Storage storage = Storage.file(storeDir);
        Files.createDirectories(storeDir);
        Files.write(storeDir.resolve("raft-log.wal"), new byte[0]);

        assertTrue(storage.readLog("raft-log").isEmpty(),
                "a 0-byte .wal (first boot / torn) must read as fresh, not throw");
    }

    @Test
    void headerOnlyFileIsFresh() throws Exception {
        Path storeDir = tempDir.resolve("header-only");
        Storage storage = Storage.file(storeDir);
        Files.createDirectories(storeDir);
        Files.write(storeDir.resolve("raft-log.wal"), validHeader());

        assertTrue(storage.readLog("raft-log").isEmpty(),
                "a header-only .wal (crash after header, before first frame) must read as fresh");
    }

    @Test
    void walFileHeaderBadMagicRejected() throws Exception {
        Path storeDir = tempDir.resolve("bad-magic");
        Storage storage = Storage.file(storeDir);
        storage.appendToLog("raft-log", "entry".getBytes());

        Path walFile = storeDir.resolve("raft-log.wal");
        byte[] fileBytes = Files.readAllBytes(walFile);
        fileBytes[0] ^= 0xFF;
        Files.write(walFile, fileBytes);

        assertThrows(UncheckedIOException.class, () -> storage.readLog("raft-log"),
                "a bad container magic must fail closed (foreign/corrupt file refused)");
    }

    @Test
    void walFileHeaderHigherVersionRejected() throws Exception {
        Path storeDir = tempDir.resolve("higher-version");
        Storage storage = Storage.file(storeDir);
        storage.appendToLog("raft-log", "entry".getBytes());

        Path walFile = storeDir.resolve("raft-log.wal");
        byte[] fileBytes = Files.readAllBytes(walFile);
        fileBytes[4] = 2;
        Files.write(walFile, fileBytes);

        assertThrows(UncheckedIOException.class, () -> storage.readLog("raft-log"),
                "an unknown/higher container fileVersion must be refused (never parse newer with older grammar)");
    }

    @Test
    void nonZeroMbzHeaderByteRejected() throws Exception {
        Path storeDir = tempDir.resolve("mbz");
        Storage storage = Storage.file(storeDir);
        storage.appendToLog("raft-log", "entry".getBytes());

        Path walFile = storeDir.resolve("raft-log.wal");
        byte[] fileBytes = Files.readAllBytes(walFile);
        fileBytes[5] = 1; // flags byte is MBZ; a non-zero value must fail closed
        Files.write(walFile, fileBytes);

        assertThrows(UncheckedIOException.class, () -> storage.readLog("raft-log"),
                "a non-zero MBZ header byte must fail closed");
    }
}
