package io.configd.edge;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {

    @Nested
    class Construction {
        @Test
        void emptyFilterPassesEverything() {
            assertTrue(BloomFilter.EMPTY.mightContain("anything"));
            assertEquals(0, BloomFilter.EMPTY.size());
        }

        @Test
        void buildWithZeroSizeReturnsEmpty() {
            BloomFilter bf = BloomFilter.build(List.of(), 0);
            assertSame(BloomFilter.EMPTY, bf);
        }

        @Test
        void buildRetainsSize() {
            List<String> keys = List.of("a", "b", "c");
            BloomFilter bf = BloomFilter.build(keys, 3);
            assertEquals(3, bf.size());
        }
    }

    @Nested
    class MightContain {
        @Test
        void noFalseNegatives() {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                keys.add("key-" + i);
            }
            BloomFilter bf = BloomFilter.build(keys, keys.size());
            for (String key : keys) {
                assertTrue(bf.mightContain(key), "False negative for: " + key);
            }
        }

        @Test
        void falsePositiveRateBelowThreshold() {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 10_000; i++) {
                keys.add("key-" + i);
            }
            BloomFilter bf = BloomFilter.build(keys, keys.size());

            int falsePositives = 0;
            int tests = 100_000;
            for (int i = 0; i < tests; i++) {
                if (bf.mightContain("miss-" + i)) {
                    falsePositives++;
                }
            }
            double fpr = (double) falsePositives / tests;
            // Should be ~0.82% with 10 bits/element, allow up to 2%
            assertTrue(fpr < 0.02, "FPR too high: " + fpr);
        }

        @Test
        void definitelyAbsentKeysRejected() {
            List<String> keys = List.of("alpha", "beta", "gamma");
            BloomFilter bf = BloomFilter.build(keys, 3);
            // Not all misses will be rejected (FPR), but many should be
            int rejected = 0;
            for (int i = 0; i < 100; i++) {
                if (!bf.mightContain(UUID.randomUUID().toString())) {
                    rejected++;
                }
            }
            assertTrue(rejected > 50, "Too few rejections: " + rejected);
        }
    }

    @Nested
    class FalsePositiveRate {
        @Test
        void expectedFppIsReasonable() {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 1000; i++) keys.add("k" + i);
            BloomFilter bf = BloomFilter.build(keys, 1000);
            double fpp = bf.expectedFpp();
            assertTrue(fpp > 0 && fpp < 0.05, "FPP out of range: " + fpp);
        }
    }
}
