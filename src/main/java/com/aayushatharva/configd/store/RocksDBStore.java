package com.aayushatharva.configd.store;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RocksDB-backed KV store with two column families:
 * <ul>
 *   <li><b>default</b> — current values (latest version of each key)</li>
 *   <li><b>mvcc</b> — historical versions keyed as [key | version-bytes] for point-in-time reads</li>
 * </ul>
 *
 * Mirrors Configd's storage design: Bloom filters for fast negative lookups,
 * custom compaction for eviction, and MVCC for consistency across lagging proxies.
 */
public class RocksDBStore implements KVStore {

    private static final Logger log = LoggerFactory.getLogger(RocksDBStore.class);

    private static final String MVCC_CF_NAME = "mvcc";
    private static final String META_CF_NAME = "meta";
    private static final byte[] VERSION_META_KEY = "__current_version__".getBytes(StandardCharsets.UTF_8);

    private final RocksDB db;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle mvccCf;
    private final ColumnFamilyHandle metaCf;
    private final DBOptions dbOptions;
    private final List<ColumnFamilyHandle> cfHandles;
    private final AtomicLong currentVersion = new AtomicLong(0);
    private final Path dataDir;

    static {
        RocksDB.loadLibrary();
    }

    public RocksDBStore(Path dataDir) {
        this.dataDir = dataDir;
        try {
            this.dbOptions = new DBOptions()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true)
                    .setMaxBackgroundJobs(4)
                    .setKeepLogFileNum(5);

            var cfDescriptors = List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultCfOptions()),
                    new ColumnFamilyDescriptor(MVCC_CF_NAME.getBytes(), mvccCfOptions()),
                    new ColumnFamilyDescriptor(META_CF_NAME.getBytes(), new ColumnFamilyOptions())
            );

            this.cfHandles = new ArrayList<>();
            this.db = RocksDB.open(dbOptions, dataDir.toString(), cfDescriptors, cfHandles);
            this.defaultCf = cfHandles.get(0);
            this.mvccCf = cfHandles.get(1);
            this.metaCf = cfHandles.get(2);

            loadVersion();
            log.info("RocksDB opened at {} with version {}", dataDir, currentVersion.get());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open RocksDB at " + dataDir, e);
        }
    }

    private ColumnFamilyOptions defaultCfOptions() {
        var tableConfig = new BlockBasedTableConfig()
                .setFilterPolicy(new BloomFilter(10, false))
                .setBlockSize(16 * 1024)
                .setBlockCache(new LRUCache(64 * 1024 * 1024));
        return new ColumnFamilyOptions()
                .setTableFormatConfig(tableConfig)
                .setWriteBufferSize(64 * 1024 * 1024)
                .setMaxWriteBufferNumber(3)
                .setCompactionStyle(CompactionStyle.LEVEL);
    }

    private ColumnFamilyOptions mvccCfOptions() {
        var tableConfig = new BlockBasedTableConfig()
                .setFilterPolicy(new BloomFilter(10, false))
                .setBlockSize(16 * 1024);
        return new ColumnFamilyOptions()
                .setTableFormatConfig(tableConfig)
                .setWriteBufferSize(32 * 1024 * 1024)
                .setCompactionStyle(CompactionStyle.LEVEL);
    }

    private void loadVersion() throws RocksDBException {
        byte[] versionBytes = db.get(metaCf, VERSION_META_KEY);
        if (versionBytes != null) {
            currentVersion.set(ByteBuffer.wrap(versionBytes).getLong());
        }
    }

    private void persistVersion(long version) throws RocksDBException {
        byte[] buf = new byte[8];
        ByteBuffer.wrap(buf).putLong(version);
        db.put(metaCf, VERSION_META_KEY, buf);
    }

    @Override
    public byte[] get(byte[] key) {
        try {
            return db.get(defaultCf, key);
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB get failed", e);
        }
    }

    /**
     * MVCC read: returns the value of {@code key} as it existed at {@code version}.
     *
     * 1. Check the default CF — if the stored version <= requested version, return it.
     * 2. Otherwise scan the MVCC CF for the latest version <= requested version.
     */
    @Override
    public byte[] get(byte[] key, long version) {
        try {
            // Fast path: check default CF
            byte[] current = db.get(defaultCf, key);
            if (current != null) {
                // Peek at the MVCC CF to find the latest version for this key
                byte[] mvccKey = encodeMvccKey(key, currentVersion.get());
                byte[] mvccValue = db.get(mvccCf, mvccKey);
                if (mvccValue != null) {
                    long storedVersion = decodeMvccVersion(mvccKey);
                    if (storedVersion <= version) {
                        return current;
                    }
                }
            }

            // Slow path: scan MVCC for the correct historical version
            byte[] prefix = encodeMvccKeyPrefix(key);
            try (var iter = db.newIterator(mvccCf)) {
                // Seek to the largest version <= requested version
                byte[] seekKey = encodeMvccKey(key, version);
                iter.seekForPrev(seekKey);
                if (iter.isValid()) {
                    byte[] foundKey = iter.key();
                    if (startsWith(foundKey, prefix)) {
                        byte[] val = iter.value();
                        // Check if it's a tombstone (empty value signals deletion)
                        if (val.length == 0) {
                            return null;
                        }
                        return val;
                    }
                }
            }
            return current;
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB MVCC get failed", e);
        }
    }

    @Override
    public void put(byte[] key, byte[] value, long version) {
        try (var batch = new WriteBatch()) {
            // Write to default CF (current value)
            batch.put(defaultCf, key, value);
            // Write to MVCC CF (historical version)
            batch.put(mvccCf, encodeMvccKey(key, version), value);

            var writeOpts = new WriteOptions().setSync(false);
            db.write(writeOpts, batch);
            writeOpts.close();

            updateVersion(version);
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB put failed", e);
        }
    }

    @Override
    public void delete(byte[] key, long version) {
        try (var batch = new WriteBatch()) {
            batch.delete(defaultCf, key);
            // Tombstone in MVCC: empty value
            batch.put(mvccCf, encodeMvccKey(key, version), new byte[0]);

            var writeOpts = new WriteOptions().setSync(false);
            db.write(writeOpts, batch);
            writeOpts.close();

            updateVersion(version);
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB delete failed", e);
        }
    }

    @Override
    public List<KVEntry> scan(byte[] startKey, byte[] endKey, int limit) {
        var results = new ArrayList<KVEntry>();
        try (var iter = db.newIterator(defaultCf)) {
            iter.seek(startKey);
            int count = 0;
            while (iter.isValid() && count < limit) {
                byte[] key = iter.key();
                if (endKey != null && compareBytes(key, endKey) >= 0) break;
                results.add(KVEntry.of(key, iter.value(), currentVersion.get()));
                iter.next();
                count++;
            }
        }
        return results;
    }

    @Override
    public boolean containsKey(byte[] key) {
        try {
            // Use Bloom filter via keyMayExist for fast negative lookups
            var holder = new org.rocksdb.Holder<>(new byte[0]);
            boolean mayExist = db.keyMayExist(defaultCf, key, holder);
            if (!mayExist) return false;
            // Bloom filter says maybe — confirm with actual read
            return db.get(defaultCf, key) != null;
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB containsKey failed", e);
        }
    }

    @Override
    public long getCurrentVersion() {
        return currentVersion.get();
    }

    @Override
    public void setCurrentVersion(long version) {
        currentVersion.set(version);
        try {
            persistVersion(version);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to persist version", e);
        }
    }

    @Override
    public void compact() {
        try {
            db.compactRange(defaultCf);
            db.compactRange(mvccCf);
            log.info("Compaction completed");
        } catch (RocksDBException e) {
            log.error("Compaction failed", e);
        }
    }

    @Override
    public long approximateSize() {
        try {
            return Long.parseLong(db.getProperty(defaultCf, "rocksdb.estimate-num-keys"));
        } catch (RocksDBException e) {
            return -1;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            persistVersion(currentVersion.get());
        } catch (RocksDBException e) {
            log.warn("Failed to persist version on close", e);
        }
        for (var cfh : cfHandles) {
            cfh.close();
        }
        db.close();
        dbOptions.close();
        log.info("RocksDB closed at {}", dataDir);
    }

    // --- Internal helpers ---

    private void updateVersion(long version) {
        long current;
        do {
            current = currentVersion.get();
            if (version <= current) return;
        } while (!currentVersion.compareAndSet(current, version));

        try {
            persistVersion(version);
        } catch (RocksDBException e) {
            log.warn("Failed to persist version update", e);
        }
    }

    /**
     * MVCC key format: [original-key-bytes][0xFF delimiter][8-byte version big-endian]
     * Using 0xFF as delimiter allows natural ordering: higher versions sort later.
     */
    static byte[] encodeMvccKey(byte[] key, long version) {
        byte[] result = new byte[key.length + 1 + 8];
        System.arraycopy(key, 0, result, 0, key.length);
        result[key.length] = (byte) 0xFF;
        ByteBuffer.wrap(result, key.length + 1, 8).putLong(version);
        return result;
    }

    static byte[] encodeMvccKeyPrefix(byte[] key) {
        byte[] result = new byte[key.length + 1];
        System.arraycopy(key, 0, result, 0, key.length);
        result[key.length] = (byte) 0xFF;
        return result;
    }

    static long decodeMvccVersion(byte[] mvccKey) {
        return ByteBuffer.wrap(mvccKey, mvccKey.length - 8, 8).getLong();
    }

    private static boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) return false;
        }
        return true;
    }

    private static int compareBytes(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }
}
