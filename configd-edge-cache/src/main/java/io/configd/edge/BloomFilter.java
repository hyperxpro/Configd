package io.configd.edge;

/**
 * A space-efficient probabilistic data structure for fast negative lookups.
 * Used by the edge cache to quickly reject queries for keys that are
 * definitely not in the store — avoiding the ~100ns HAMT traversal
 * when a ~10ns Bloom filter check suffices.
 * <p>
 * This implementation uses double hashing (Kirsch & Mitzenmacker, 2006)
 * with MurmurHash3-derived seeds to minimize hash computation while
 * maintaining the theoretical false-positive rate.
 * <p>
 * <b>Thread safety:</b> Instances are immutable after construction.
 * To update the filter, create a new instance with {@link #rebuild(Iterable)}.
 * The immutability aligns with the RCU pattern used throughout the edge cache.
 * <p>
 * <b>False positive rate:</b> With the default of 10 bits per element
 * and 7 hash functions, the expected FPR is ~0.82%.
 *
 * @see LocalConfigStore
 */
public final class BloomFilter {

    private static final int DEFAULT_BITS_PER_ELEMENT = 10;
    private static final int DEFAULT_NUM_HASHES = 7;

    private final long[] bits;
    private final int numBits;
    private final int numHashes;
    private final int size;

    /** Empty filter that rejects nothing (mightContain always returns true). */
    public static final BloomFilter EMPTY = new BloomFilter(new long[0], 0, 0, 0);

    private BloomFilter(long[] bits, int numBits, int numHashes, int size) {
        this.bits = bits;
        this.numBits = numBits;
        this.numHashes = numHashes;
        this.size = size;
    }

    /**
     * Builds a Bloom filter from the given keys.
     *
     * @param keys the keys to insert
     * @param expectedSize the expected number of keys (for sizing)
     * @param bitsPerElement bits per element (higher = lower FPR)
     * @return a new immutable Bloom filter
     */
    public static BloomFilter build(Iterable<String> keys, int expectedSize, int bitsPerElement) {
        if (expectedSize <= 0) {
            return EMPTY;
        }
        int numBits = expectedSize * bitsPerElement;
        int numHashes = (int) Math.round((double) numBits / expectedSize * Math.log(2));
        if (numHashes < 1) numHashes = 1;
        long[] bits = new long[(numBits + 63) >>> 6]; // ceil division by 64

        int count = 0;
        for (String key : keys) {
            int h1 = murmurHash3(key, 0);
            int h2 = murmurHash3(key, h1);
            for (int i = 0; i < numHashes; i++) {
                int idx = Math.floorMod(h1 + i * h2, numBits);
                bits[idx >>> 6] |= 1L << (idx & 63);
            }
            count++;
        }
        return new BloomFilter(bits, numBits, numHashes, count);
    }

    /**
     * Builds with default parameters (10 bits/element, 7 hashes).
     */
    public static BloomFilter build(Iterable<String> keys, int expectedSize) {
        return build(keys, expectedSize, DEFAULT_BITS_PER_ELEMENT);
    }

    /**
     * Returns true if the key MIGHT be in the set, false if DEFINITELY NOT.
     * <p>
     * False negatives are impossible. False positives occur at ~0.82% rate
     * with default parameters.
     *
     * @param key the key to test
     * @return true if possibly present, false if definitely absent
     */
    public boolean mightContain(String key) {
        if (numBits == 0) return true; // empty filter: pass-through
        int h1 = murmurHash3(key, 0);
        int h2 = murmurHash3(key, h1);
        for (int i = 0; i < numHashes; i++) {
            int idx = Math.floorMod(h1 + i * h2, numBits);
            if ((bits[idx >>> 6] & (1L << (idx & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    /** Returns the number of elements inserted during construction. */
    public int size() { return size; }

    /** Returns the expected false positive rate. */
    public double expectedFpp() {
        if (numBits == 0) return 1.0;
        return Math.pow(1.0 - Math.exp(-(double) numHashes * size / numBits), numHashes);
    }

    /**
     * MurmurHash3 finalization mix (32-bit). Used for double hashing.
     */
    private static int murmurHash3(String key, int seed) {
        int h = seed;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0xcc9e2d51;
            h = Integer.rotateLeft(h, 15);
            h *= 0x1b873593;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }
        h ^= key.length();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
