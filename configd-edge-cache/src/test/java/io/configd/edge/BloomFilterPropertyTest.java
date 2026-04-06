package io.configd.edge;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link BloomFilter}. The fundamental property:
 * a Bloom filter NEVER has false negatives.
 */
class BloomFilterPropertyTest {

    @Property(tries = 500)
    void noFalseNegatives(
            @ForAll List<@StringLength(min = 1, max = 50) String> keys) {
        Assume.that(!keys.isEmpty());
        BloomFilter bf = BloomFilter.build(keys, keys.size());
        for (String key : keys) {
            assertTrue(bf.mightContain(key),
                    "False negative for key: " + key);
        }
    }

    @Property(tries = 100)
    void sizeMatchesInsertedCount(
            @ForAll @IntRange(min = 1, max = 1000) int n) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) keys.add("key-" + i);
        BloomFilter bf = BloomFilter.build(keys, n);
        assertEquals(n, bf.size());
    }

    @Property(tries = 100)
    void falsePositiveRateBounded(
            @ForAll @IntRange(min = 100, max = 5000) int n) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) keys.add("inserted-" + i);
        BloomFilter bf = BloomFilter.build(keys, n);

        int fp = 0;
        int tests = n * 10;
        for (int i = 0; i < tests; i++) {
            if (bf.mightContain("absent-" + i)) fp++;
        }
        double fpr = (double) fp / tests;
        // With 10 bits/element, theoretical FPR is ~0.82%. Allow up to 3%.
        assertTrue(fpr < 0.03, "FPR too high: " + fpr + " for n=" + n);
    }
}
