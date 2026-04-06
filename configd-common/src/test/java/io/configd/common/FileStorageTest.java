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

/**
 * Tests for {@link FileStorage}.
 */
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

        // Corrupt the CRC by modifying the last 4 bytes of the WAL file
        Path walFile = storeDir.resolve("corrupt-log.wal");
        byte[] fileBytes = Files.readAllBytes(walFile);
        // Flip a bit in the CRC (last 4 bytes)
        fileBytes[fileBytes.length - 1] ^= 0xFF;
        Files.write(walFile, fileBytes);

        assertThrows(UncheckedIOException.class, () -> storage.readLog("corrupt-log"));
    }

    @Test
    void crc32DetectsCorruptedData() throws Exception {
        Path storeDir = tempDir.resolve("store");
        Storage storage = Storage.file(storeDir);

        storage.appendToLog("corrupt-data", "some data here".getBytes());

        // Corrupt the data portion (byte at offset 4, after the length header)
        Path walFile = storeDir.resolve("corrupt-data.wal");
        byte[] fileBytes = Files.readAllBytes(walFile);
        fileBytes[4] ^= 0xFF;
        Files.write(walFile, fileBytes);

        assertThrows(UncheckedIOException.class, () -> storage.readLog("corrupt-data"));
    }

    @Test
    void appendToLogWithLargeEntry() {
        Storage storage = Storage.file(tempDir.resolve("store"));

        byte[] largeEntry = new byte[64 * 1024]; // 64 KB
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
     * Regression test for FIND-0006: put() must use atomic rename so that a
     * crash between truncation and write completion does not corrupt the file.
     * Verifies that a temp file is created and atomically renamed.
     */
    @Test
    void putUsesAtomicRename() throws Exception {
        Path storeDir = tempDir.resolve("atomic-test");
        Storage storage = Storage.file(storeDir);

        storage.put("raft-state", "term=5".getBytes());

        // The final file should exist
        Path datFile = storeDir.resolve("raft-state.dat");
        assertTrue(Files.exists(datFile), "data file must exist after put");
        assertArrayEquals("term=5".getBytes(), Files.readAllBytes(datFile));

        // The temp file should NOT exist (cleaned up by atomic rename)
        Path tmpFile = storeDir.resolve("raft-state.dat.tmp");
        assertFalse(Files.exists(tmpFile),
                "temp file must not exist after successful put (atomic rename cleans it up)");

        // Overwriting must also be atomic — verify data integrity
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

        // Create a new storage instance pointing at the same directory
        Storage storage2 = Storage.file(dir);
        assertArrayEquals("value".getBytes(), storage2.get("key"));

        List<byte[]> entries = storage2.readLog("wal");
        assertEquals(2, entries.size());
        assertArrayEquals("entry1".getBytes(), entries.get(0));
        assertArrayEquals("entry2".getBytes(), entries.get(1));
    }

    /**
     * Regression test for F-0011: WAL recovery must not crash on a truncated
     * trailing entry.
     * <p>
     * A crash during appendToLog() can leave a partially written entry at the
     * end of the WAL file (length header written, but data and/or CRC incomplete).
     * Before the fix, readLog() threw IOException on insufficient remaining bytes,
     * which prevented server restart entirely.
     * <p>
     * After the fix, truncated trailing entries are silently discarded. Previously
     * read entries with valid CRCs are preserved.
     */
    @Test
    void walRecoveryDiscardsTruncatedTrailingEntry() throws Exception {
        Path storeDir = tempDir.resolve("truncated-wal");
        Storage storage = Storage.file(storeDir);

        // Write 2 complete entries to the WAL
        byte[] entry1 = "first-entry".getBytes();
        byte[] entry2 = "second-entry".getBytes();
        storage.appendToLog("recovery-log", entry1);
        storage.appendToLog("recovery-log", entry2);

        // Verify both entries are readable before corruption
        List<byte[]> beforeCorruption = storage.readLog("recovery-log");
        assertEquals(2, beforeCorruption.size());

        // Simulate a crash mid-write by truncating the WAL file:
        // remove the last few bytes so the second entry's data+CRC is incomplete.
        // WAL format: [4-byte length][data][4-byte CRC32] per entry.
        Path walFile = storeDir.resolve("recovery-log.wal");
        long originalSize = Files.size(walFile);

        // Remove the last 3 bytes — this makes the second entry's CRC incomplete,
        // simulating a crash during the write of the second entry.
        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
            channel.truncate(originalSize - 3);
        }

        // Before the fix: readLog() would throw IOException/UncheckedIOException
        // because it tried to read beyond the buffer for the truncated entry.
        // After the fix: the truncated trailing entry is silently discarded,
        // and only the first (complete) entry is returned.
        Storage freshStorage = Storage.file(storeDir);
        List<byte[]> recovered = freshStorage.readLog("recovery-log");

        assertEquals(1, recovered.size(),
                "readLog() should return 1 valid entry and discard the truncated trailing entry");
        assertArrayEquals(entry1, recovered.get(0),
                "The first (complete) entry should be preserved intact");
    }
}
