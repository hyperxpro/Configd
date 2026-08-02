package io.configd.edge;

/**
 * Rejects queries for keys definitely not present, avoiding the ~100ns HAMT traversal
 * when a ~10ns Bloom filter check suffices.
 * <p>
 * Uses double hashing (Kirsch &amp; Mitzenmacker) to derive every probe index from two
 * MurmurHash3 values instead of k independent hashes, without degrading the false-positive rate.
 * <p>
 * Immutable after construction; build a new instance to update.
 * <p>
 * Default of 10 bits/element and 7 hash functions gives an expected FPR of ~0.82%.
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

    public static BloomFilter build(Iterable<String> keys, int expectedSize, int bitsPerElement) {
        if (expectedSize <= 0) {
            return EMPTY;
        }
        int numBits = expectedSize * bitsPerElement;
        int numHashes = (int) Math.round((double) numBits / expectedSize * Math.log(2));
        if (numHashes < 1) numHashes = 1;
        long[] bits = new long[(numBits + 63) >>> 6];

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

    public static BloomFilter build(Iterable<String> keys, int expectedSize) {
        return build(keys, expectedSize, DEFAULT_BITS_PER_ELEMENT);
    }

    /** False negatives are impossible: a false answer means the key is definitely absent. */
    public boolean mightContain(String key) {
        if (numBits == 0) return true;
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

    public int size() { return size; }

    public double expectedFpp() {
        if (numBits == 0) return 1.0;
        return Math.pow(1.0 - Math.exp(-(double) numHashes * size / numBits), numHashes);
    }

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
