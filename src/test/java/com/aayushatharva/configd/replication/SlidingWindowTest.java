package com.aayushatharva.configd.replication;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the sliding window used by proxy nodes.
 */
class SlidingWindowTest {

    @Test
    void addAndGet() {
        var window = new SlidingWindow(60_000);

        byte[] key = "config-key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "config-value".getBytes(StandardCharsets.UTF_8);

        window.add(key, value, 1);

        assertThat(window.get(key)).isEqualTo(value);
        assertThat(window.size()).isEqualTo(1);
    }

    @Test
    void getLatestVersion() {
        var window = new SlidingWindow(60_000);

        byte[] key = "key".getBytes();
        window.add(key, "v1".getBytes(), 1);
        window.add(key, "v2".getBytes(), 2);
        window.add(key, "v3".getBytes(), 3);

        // Should return the latest version (last added)
        assertThat(new String(window.get(key))).isEqualTo("v3");
    }

    @Test
    void missReturnsNull() {
        var window = new SlidingWindow(60_000);
        assertThat(window.get("missing".getBytes())).isNull();
    }

    @Test
    void getEntriesAfterVersion() {
        var window = new SlidingWindow(60_000);

        window.add("a".getBytes(), "1".getBytes(), 1);
        window.add("b".getBytes(), "2".getBytes(), 2);
        window.add("c".getBytes(), "3".getBytes(), 3);

        var entries = window.getEntriesAfter(1);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).version()).isEqualTo(2);
        assertThat(entries.get(1).version()).isEqualTo(3);
    }

    @Test
    void isProtected() {
        var window = new SlidingWindow(60_000);

        byte[] key = "protected-key".getBytes();
        window.add(key, "value".getBytes(), 1);

        assertThat(window.isProtected(key)).isTrue();
        assertThat(window.isProtected("other".getBytes())).isFalse();
    }

    @Test
    void expiredEntriesAreEvicted() throws InterruptedException {
        var window = new SlidingWindow(50); // 50ms window

        window.add("old".getBytes(), "data".getBytes(), 1);

        // Wait for expiration
        Thread.sleep(100);

        window.evictExpired();
        assertThat(window.size()).isEqualTo(0);
        assertThat(window.get("old".getBytes())).isNull();
    }
}
