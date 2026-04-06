package io.configd.store;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Persistent (immutable) Hash Array Mapped Trie with structural sharing.
 * <p>
 * All mutation operations ({@link #put}, {@link #remove}) return a new
 * {@code HamtMap} instance sharing structure with the original. The original
 * is never modified. This makes the data structure inherently thread-safe
 * for concurrent reads with no synchronization.
 * <p>
 * <b>Branching factor:</b> 32 (5 bits per level of the hash code).<br>
 * <b>Depth for 10^6 keys:</b> ~4 levels.<br>
 * <b>Depth for 10^9 keys:</b> ~6 levels.<br>
 * <b>Read path:</b> zero allocation (no iterators, no autoboxing, no temporaries).
 *
 * <h3>Internal node types</h3>
 * <ul>
 *   <li>{@link BitmapIndexedNode} — sparse array with a 32-bit bitmap indicating
 *       which of the 32 possible hash-fragment slots are occupied.</li>
 *   <li>{@link ArrayNode} — full 32-element array used when a bitmap node
 *       exceeds 16 children, avoiding bitCount overhead for dense levels.</li>
 *   <li>{@link CollisionNode} — flat array for keys sharing the same full
 *       32-bit hash code (extremely rare with good hash functions).</li>
 * </ul>
 *
 * @param <K> key type (must have proper {@code hashCode}/{@code equals})
 * @param <V> value type
 */
public final class HamtMap<K, V> {

    // -- Constants ----------------------------------------------------------

    /** Bits consumed per trie level. */
    private static final int BITS = 5;

    /** Mask for extracting a hash fragment: 0x1F. */
    private static final int MASK = (1 << BITS) - 1;

    /** Width of each level (2^5 = 32). */
    private static final int WIDTH = 1 << BITS;

    /** Promote bitmap node to array node above this child count. */
    private static final int PROMOTE_THRESHOLD = 16;

    /** Demote array node to bitmap node at or below this child count. */
    private static final int DEMOTE_THRESHOLD = 8;

    // -- Singleton empty map ------------------------------------------------

    private static final HamtMap<?, ?> EMPTY = new HamtMap<>(null, 0);

    // -- Fields -------------------------------------------------------------

    private final Node<K, V> root;
    private final int size;

    private HamtMap(Node<K, V> root, int size) {
        this.root = root;
        this.size = size;
    }

    /** Returns an empty, immutable {@code HamtMap}. */
    @SuppressWarnings("unchecked")
    public static <K, V> HamtMap<K, V> empty() {
        return (HamtMap<K, V>) EMPTY;
    }

    // -- Public API ---------------------------------------------------------

    /** Number of key-value pairs. */
    public int size() {
        return size;
    }

    /** True if empty. */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns the value for {@code key}, or {@code null} if absent.
     * Zero-allocation: no objects are created on this path.
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key must not be null");
        if (root == null) {
            return null;
        }
        return root.get(key, spread(key.hashCode()), 0);
    }

    /** True if this map contains a mapping for {@code key}. */
    public boolean containsKey(K key) {
        return get(key) != null;
    }

    /**
     * Returns a new map with the key-value pair added or updated.
     * Only nodes on the path from root to the affected leaf are copied;
     * everything else is shared with this map.
     */
    public HamtMap<K, V> put(K key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        int hash = spread(key.hashCode());
        var sc = new SizeChange();
        Node<K, V> base = (root != null) ? root : BitmapIndexedNode.<K, V>empty();
        Node<K, V> newRoot = base.put(key, value, hash, 0, sc);
        if (newRoot == base && sc.delta == 0) {
            return this; // value unchanged — zero allocation
        }
        return new HamtMap<>(newRoot, size + sc.delta);
    }

    /**
     * Returns a new map with the key removed. If the key is absent,
     * returns {@code this} (zero allocation on no-op).
     */
    public HamtMap<K, V> remove(K key) {
        Objects.requireNonNull(key, "key must not be null");
        if (root == null) {
            return this;
        }
        int hash = spread(key.hashCode());
        var sc = new SizeChange();
        Node<K, V> newRoot = root.remove(key, hash, 0, sc);
        if (sc.delta == 0) {
            return this;
        }
        if (newRoot == null) {
            return empty();
        }
        return new HamtMap<>(newRoot, size + sc.delta);
    }

    /** Iterates all entries in unspecified order. */
    public void forEach(BiConsumer<K, V> action) {
        Objects.requireNonNull(action, "action must not be null");
        if (root != null) {
            root.forEach(action);
        }
    }

    // -- Hash spreading -----------------------------------------------------

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    // -- Size delta carrier -------------------------------------------------

    static final class SizeChange {
        int delta;
    }

    // -- Node sealed hierarchy ----------------------------------------------

    sealed interface Node<K, V>
            permits BitmapIndexedNode, ArrayNode, CollisionNode {

        V get(K key, int hash, int shift);

        Node<K, V> put(K key, V value, int hash, int shift, SizeChange sc);

        Node<K, V> remove(K key, int hash, int shift, SizeChange sc);

        void forEach(BiConsumer<K, V> action);
    }

    // -----------------------------------------------------------------------
    // BitmapIndexedNode
    // -----------------------------------------------------------------------

    /**
     * Sparse trie node. A 32-bit {@code bitmap} marks which of the 32
     * hash-fragment slots are occupied. The dense {@code array} stores
     * entries as interleaved (key, value-or-node) pairs:
     * <ul>
     *   <li>Inline leaf: {@code array[2*i] = key}, {@code array[2*i+1] = value}</li>
     *   <li>Sub-node:    {@code array[2*i] = null}, {@code array[2*i+1] = Node}</li>
     * </ul>
     * The physical index for a bitmap slot is: {@code Integer.bitCount(bitmap & (bit - 1))}.
     */
    static final class BitmapIndexedNode<K, V> implements Node<K, V> {

        final int bitmap;
        final Object[] array;

        BitmapIndexedNode(int bitmap, Object[] array) {
            this.bitmap = bitmap;
            this.array = array;
        }

        static <K, V> BitmapIndexedNode<K, V> empty() {
            return new BitmapIndexedNode<>(0, new Object[0]);
        }

        private int index(int bit) {
            return Integer.bitCount(bitmap & (bit - 1));
        }

        // -- get (zero allocation) -----------------------------------------

        @Override
        @SuppressWarnings("unchecked")
        public V get(K key, int hash, int shift) {
            int frag = (hash >>> shift) & MASK;
            int bit = 1 << frag;
            if ((bitmap & bit) == 0) {
                return null;
            }
            int idx = index(bit);
            Object k = array[2 * idx];
            Object v = array[2 * idx + 1];
            if (k == null) {
                return ((Node<K, V>) v).get(key, hash, shift + BITS);
            }
            return key.equals(k) ? (V) v : null;
        }

        // -- put -----------------------------------------------------------

        @Override
        @SuppressWarnings("unchecked")
        public Node<K, V> put(K key, V value, int hash, int shift, SizeChange sc) {
            int frag = (hash >>> shift) & MASK;
            int bit = 1 << frag;
            int idx = index(bit);

            if ((bitmap & bit) == 0) {
                // Empty slot — insert inline leaf
                sc.delta = 1;
                int n = Integer.bitCount(bitmap);
                if (n >= PROMOTE_THRESHOLD) {
                    return promoteAndInsert(key, value, hash, shift, bit);
                }
                Object[] dst = new Object[2 * (n + 1)];
                System.arraycopy(array, 0, dst, 0, 2 * idx);
                dst[2 * idx] = key;
                dst[2 * idx + 1] = value;
                System.arraycopy(array, 2 * idx, dst, 2 * (idx + 1), 2 * (n - idx));
                return new BitmapIndexedNode<>(bitmap | bit, dst);
            }

            Object existingKey = array[2 * idx];
            Object existingVal = array[2 * idx + 1];

            if (existingKey == null) {
                // Sub-node — recurse
                Node<K, V> child = (Node<K, V>) existingVal;
                Node<K, V> newChild = child.put(key, value, hash, shift + BITS, sc);
                if (newChild == child) {
                    return this;
                }
                return cloneAndSet(2 * idx + 1, newChild);
            }

            K ek = (K) existingKey;
            if (key.equals(ek)) {
                // Same key — update value in place
                if (value.equals(existingVal)) {
                    return this;
                }
                sc.delta = 0;
                return cloneAndSet(2 * idx + 1, value);
            }

            // Different key, same hash fragment at this level — merge
            sc.delta = 1;
            int existingHash = spread(ek.hashCode());
            Node<K, V> merged = mergeLeaves(shift + BITS,
                    ek, (V) existingVal, existingHash,
                    key, value, hash);
            return cloneAndSetNode(2 * idx, merged);
        }

        // -- remove --------------------------------------------------------

        @Override
        @SuppressWarnings("unchecked")
        public Node<K, V> remove(K key, int hash, int shift, SizeChange sc) {
            int frag = (hash >>> shift) & MASK;
            int bit = 1 << frag;
            if ((bitmap & bit) == 0) {
                return this;
            }
            int idx = index(bit);
            Object k = array[2 * idx];
            Object v = array[2 * idx + 1];

            if (k == null) {
                Node<K, V> child = (Node<K, V>) v;
                Node<K, V> newChild = child.remove(key, hash, shift + BITS, sc);
                if (newChild == child) {
                    return this;
                }
                if (newChild == null) {
                    return removePair(bit, idx);
                }
                // If the child collapsed to a single inline leaf, pull it up
                if (newChild instanceof BitmapIndexedNode<K, V> bm
                        && Integer.bitCount(bm.bitmap) == 1
                        && bm.array[0] != null) {
                    return cloneAndSetInline(2 * idx, bm.array[0], 2 * idx + 1, bm.array[1]);
                }
                return cloneAndSet(2 * idx + 1, newChild);
            }

            if (key.equals(k)) {
                sc.delta = -1;
                return removePair(bit, idx);
            }
            return this;
        }

        // -- forEach -------------------------------------------------------

        @Override
        @SuppressWarnings("unchecked")
        public void forEach(BiConsumer<K, V> action) {
            int n = Integer.bitCount(bitmap);
            for (int i = 0; i < n; i++) {
                Object k = array[2 * i];
                Object v = array[2 * i + 1];
                if (k == null) {
                    ((Node<K, V>) v).forEach(action);
                } else {
                    action.accept((K) k, (V) v);
                }
            }
        }

        // -- internal helpers ----------------------------------------------

        private BitmapIndexedNode<K, V> cloneAndSet(int pos, Object val) {
            Object[] c = array.clone();
            c[pos] = val;
            return new BitmapIndexedNode<>(bitmap, c);
        }

        private BitmapIndexedNode<K, V> cloneAndSetNode(int pos, Node<K, V> node) {
            Object[] c = array.clone();
            c[pos] = null;       // key slot = null means sub-node
            c[pos + 1] = node;   // value slot = the sub-node
            return new BitmapIndexedNode<>(bitmap, c);
        }

        private BitmapIndexedNode<K, V> cloneAndSetInline(int posK, Object key,
                                                           int posV, Object val) {
            Object[] c = array.clone();
            c[posK] = key;
            c[posV] = val;
            return new BitmapIndexedNode<>(bitmap, c);
        }

        private Node<K, V> removePair(int bit, int idx) {
            int n = Integer.bitCount(bitmap);
            if (n == 1) {
                return null;
            }
            Object[] dst = new Object[2 * (n - 1)];
            System.arraycopy(array, 0, dst, 0, 2 * idx);
            System.arraycopy(array, 2 * (idx + 1), dst, 2 * idx, 2 * (n - idx - 1));
            return new BitmapIndexedNode<>(bitmap ^ bit, dst);
        }

        /**
         * Promotes this bitmap node to an ArrayNode and inserts a new leaf.
         * Called when child count exceeds {@link #PROMOTE_THRESHOLD}.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private ArrayNode<K, V> promoteAndInsert(K newKey, V newValue, int newHash,
                                                  int shift, int newBit) {
            Node<K, V>[] children = new Node[WIDTH];
            int n = Integer.bitCount(bitmap);
            int j = 0;
            for (int i = 0; i < WIDTH; i++) {
                int iBit = 1 << i;
                if ((bitmap & iBit) != 0) {
                    Object k = array[2 * j];
                    Object v = array[2 * j + 1];
                    if (k == null) {
                        children[i] = (Node<K, V>) v;
                    } else {
                        // Wrap inline leaf as a single-entry BitmapIndexedNode
                        // at the next shift level
                        children[i] = liftLeaf((K) k, (V) v, shift + BITS);
                    }
                    j++;
                }
            }
            int newFrag = Integer.numberOfTrailingZeros(newBit);
            children[newFrag] = liftLeaf(newKey, newValue, shift + BITS);
            return new ArrayNode<>(n + 1, children);
        }
    }

    // -----------------------------------------------------------------------
    // ArrayNode
    // -----------------------------------------------------------------------

    /**
     * Full 32-slot node. Direct array indexing (O(1), no bitCount).
     * Used when a BitmapIndexedNode would have > {@value PROMOTE_THRESHOLD}
     * children.
     */
    static final class ArrayNode<K, V> implements Node<K, V> {

        private final int count;
        private final Node<K, V>[] children;

        ArrayNode(int count, Node<K, V>[] children) {
            this.count = count;
            this.children = children;
        }

        @Override
        public V get(K key, int hash, int shift) {
            int frag = (hash >>> shift) & MASK;
            Node<K, V> child = children[frag];
            return (child == null) ? null : child.get(key, hash, shift + BITS);
        }

        @Override
        public Node<K, V> put(K key, V value, int hash, int shift, SizeChange sc) {
            int frag = (hash >>> shift) & MASK;
            Node<K, V> child = children[frag];
            if (child == null) {
                sc.delta = 1;
                Node<K, V> leaf = liftLeaf(key, value, shift + BITS);
                return replaceChild(frag, leaf, count + 1);
            }
            Node<K, V> newChild = child.put(key, value, hash, shift + BITS, sc);
            if (newChild == child) {
                return this;
            }
            return replaceChild(frag, newChild, count);
        }

        @Override
        public Node<K, V> remove(K key, int hash, int shift, SizeChange sc) {
            int frag = (hash >>> shift) & MASK;
            Node<K, V> child = children[frag];
            if (child == null) {
                return this;
            }
            Node<K, V> newChild = child.remove(key, hash, shift + BITS, sc);
            if (newChild == child) {
                return this;
            }
            if (newChild == null) {
                int newCount = count - 1;
                if (newCount <= DEMOTE_THRESHOLD) {
                    return demote(frag, newCount);
                }
                return replaceChild(frag, null, newCount);
            }
            return replaceChild(frag, newChild, count);
        }

        @Override
        public void forEach(BiConsumer<K, V> action) {
            for (Node<K, V> child : children) {
                if (child != null) {
                    child.forEach(action);
                }
            }
        }

        private ArrayNode<K, V> replaceChild(int frag, Node<K, V> node, int newCount) {
            Node<K, V>[] c = children.clone();
            c[frag] = node;
            return new ArrayNode<>(newCount, c);
        }

        /**
         * Demotes this ArrayNode to a BitmapIndexedNode, excluding the child
         * at {@code removedFrag} (which has become null).
         */
        @SuppressWarnings("unchecked")
        private BitmapIndexedNode<K, V> demote(int removedFrag, int remaining) {
            Object[] arr = new Object[2 * remaining];
            int bitmap = 0;
            int j = 0;
            for (int i = 0; i < WIDTH; i++) {
                if (i == removedFrag || children[i] == null) {
                    continue;
                }
                Node<K, V> child = children[i];
                // Try to inline single-leaf children
                if (child instanceof BitmapIndexedNode<K, V> bm
                        && Integer.bitCount(bm.bitmap) == 1
                        && bm.array[0] != null) {
                    arr[2 * j] = bm.array[0];
                    arr[2 * j + 1] = bm.array[1];
                } else {
                    arr[2 * j] = null;
                    arr[2 * j + 1] = child;
                }
                bitmap |= (1 << i);
                j++;
            }
            return new BitmapIndexedNode<>(bitmap, arr);
        }
    }

    // -----------------------------------------------------------------------
    // CollisionNode
    // -----------------------------------------------------------------------

    /**
     * Handles full 32-bit hash collisions. Stores entries as a flat
     * interleaved array {@code [k0, v0, k1, v1, ...]} and searches linearly.
     */
    static final class CollisionNode<K, V> implements Node<K, V> {

        final int hash;
        final Object[] pairs;

        CollisionNode(int hash, Object[] pairs) {
            this.hash = hash;
            this.pairs = pairs;
        }

        int count() {
            return pairs.length / 2;
        }

        @Override
        @SuppressWarnings("unchecked")
        public V get(K key, int hash, int shift) {
            int idx = findIndex(key);
            return (idx < 0) ? null : (V) pairs[2 * idx + 1];
        }

        @Override
        @SuppressWarnings("unchecked")
        public Node<K, V> put(K key, V value, int hash, int shift, SizeChange sc) {
            if (hash == this.hash) {
                int idx = findIndex(key);
                if (idx >= 0) {
                    if (value.equals(pairs[2 * idx + 1])) {
                        return this;
                    }
                    sc.delta = 0;
                    Object[] newPairs = pairs.clone();
                    newPairs[2 * idx + 1] = value;
                    return new CollisionNode<>(this.hash, newPairs);
                }
                sc.delta = 1;
                int n = count();
                Object[] newPairs = new Object[2 * (n + 1)];
                System.arraycopy(pairs, 0, newPairs, 0, 2 * n);
                newPairs[2 * n] = key;
                newPairs[2 * n + 1] = value;
                return new CollisionNode<>(this.hash, newPairs);
            }
            // Different hash — nest this collision node under a bitmap node
            // and insert the new key alongside it.
            sc.delta = 1;
            int frag = (this.hash >>> shift) & MASK;
            int bit = 1 << frag;
            BitmapIndexedNode<K, V> wrapper = new BitmapIndexedNode<>(bit,
                    new Object[]{null, this});
            // Re-route through the wrapper's put — it will place the new key
            // at the correct fragment. Use a fresh SizeChange since the wrapper
            // doesn't know about the collision node's entries.
            var innerSc = new SizeChange();
            Node<K, V> result = wrapper.put(key, value, hash, shift, innerSc);
            // sc.delta is already 1 (the new key); innerSc.delta is also 1
            // but that's an internal detail. The caller only sees +1.
            return result;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Node<K, V> remove(K key, int hash, int shift, SizeChange sc) {
            int idx = findIndex(key);
            if (idx < 0) {
                return this;
            }
            sc.delta = -1;
            int n = count();
            if (n == 1) {
                return null;
            }
            if (n == 2) {
                int other = 1 - idx;
                // Collapse to a single-leaf bitmap node.
                // The parent will inline this if it is a BitmapIndexedNode.
                K ok = (K) pairs[2 * other];
                V ov = (V) pairs[2 * other + 1];
                return liftLeaf(ok, ov, shift);
            }
            Object[] newPairs = new Object[2 * (n - 1)];
            System.arraycopy(pairs, 0, newPairs, 0, 2 * idx);
            System.arraycopy(pairs, 2 * (idx + 1), newPairs, 2 * idx,
                    2 * (n - idx - 1));
            return new CollisionNode<>(this.hash, newPairs);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void forEach(BiConsumer<K, V> action) {
            int n = count();
            for (int i = 0; i < n; i++) {
                action.accept((K) pairs[2 * i], (V) pairs[2 * i + 1]);
            }
        }

        private int findIndex(K key) {
            int n = count();
            for (int i = 0; i < n; i++) {
                if (key.equals(pairs[2 * i])) {
                    return i;
                }
            }
            return -1;
        }
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Creates a single-leaf BitmapIndexedNode at the given shift level.
     * The leaf's bitmap bit is determined by the key's hash fragment at
     * {@code shift}, so it can be correctly looked up when the parent
     * dispatches to this node.
     */
    private static <K, V> BitmapIndexedNode<K, V> liftLeaf(K key, V value, int shift) {
        int hash = spread(key.hashCode());
        int frag = (hash >>> shift) & MASK;
        int bit = 1 << frag;
        return new BitmapIndexedNode<>(bit, new Object[]{key, value});
    }

    /**
     * Creates a sub-trie containing two leaf entries whose hash fragments
     * collide at the parent's level. Recurses until fragments diverge or
     * creates a {@link CollisionNode} if the full 32-bit hashes are identical.
     */
    private static <K, V> Node<K, V> mergeLeaves(int shift,
                                                   K k1, V v1, int h1,
                                                   K k2, V v2, int h2) {
        if (shift >= 32) {
            return new CollisionNode<>(h1, new Object[]{k1, v1, k2, v2});
        }
        int f1 = (h1 >>> shift) & MASK;
        int f2 = (h2 >>> shift) & MASK;
        if (f1 == f2) {
            Node<K, V> child = mergeLeaves(shift + BITS, k1, v1, h1, k2, v2, h2);
            int bit = 1 << f1;
            return new BitmapIndexedNode<>(bit, new Object[]{null, child});
        }
        int b1 = 1 << f1;
        int b2 = 1 << f2;
        if (f1 < f2) {
            return new BitmapIndexedNode<>(b1 | b2, new Object[]{k1, v1, k2, v2});
        }
        return new BitmapIndexedNode<>(b1 | b2, new Object[]{k2, v2, k1, v1});
    }
}
