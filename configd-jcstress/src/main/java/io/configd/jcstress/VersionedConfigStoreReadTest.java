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
 * RR-029 / W-1 — {@link VersionedConfigStore} lock-free read vs the single writer.
 *
 * <p>The store is MVCC: the single writer (Raft apply thread) swaps the volatile
 * {@code currentSnapshot}; readers do one volatile read and walk the immutable
 * HAMT inside that snapshot. The documented precondition is single-writer; we
 * model exactly one writer + concurrent readers (more than one writer would test
 * an unsupported contract and is deliberately out of scope).
 *
 * <p><b>Invariant:</b> a read must return a <em>consistent</em> version — the
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

    /** Encodes a version into an 8-byte big-endian value, so the read can self-check. */
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

    // ------------------------------------------------------------------
    // Single writer advances the version; reader observes (value, version).
    // r1 = the version stamped on the ReadResult; r2 = the version DECODED from
    // the value bytes. They MUST be equal (a consistent snapshot). They differ
    // only on a torn MVCC read, which the single-volatile-read design forbids.
    // ------------------------------------------------------------------
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
            store.put(KEY, encode(1L), 1L); // seed at version 1
        }

        @Actor
        public void writer() {
            store.put(KEY, encode(2L), 2L); // single writer advances to version 2
        }

        @Actor
        public void reader(JJ_Result r) {
            ReadResult rr = store.get(KEY);
            r.r1 = rr.version();          // stamped version
            r.r2 = decode(rr.value());    // version recovered from the bytes
        }
    }

    // ------------------------------------------------------------------
    // CF-31 probe: ReadResult hands out the live internal byte[] (valueUnsafe
    // aliasing). Is that array ever observed mid-mutation cross-thread? The
    // VersionedValue defensively COPIES on construction and is immutable, and the
    // writer publishes a NEW VersionedValue (never mutates the old array) — so the
    // reader's aliased array is effectively frozen. This test fails (FORBIDDEN) if
    // a reader ever decodes a version from the bytes that does not match a version
    // the writer actually published, i.e. a torn/visibility hazard on the shared
    // array. A clean run is the safe-by-construction evidence.
    // ------------------------------------------------------------------
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
            // Grab the (aliased) internal array and decode it. Only 1 or 2 are
            // legal published versions; any other decoded long proves a torn array.
            long v = decode(store.get(KEY).value());
            r.r1 = (v == 1L || v == 2L) ? v : 99L; // 99 surfaces as a forbidden outcome
        }
    }
}
