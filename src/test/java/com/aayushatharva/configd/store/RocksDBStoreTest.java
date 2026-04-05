package com.aayushatharva.configd.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the RocksDB-backed KV store with MVCC.
 */
class RocksDBStoreTest {

    @TempDir
    Path tempDir;

    private RocksDBStore store;

    @BeforeEach
    void setUp() {
        store = new RocksDBStore(tempDir.resolve("testdb"));
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    @Test
    void putAndGet() {
        byte[] key = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] value = "world".getBytes(StandardCharsets.UTF_8);

        store.put(key, value, 1);

        assertThat(store.get(key)).isEqualTo(value);
    }

    @Test
    void getReturnsNullForMissingKey() {
        assertThat(store.get("nonexistent".getBytes())).isNull();
    }

    @Test
    void deleteRemovesKey() {
        byte[] key = "toDelete".getBytes();
        store.put(key, "value".getBytes(), 1);
        assertThat(store.get(key)).isNotNull();

        store.delete(key, 2);
        assertThat(store.get(key)).isNull();
    }

    @Test
    void versionTracking() {
        store.put("a".getBytes(), "1".getBytes(), 5);
        assertThat(store.getCurrentVersion()).isEqualTo(5);

        store.put("b".getBytes(), "2".getBytes(), 10);
        assertThat(store.getCurrentVersion()).isEqualTo(10);

        // Earlier version should not regress
        store.put("c".getBytes(), "3".getBytes(), 7);
        assertThat(store.getCurrentVersion()).isEqualTo(10);
    }

    @Test
    void mvccRead() {
        byte[] key = "config".getBytes();
        store.put(key, "v1".getBytes(), 1);
        store.put(key, "v2".getBytes(), 5);
        store.put(key, "v3".getBytes(), 10);

        // Latest value
        assertThat(new String(store.get(key))).isEqualTo("v3");

        // Historical MVCC reads
        byte[] atV5 = store.get(key, 5);
        assertThat(atV5).isNotNull();
        assertThat(new String(atV5)).isEqualTo("v2");

        byte[] atV1 = store.get(key, 1);
        assertThat(atV1).isNotNull();
        assertThat(new String(atV1)).isEqualTo("v1");
    }

    @Test
    void containsKeyUsesBloomFilter() {
        store.put("exists".getBytes(), "yes".getBytes(), 1);

        assertThat(store.containsKey("exists".getBytes())).isTrue();
        assertThat(store.containsKey("nope".getBytes())).isFalse();
    }

    @Test
    void rangeScan() {
        store.put("a".getBytes(), "1".getBytes(), 1);
        store.put("b".getBytes(), "2".getBytes(), 2);
        store.put("c".getBytes(), "3".getBytes(), 3);
        store.put("d".getBytes(), "4".getBytes(), 4);

        List<KVEntry> results = store.scan("b".getBytes(), "d".getBytes(), 100);
        assertThat(results).hasSize(2);
        assertThat(new String(results.get(0).key())).isEqualTo("b");
        assertThat(new String(results.get(1).key())).isEqualTo("c");
    }

    @Test
    void scanWithLimit() {
        for (int i = 0; i < 100; i++) {
            store.put(String.format("key%03d", i).getBytes(), ("val" + i).getBytes(), i + 1);
        }

        List<KVEntry> results = store.scan("key".getBytes(), null, 5);
        assertThat(results).hasSize(5);
    }

    @Test
    void compactDoesNotLoseData() {
        store.put("persist".getBytes(), "me".getBytes(), 1);
        store.compact();
        assertThat(store.get("persist".getBytes())).isEqualTo("me".getBytes());
    }

    @Test
    void mvccKeyEncoding() {
        byte[] key = "test".getBytes();
        long version = 42;
        byte[] encoded = RocksDBStore.encodeMvccKey(key, version);
        long decoded = RocksDBStore.decodeMvccVersion(encoded);
        assertThat(decoded).isEqualTo(version);
    }
}
