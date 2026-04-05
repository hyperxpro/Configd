package com.aayushatharva.configd.txlog;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the RocksDB-backed transaction log.
 */
class RocksDBTransactionLogTest {

    @TempDir
    Path tempDir;

    private RocksDBTransactionLog txLog;

    @BeforeEach
    void setUp() {
        txLog = new RocksDBTransactionLog(tempDir.resolve("txlog"));
    }

    @AfterEach
    void tearDown() throws Exception {
        txLog.close();
    }

    @Test
    void appendAndRead() {
        long seq1 = txLog.append(Operation.SET, "key1".getBytes(), "val1".getBytes());
        long seq2 = txLog.append(Operation.SET, "key2".getBytes(), "val2".getBytes());
        long seq3 = txLog.append(Operation.DELETE, "key1".getBytes(), null);

        assertThat(seq1).isEqualTo(1);
        assertThat(seq2).isEqualTo(2);
        assertThat(seq3).isEqualTo(3);

        List<TransactionLogEntry> entries = txLog.readAfter(0, 100);
        assertThat(entries).hasSize(3);

        assertThat(entries.get(0).operation()).isEqualTo(Operation.SET);
        assertThat(entries.get(0).key()).isEqualTo("key1".getBytes());
        assertThat(entries.get(2).operation()).isEqualTo(Operation.DELETE);
    }

    @Test
    void readAfterSequence() {
        txLog.append(Operation.SET, "a".getBytes(), "1".getBytes());
        txLog.append(Operation.SET, "b".getBytes(), "2".getBytes());
        txLog.append(Operation.SET, "c".getBytes(), "3".getBytes());

        List<TransactionLogEntry> entries = txLog.readAfter(1, 100);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).sequenceNumber()).isEqualTo(2);
        assertThat(entries.get(1).sequenceNumber()).isEqualTo(3);
    }

    @Test
    void readRange() {
        for (int i = 0; i < 10; i++) {
            txLog.append(Operation.SET, ("k" + i).getBytes(), ("v" + i).getBytes());
        }

        List<TransactionLogEntry> entries = txLog.readRange(3, 7);
        assertThat(entries).hasSize(5);
        assertThat(entries.get(0).sequenceNumber()).isEqualTo(3);
        assertThat(entries.get(4).sequenceNumber()).isEqualTo(7);
    }

    @Test
    void readWithLimit() {
        for (int i = 0; i < 100; i++) {
            txLog.append(Operation.SET, ("k" + i).getBytes(), ("v" + i).getBytes());
        }

        List<TransactionLogEntry> entries = txLog.readAfter(0, 5);
        assertThat(entries).hasSize(5);
    }

    @Test
    void latestSequence() {
        assertThat(txLog.getLatestSequence()).isEqualTo(0);

        txLog.append(Operation.SET, "a".getBytes(), "1".getBytes());
        assertThat(txLog.getLatestSequence()).isEqualTo(1);

        txLog.append(Operation.SET, "b".getBytes(), "2".getBytes());
        assertThat(txLog.getLatestSequence()).isEqualTo(2);
    }

    @Test
    void truncateBefore() {
        for (int i = 0; i < 10; i++) {
            txLog.append(Operation.SET, ("k" + i).getBytes(), ("v" + i).getBytes());
        }

        txLog.truncateBefore(5);

        List<TransactionLogEntry> entries = txLog.readAfter(0, 100);
        assertThat(entries).hasSize(6); // entries 5-10
        assertThat(entries.get(0).sequenceNumber()).isEqualTo(5);
    }

    @Test
    void snappyCompressionRoundTrip() {
        // Write a large entry to exercise Snappy compression
        byte[] largeValue = new byte[10000];
        java.util.Arrays.fill(largeValue, (byte) 'X');

        long seq = txLog.append(Operation.SET, "big".getBytes(), largeValue);

        List<TransactionLogEntry> entries = txLog.readAfter(seq - 1, 1);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).value()).isEqualTo(largeValue);
    }
}
