package com.aayushatharva.configd.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Bloom-filter-backed key index.
 */
class KeyIndexTest {

    @Test
    void mightContainAfterAdd() {
        var index = new KeyIndex(1000, 0.01);

        byte[] key = "hello".getBytes(StandardCharsets.UTF_8);
        index.addKey(key);

        assertThat(index.mightContain(key)).isTrue();
    }

    @Test
    void definitelyNotContainForAbsent() {
        var index = new KeyIndex(1000, 0.01);

        // With a good Bloom filter, false positives should be rare
        int falsePositives = 0;
        for (int i = 0; i < 1000; i++) {
            if (index.mightContain(("absent-" + i).getBytes())) {
                falsePositives++;
            }
        }

        // FPP is 0.01, so expect ~10 false positives out of 1000
        assertThat(falsePositives).isLessThan(50);
    }

    @Test
    void rebuild() {
        var index = new KeyIndex(1000, 0.01);

        // Add some initial keys
        index.addKey("old-key".getBytes());

        // Rebuild with new key set
        var newKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 100; i++) {
            newKeys.add(("new-" + i).getBytes());
        }
        index.rebuild(newKeys);

        assertThat(index.getKeyCount()).isEqualTo(100);

        // New keys should be found
        assertThat(index.mightContain("new-0".getBytes())).isTrue();
        assertThat(index.mightContain("new-99".getBytes())).isTrue();
    }

    @Test
    void keyCount() {
        var index = new KeyIndex();
        assertThat(index.getKeyCount()).isEqualTo(0);

        index.addKey("a".getBytes());
        index.addKey("b".getBytes());
        assertThat(index.getKeyCount()).isEqualTo(2);
    }
}
