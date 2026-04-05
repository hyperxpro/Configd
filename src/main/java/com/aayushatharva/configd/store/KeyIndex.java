package com.aayushatharva.configd.store;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Key-only index for proxy nodes.
 *
 * Configd v1.5 optimization: proxies store all keys but persist values only for
 * cached keys. This reduces dataset size ~11x compared to full storage.
 *
 * Uses a Bloom filter for fast negative lookups (Configd sees ~10x more negative
 * lookups than positive ones). The Bloom filter is rebuilt periodically from the
 * underlying store's key set.
 */
public class KeyIndex {

    private static final Logger log = LoggerFactory.getLogger(KeyIndex.class);
    private static final int DEFAULT_EXPECTED_KEYS = 10_000_000;
    private static final double DEFAULT_FPP = 0.01;

    private final AtomicReference<BloomFilter<byte[]>> bloomFilter;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int expectedKeys;
    private final double falsePositiveProbability;
    private volatile int keyCount = 0;

    public KeyIndex() {
        this(DEFAULT_EXPECTED_KEYS, DEFAULT_FPP);
    }

    public KeyIndex(int expectedKeys, double falsePositiveProbability) {
        this.expectedKeys = expectedKeys;
        this.falsePositiveProbability = falsePositiveProbability;
        this.bloomFilter = new AtomicReference<>(createBloomFilter());
    }

    /** Returns true if the key MIGHT exist; false means it definitely does not. */
    public boolean mightContain(byte[] key) {
        return bloomFilter.get().mightContain(key);
    }

    /** Add a key to the index. */
    public void addKey(byte[] key) {
        lock.readLock().lock();
        try {
            bloomFilter.get().put(key);
            keyCount++;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Rebuild the Bloom filter from a fresh key set.
     * Called periodically or after significant changes.
     */
    public void rebuild(Iterable<byte[]> keys) {
        lock.writeLock().lock();
        try {
            var newFilter = createBloomFilter();
            int count = 0;
            for (byte[] key : keys) {
                newFilter.put(key);
                count++;
            }
            bloomFilter.set(newFilter);
            keyCount = count;
            log.info("Bloom filter rebuilt with {} keys, ~{} bytes",
                    count, newFilter.approximateElementCount());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getKeyCount() {
        return keyCount;
    }

    public double expectedFpp() {
        return bloomFilter.get().expectedFpp();
    }

    @SuppressWarnings("UnstableApiUsage")
    private BloomFilter<byte[]> createBloomFilter() {
        return BloomFilter.create(Funnels.byteArrayFunnel(), expectedKeys, falsePositiveProbability);
    }
}
