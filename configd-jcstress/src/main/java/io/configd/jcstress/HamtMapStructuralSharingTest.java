package io.configd.jcstress;

import io.configd.store.HamtMap;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Verifies {@link HamtMap} structural sharing under concurrent get vs put.
 *
 * <p>{@code HamtMap} is a persistent (immutable, final-field) trie: {@code put}
 * copies only the path root->leaf and shares everything else, returning a NEW map;
 * the original is never mutated. The publication seam in production is the
 * volatile {@code ConfigSnapshot} swap in {@code VersionedConfigStore}; here we
 * model the same shape directly with a {@code volatile HamtMap} the writer swaps.
 *
 * <p><b>Invariant:</b> a reader that reads the volatile map reference and then
 * {@code get}s a key must observe a fully-constructed structure: every key that
 * belongs to the map version it read is visible with the correct value, and a key
 * is never observed half-inserted (null where the read map already contains it, or
 * a stale value spliced into a fresh node). Because the map is immutable and
 * published via a volatile write, the final-field + happens-before guarantees make
 * this safe by construction - this test is the evidence.
 */
public final class HamtMapStructuralSharingTest {

    private HamtMapStructuralSharingTest() {
    }

    // Keys land in different hash fragments so put() copies a different path —
    // exercising structural sharing (the shared key must remain reachable through the new root).
    private static final String SHARED = "shared-key";
    private static final String FRESH = "fresh-key";

    // Reader must see SHARED present and correct regardless of which map version it observed.
    // r1 = value for SHARED (must be 7); 0 = torn/lost through structural sharing (forbidden).
    @JCStressTest
    @State
    @Description("HamtMap: shared key stays reachable through a put that copies a different path")
    @Outcome(id = "7", expect = Expect.ACCEPTABLE, desc = "shared key correct (sharing held)")
    @Outcome(expect = Expect.FORBIDDEN, desc = "shared key lost/torn through structural sharing")
    public static class SharedKeyStaysReachable {
        volatile HamtMap<String, Integer> map;

        public SharedKeyStaysReachable() {
            map = HamtMap.<String, Integer>empty().put(SHARED, 7);
        }

        @Actor
        public void writer() {
            map = map.put(FRESH, 99);
        }

        @Actor
        public void reader(I_Result r) {
            HamtMap<String, Integer> m = map;
            Integer v = m.get(SHARED);
            r.r1 = (v == null) ? 0 : v;
        }
    }

    // Outcomes must be internally consistent with one published map version: (7, 0) = pre-put,
    // (7, 99) = post-put. Anything else (SHARED missing, FRESH wrong, half-inserted) = torn/forbidden.
    @JCStressTest
    @State
    @Description("HamtMap: a read observes a self-consistent map version (no half-insert)")
    @Outcome(id = "7, 0", expect = Expect.ACCEPTABLE, desc = "pre-put version (FRESH absent)")
    @Outcome(id = "7, 99", expect = Expect.ACCEPTABLE, desc = "post-put version (both present)")
    @Outcome(expect = Expect.FORBIDDEN, desc = "TORN: inconsistent or half-constructed map")
    public static class ConsistentMapVersion {
        volatile HamtMap<String, Integer> map;

        public ConsistentMapVersion() {
            map = HamtMap.<String, Integer>empty().put(SHARED, 7);
        }

        @Actor
        public void writer() {
            map = map.put(FRESH, 99);
        }

        @Actor
        public void reader(II_Result r) {
            HamtMap<String, Integer> m = map;
            Integer s = m.get(SHARED);
            Integer f = m.get(FRESH);
            r.r1 = (s == null) ? -1 : s;               // -1 surfaces as forbidden
            r.r2 = (f == null) ? 0 : f;                // 0 == legitimately absent
        }
    }
}
