package com.aayushatharva.configd.txlog;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RocksDB-backed transaction log with Snappy compression.
 *
 * Keys in RocksDB are 8-byte big-endian sequence numbers, ensuring natural ordering.
 * Values are Snappy-compressed serialized {@link TransactionLogEntry} instances.
 */
public class RocksDBTransactionLog implements TransactionLog {

    private static final Logger log = LoggerFactory.getLogger(RocksDBTransactionLog.class);

    private final RocksDB db;
    private final Options options;
    private final AtomicLong sequenceCounter;
    private volatile long oldestSequence;

    static {
        RocksDB.loadLibrary();
    }

    public RocksDBTransactionLog(Path dataDir) {
        try {
            this.options = new Options()
                    .setCreateIfMissing(true)
                    .setWriteBufferSize(32 * 1024 * 1024)
                    .setMaxWriteBufferNumber(2)
                    .setCompactionStyle(CompactionStyle.FIFO);

            this.db = RocksDB.open(options, dataDir.toString());

            // Recover the latest sequence
            long latest = 0;
            long oldest = Long.MAX_VALUE;
            try (var iter = db.newIterator()) {
                iter.seekToLast();
                if (iter.isValid()) {
                    latest = decodeSequenceKey(iter.key());
                }
                iter.seekToFirst();
                if (iter.isValid()) {
                    oldest = decodeSequenceKey(iter.key());
                }
            }
            this.sequenceCounter = new AtomicLong(latest);
            this.oldestSequence = (oldest == Long.MAX_VALUE) ? 0 : oldest;

            log.info("Transaction log opened at {} — latest={}, oldest={}",
                    dataDir, latest, oldestSequence);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open transaction log at " + dataDir, e);
        }
    }

    @Override
    public long append(Operation operation, byte[] key, byte[] value) {
        long seq = sequenceCounter.incrementAndGet();
        var entry = new TransactionLogEntry(seq, operation, System.currentTimeMillis(), key, value);

        try {
            byte[] serialized = entry.serialize();
            byte[] compressed = Snappy.compress(serialized);

            var writeOpts = new WriteOptions().setSync(false);
            db.put(writeOpts, encodeSequenceKey(seq), compressed);
            writeOpts.close();

            return seq;
        } catch (RocksDBException | IOException e) {
            throw new RuntimeException("Failed to append to transaction log at seq=" + seq, e);
        }
    }

    @Override
    public List<TransactionLogEntry> readAfter(long afterSequence, int limit) {
        var entries = new ArrayList<TransactionLogEntry>();
        try (var iter = db.newIterator()) {
            iter.seek(encodeSequenceKey(afterSequence + 1));
            int count = 0;
            while (iter.isValid() && count < limit) {
                byte[] compressed = iter.value();
                byte[] serialized = Snappy.uncompress(compressed);
                entries.add(TransactionLogEntry.deserialize(serialized));
                iter.next();
                count++;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to decompress log entry", e);
        }
        return entries;
    }

    @Override
    public List<TransactionLogEntry> readRange(long fromSequence, long toSequence) {
        var entries = new ArrayList<TransactionLogEntry>();
        try (var iter = db.newIterator()) {
            iter.seek(encodeSequenceKey(fromSequence));
            while (iter.isValid()) {
                long seq = decodeSequenceKey(iter.key());
                if (seq > toSequence) break;

                byte[] compressed = iter.value();
                byte[] serialized = Snappy.uncompress(compressed);
                entries.add(TransactionLogEntry.deserialize(serialized));
                iter.next();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to decompress log entry", e);
        }
        return entries;
    }

    @Override
    public long getLatestSequence() {
        return sequenceCounter.get();
    }

    @Override
    public long getOldestSequence() {
        return oldestSequence;
    }

    @Override
    public void truncateBefore(long sequenceNumber) {
        try {
            db.deleteRange(encodeSequenceKey(0), encodeSequenceKey(sequenceNumber));
            oldestSequence = sequenceNumber;
            log.debug("Truncated transaction log entries before seq={}", sequenceNumber);
        } catch (RocksDBException e) {
            log.error("Failed to truncate transaction log", e);
        }
    }

    @Override
    public long size() {
        long latest = sequenceCounter.get();
        return (latest >= oldestSequence) ? (latest - oldestSequence) : 0;
    }

    @Override
    public void close() throws IOException {
        db.close();
        options.close();
        log.info("Transaction log closed");
    }

    private static byte[] encodeSequenceKey(long seq) {
        byte[] key = new byte[8];
        ByteBuffer.wrap(key).putLong(seq);
        return key;
    }

    private static long decodeSequenceKey(byte[] key) {
        return ByteBuffer.wrap(key).getLong();
    }
}
