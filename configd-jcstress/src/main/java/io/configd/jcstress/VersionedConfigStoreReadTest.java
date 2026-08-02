package io.configd.jcstress;

import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.JJ_Result;
import org.openjdk.jcstress.infra.results.J_Result;

/**
 * Verifies {@link VersionedConfigStore} lock-free read vs the single writer.
 *
 * <p>The store is MVCC: the single writer (Raft apply thread) swaps the volatile
 * {@code currentSnapshot}; readers do one volatile read and walk the immutable
 * HAMT inside that snapshot. The documented precondition is single-writer; we
 * model exactly one writer + concurrent readers (more than one writer would test
 * an unsupported contract and is deliberately out of scope).
 *
 * <p><b>Invariant:</b> a read must return a <em>consistent</em> version - the
 * {@code (value, version)} pair it hands back must be one the writer actually
 * published atomically, never a torn cross-version splice (value of v=N stamped
 * with version M). We make this checkable by writing values whose bytes ENCODE
 * their own version: every published value is {@code [version as 8 bytes]}, so a
 * reader can verify {@code decode(value) == version}. A mismatch is a torn read.
 */
public final class VersionedConfigStoreReadTest {

    private VersionedConfigStoreReadTest() {
    }

    private static final String KEY = "k";

    static byte[] encode(long version) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (version >>> (56 - 8 * i));
        }
        return b;
    }

    static long decode(byte[] b) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }

    // r1 = stamped version; r2 = version decoded from value bytes. Must be equal (consistent snapshot).
    // Differ only on torn MVCC read, which single-volatile-read design forbids.
    @JCStressTest
    @State
    @Description("VersionedConfigStore: read-while-write must return a consistent (value,version)")
    @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "read saw v1 (pre-write), consistent")
    @Outcome(id = "2, 2", expect = Expect.ACCEPTABLE, desc = "read saw v2 (post-write), consistent")
    @Outcome(expect = Expect.FORBIDDEN, desc = "TORN: stamped version != version encoded in value")
    public static class ConsistentVersionRead {
        final VersionedConfigStore store;

        public ConsistentVersionRead() {
            store = new VersionedConfigStore();
            store.put(KEY, encode(1L), 1L);
        }

        @Actor
        public void writer() {
            store.put(KEY, encode(2L), 2L);
        }

        @Actor
        public void reader(JJ_Result r) {
            ReadResult rr = store.get(KEY);
            r.r1 = rr.version();
            r.r2 = decode(rr.value());
        }
    }

    // ReadResult hands out internal byte[] (aliased). VersionedValue is immutable; writer publishes NEW
    // VersionedValue (never mutates old array), so aliased array is effectively frozen. Test fails if
    // reader decodes a version not actually published (torn/visibility hazard).
    @JCStressTest
    @State
    @Description("CF-31: aliased internal byte[] must never be observed torn under concurrent overwrite")
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "value bytes decode to a real published version")
    @Outcome(id = "2", expect = Expect.ACCEPTABLE, desc = "value bytes decode to the other published version")
    @Outcome(expect = Expect.FORBIDDEN, desc = "TORN: decoded version was never published (array tear)")
    public static class AliasedArrayNoTear {
        final VersionedConfigStore store;

        public AliasedArrayNoTear() {
            store = new VersionedConfigStore();
            store.put(KEY, encode(1L), 1L);
        }

        @Actor
        public void writer() {
            store.put(KEY, encode(2L), 2L);
        }

        @Actor
        public void reader(J_Result r) {
            long v = decode(store.get(KEY).value());
            r.r1 = (v == 1L || v == 2L) ? v : 99L; // 99 = forbidden
        }
    }
}
