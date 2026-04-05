package com.aayushatharva.configd.cache;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the L1 local cache with LRU eviction.
 */
class LocalCacheTest {

    @Test
    void putAndGet() {
        var cache = new LocalCache(1024 * 1024, 0.8, 0.95);

        byte[] key = "key1".getBytes(StandardCharsets.UTF_8);
        byte[] value = "value1".getBytes(StandardCharsets.UTF_8);

        cache.put(key, value, 1);
        assertThat(cache.get(key)).isEqualTo(value);
    }

    @Test
    void missReturnsNull() {
        var cache = new LocalCache(1024 * 1024, 0.8, 0.95);
        assertThat(cache.get("missing".getBytes())).isNull();
    }

    @Test
    void versionCheck() {
        var cache = new LocalCache(1024 * 1024, 0.8, 0.95);
        byte[] key = "config".getBytes();
        cache.put(key, "v2".getBytes(), 5);

        // Should return value when maxVersion >= cached version
        assertThat(cache.get(key, 5)).isEqualTo("v2".getBytes());
        assertThat(cache.get(key, 10)).isEqualTo("v2".getBytes());

        // Should return null when maxVersion < cached version
        assertThat(cache.get(key, 3)).isNull();
    }

    @Test
    void lruEviction() {
        // Small cache that forces eviction
        var cache = new LocalCache(200, 0.5, 0.9);

        // Fill the cache
        for (int i = 0; i < 20; i++) {
            cache.put(("key" + i).getBytes(), ("value" + i).getBytes(), i);
        }

        // Cache should have evicted some entries
        assertThat(cache.size()).isLessThan(20);
    }

    @Test
    void hardLimitRejectsWrites() {
        // Very small cache
        var cache = new LocalCache(100, 0.5, 0.7);

        // Fill past hard limit
        for (int i = 0; i < 100; i++) {
            cache.put(("key" + i).getBytes(), ("value" + i).getBytes(), i);
        }

        // At some point writes should be rejected
        assertThat(cache.getCurrentBytes()).isLessThan(200);
    }

    @Test
    void invalidate() {
        var cache = new LocalCache(1024 * 1024, 0.8, 0.95);
        byte[] key = "temp".getBytes();

        cache.put(key, "data".getBytes(), 1);
        assertThat(cache.get(key)).isNotNull();

        cache.invalidate(key);
        assertThat(cache.get(key)).isNull();
    }

    @Test
    void utilizationRatio() {
        var cache = new LocalCache(1024, 0.8, 0.95);
        assertThat(cache.utilizationRatio()).isEqualTo(0.0);

        cache.put("k".getBytes(), "v".getBytes(), 1);
        assertThat(cache.utilizationRatio()).isGreaterThan(0.0);
    }
}
