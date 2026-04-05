package com.aayushatharva.configd.cache;

import com.aayushatharva.configd.store.KVEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Level 1 cache: local per-server cache.
 *
 * Individual caches on each proxy server containing recently accessed key-values.
 * Retention based on access recency (LRU). This is the highest performance tier.
 *
 * Uses an LRU eviction policy with soft and hard limits:
 * - Soft limit: triggers background eviction of least-recently-used entries
 * - Hard limit: temporarily stops accepting new cache entries
 */
public class LocalCache {

    private static final Logger log = LoggerFactory.getLogger(LocalCache.class);

    private final long maxBytes;
    private final double softLimitRatio;
    private final double hardLimitRatio;
    private final AtomicLong currentBytes = new AtomicLong(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // LRU via access-ordered LinkedHashMap
    private final LinkedHashMap<ByteArrayKey, CacheEntry> cache;

    public record CacheEntry(byte[] value, long version, long lastAccessTime, int sizeBytes) {}

    public LocalCache(long maxBytes, double softLimitRatio, double hardLimitRatio) {
        this.maxBytes = maxBytes;
        this.softLimitRatio = softLimitRatio;
        this.hardLimitRatio = hardLimitRatio;
        this.cache = new LinkedHashMap<>(1024, 0.75f, true); // access-order
    }

    /** Get a value from the cache. Returns null on miss. */
    public byte[] get(byte[] key) {
        lock.readLock().lock();
        try {
            var entry = cache.get(new ByteArrayKey(key));
            if (entry != null) {
                return entry.value();
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Get a value with version check. Returns null if cached version > requested version. */
    public byte[] get(byte[] key, long maxVersion) {
        lock.readLock().lock();
        try {
            var entry = cache.get(new ByteArrayKey(key));
            if (entry != null && entry.version() <= maxVersion) {
                return entry.value();
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Put a value into the cache. May be rejected if at hard limit. */
    public boolean put(byte[] key, byte[] value, long version) {
        int entrySize = key.length + (value != null ? value.length : 0) + 32; // 32 bytes overhead

        // Hard limit check
        if (currentBytes.get() + entrySize > maxBytes * hardLimitRatio) {
            return false;
        }

        lock.writeLock().lock();
        try {
            var bkey = new ByteArrayKey(key);
            var existing = cache.remove(bkey);
            if (existing != null) {
                currentBytes.addAndGet(-existing.sizeBytes());
            }

            var entry = new CacheEntry(value, version, System.currentTimeMillis(), entrySize);
            cache.put(bkey, entry);
            currentBytes.addAndGet(entrySize);

            // Soft limit eviction
            if (currentBytes.get() > maxBytes * softLimitRatio) {
                evictLRU();
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void invalidate(byte[] key) {
        lock.writeLock().lock();
        try {
            var removed = cache.remove(new ByteArrayKey(key));
            if (removed != null) {
                currentBytes.addAndGet(-removed.sizeBytes());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Evict entries until below the soft limit. Must be called with write lock held. */
    private void evictLRU() {
        long target = (long) (maxBytes * softLimitRatio * 0.9); // evict to 90% of soft limit
        var iterator = cache.entrySet().iterator();
        int evicted = 0;
        while (iterator.hasNext() && currentBytes.get() > target) {
            var entry = iterator.next();
            currentBytes.addAndGet(-entry.getValue().sizeBytes());
            iterator.remove();
            evicted++;
        }
        if (evicted > 0) {
            log.debug("Evicted {} entries from local cache", evicted);
        }
    }

    public long getCurrentBytes() {
        return currentBytes.get();
    }

    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public double utilizationRatio() {
        return (double) currentBytes.get() / maxBytes;
    }

    /** Wrapper for byte[] to use as HashMap key. */
    private record ByteArrayKey(byte[] data) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ByteArrayKey that)) return false;
            return java.util.Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(data);
        }
    }
}
