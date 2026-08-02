package io.configd.testkit;

import io.configd.edge.VersionCursor;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-the-tester for {@link EdgeInvariants}: each edge invariant is observed
 * <b>FIRING</b> on a deliberately-constructed violation, so the checker is proven
 * non-vacuous (an assertion never seen to fire is unverified).
 * <p>
 * The four firings (one per invariant clause) and their capture is the deliverable:
 * <ol>
 *   <li>(a) a snapshot that decreases store version -> version-monotonicity throws;</li>
 *   <li>(b) a snapshot that decreases a key's version (while overall version rises)
 *       -> no-stale-overwrite throws; PLUS the production guard path - a stale
 *       {@link EdgeStream.Notify} is refused by {@link io.configd.edge.DeltaApplier}
 *       ({@code STALE_DELTA}) and never overwrites, so production correctness itself
 *       protects the invariant;</li>
 *   <li>(c) a divergent edge end-state -> {@link EdgeInvariants#finalCheck} throws
 *       with a precise diff;</li>
 *   <li>(d) a publication observed past the bound -> a recorded liveness violation
 *       with the correct lateness (NOT thrown).</li>
 * </ol>
 * The captured firing messages are printed for the evidence record.
 */
class EdgeInvariantsTestTheTesterTest {

    private static final long SEED = 4242L;

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private EdgeActor newEdge(int subscribedCp) {
        return new EdgeActor(EdgeActor.EDGE_ID_BASE, subscribedCp, now::get);
    }

    private static ConfigSnapshot snapshotWith(long version, String key, String value, long keyVersion) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        if (key != null) {
            data = data.put(key, new VersionedValue(
                    value.getBytes(StandardCharsets.UTF_8), keyVersion, version));
        }
        return new ConfigSnapshot(data, version, version);
    }

    // (a) per-edge version monotonicity fires.

    @Test
    void versionMonotonicityCheckerFiresOnDecreasingStoreVersion() {
        EdgeActivity activity = new EdgeActivity();
        EdgeInvariants inv = new EdgeInvariants(SEED, activity);
        EdgeActor edge = newEdge(0);

        edge.deliver(new EdgeStream.Snapshot(snapshotWith(10, "k", "v10", 10), 10));
        edge.tick();
        inv.checkAll(List.of(edge), now.get(), e -> true); // records baseline version 10

        // Force a regression to version 5 within the SAME incarnation, BYPASSING the
        // production backward-snapshot guard (which now correctly refuses it - see the
        // separate guard test below). This models a hypothetical bug so the checker's
        // non-vacuity is still provable.
        edge.forceLoadSnapshotUnsafeForTest(snapshotWith(5, "k", "v5", 5), 5);

        SimInvariants.SafetyViolation ex = assertThrows(SimInvariants.SafetyViolation.class,
                () -> inv.checkAll(List.of(edge), now.get(), e -> true),
                "version-monotonicity checker must FIRE when store version decreases");
        assertTrue(ex.getMessage().contains("version monotonicity")
                        && ex.getMessage().contains("seed=" + SEED),
                "firing message must name the invariant and the seed: " + ex.getMessage());
        System.out.println("[capture a] " + ex.getMessage());
    }

    /**
         * The complementary half of (a): the production {@link EdgeActor#applySnapshot} now
         * REFUSES a backward snapshot (one whose seq is below the edge's current cursor), so the
         * edge never regresses through the real apply path - production correctness protects the
         * monotonicity invariant.
         */
    @Test
    void productionApplySnapshotRefusesBackwardSnapshotSoTheStoreNeverRegresses() {
        EdgeActivity activity = new EdgeActivity();
        EdgeInvariants inv = new EdgeInvariants(SEED, activity);
        EdgeActor edge = newEdge(0);

        edge.deliver(new EdgeStream.Snapshot(snapshotWith(10, "k", "v10", 10), 10));
        edge.tick();
        assertEquals(10, edge.currentVersion());
        inv.checkAll(List.of(edge), now.get(), e -> true);

        edge.deliver(new EdgeStream.Snapshot(snapshotWith(5, "k", "v5", 5), 5));
        edge.tick();
        assertEquals(10, edge.currentVersion(), "backward snapshot must be refused (no regression)");
        // The checker passes (no decrease occurred) - production guard protected it.
        inv.checkAll(List.of(edge), now.get(), e -> true);
    }

    // (b) no-stale-overwrite fires; production guard protects.

    @Test
    void noStaleOverwriteCheckerFiresOnDecreasingKeyVersion() {
        EdgeActivity activity = new EdgeActivity();
        EdgeInvariants inv = new EdgeInvariants(SEED, activity);
        EdgeActor edge = newEdge(0);

        edge.deliver(new EdgeStream.Snapshot(snapshotWith(10, "k", "v10", 10), 10));
        edge.tick();
        inv.checkAll(List.of(edge), now.get(), e -> true);

        // Store version RISES to 11 (so invariant (a) passes) but key 'k' regresses to
        // version 5 - a per-key stale overwrite, which invariant (b) must catch. We inject
        // this via the UNSAFE force-load hook (BYPASSING the codec): the production snapshot
        // path serializes through EdgeSnapshotCodec, which RESTAMPS every key to the snapshot
        // seq (per the apply-sequence invariant), so a per-key regression cannot survive the
        // real path and a bug must be injected directly to prove the checker's non-vacuity.
        // (Same discipline as (a).)
        edge.forceLoadSnapshotUnsafeForTest(snapshotWith(11, "k", "v5", 5), 11);

        SimInvariants.SafetyViolation ex = assertThrows(SimInvariants.SafetyViolation.class,
                () -> inv.checkAll(List.of(edge), now.get(), e -> true),
                "no-stale-overwrite checker must FIRE when a key's version decreases");
        assertTrue(ex.getMessage().contains("stale overwrite")
                        && ex.getMessage().contains("key 'k'"),
                "firing message must name the invariant and the key: " + ex.getMessage());
        System.out.println("[capture b] " + ex.getMessage());
    }

    @Test
    void monitorWiredReadStoreFiresInvM1OnCursorAheadRead() {
        // The read-side half of invariant (a): a cursor-bound read whose cursor is
        // ahead of the local store version routes through the REAL test-mode
        // InvariantMonitor wired into the edge's read LocalConfigStore, which throws
        // (fails the seed). This proves the monotonic_read seam is live and non-vacuous.
        EdgeActor edge = newEdge(0);
        edge.deliver(new EdgeStream.Notify(notification(3, 0, 3, "k", "v3")));
        edge.tick();
        assertEquals(3, edge.currentVersion());

        VersionCursor aheadCursor = new VersionCursor(9, now.get());
        AssertionError ex = assertThrows(AssertionError.class,
                () -> edge.get("k", aheadCursor),
                "monitor-wired read store must FIRE monotonic_read when cursor is ahead");
        assertTrue(ex.getMessage().contains("monotonic_read")
                        || ex.getMessage().contains("monotonic read"),
                "firing message must name the INV-M1 invariant: " + ex.getMessage());
        System.out.println("[capture a-read] " + ex.getMessage());

        VersionCursor okCursor = new VersionCursor(3, now.get());
        assertEquals("v3", new String(edge.get("k", okCursor).value(), StandardCharsets.UTF_8),
                "a cursor not ahead of the store must read normally");
    }

    @Test
    void productionDeltaApplierGuardRefusesStaleDeltaSoTheStoreNeverRegresses() {
        // The complementary half of (b): when the violation is offered through the
        // REAL apply path (a Notify with a stale delta), DeltaApplier's own
        // STALE_DELTA guard refuses it - production correctness protects the
        // invariant, so the checker has nothing to catch. We assert the refusal.
        EdgeActivity activity = new EdgeActivity();
        EdgeInvariants inv = new EdgeInvariants(SEED, activity);
        EdgeActor edge = newEdge(0);

        edge.deliver(new EdgeStream.Notify(notification(1, 0, 1, "k", "v1")));
        edge.tick();
        assertEquals(1, edge.currentVersion(), "legit delta must apply");
        inv.checkAll(List.of(edge), now.get(), e -> true);

        // Offer a STALE delta (toVersion 1 <= currentVersion 1). DeltaApplier returns
        // STALE_DELTA and does NOT apply, so the store does not regress.
        edge.deliver(new EdgeStream.Notify(notification(1, 0, 1, "k", "stale")));
        edge.tick();
        assertEquals(1, edge.currentVersion(), "stale delta must NOT change version");
        assertEquals("v1", value(edge, "k"), "stale delta must NOT overwrite the value");
        assertDoesNotThrow(() -> inv.checkAll(List.of(edge), now.get(), e -> true));
    }

    // (c) convergence finalCheck fires on divergence.

    @Test
    void convergenceFinalCheckFiresOnDivergentEdgeState() {
        EdgeActivity activity = new EdgeActivity();
        EdgeInvariants inv = new EdgeInvariants(SEED, activity);
        EdgeActor edge = newEdge(0);

        edge.deliver(new EdgeStream.Snapshot(snapshotWith(7, "k", "vEdge", 7), 7));
        edge.tick();
        ConfigSnapshot authoritative = snapshotWith(9, "k", "vLeader", 9);

        SimInvariants.SafetyViolation ex = assertThrows(SimInvariants.SafetyViolation.class,
                () -> inv.finalCheck(List.of(edge), authoritative),
                "finalCheck must FIRE when an edge has diverged from the leader");
        assertTrue(ex.getMessage().contains("convergence")
                        && ex.getMessage().contains("version mismatch"),
                "firing message must be a precise diff: " + ex.getMessage());
        System.out.println("[capture c] " + ex.getMessage());

        EdgeActor converged = newEdge(0);
        converged.deliver(new EdgeStream.Snapshot(snapshotWith(9, "k", "vLeader", 9), 9));
        converged.tick();
        assertDoesNotThrow(() -> inv.finalCheck(List.of(converged), authoritative),
                "a converged edge must pass finalCheck");
    }

    // (d) eventual-delivery bound recorded with correct lateness.

    @Test
    void eventualDeliveryViolationIsRecordedWithCorrectLateness() {
        EdgeActivity activity = new EdgeActivity();
        long bound = EdgeInvariants.BOUND_MS;
        EdgeInvariants inv = new EdgeInvariants(SEED, activity, bound);
        EdgeActor edge = newEdge(0); // subscribed to CP node 0; never observes

        long publishedAt = now.get();
        inv.recordPublication(7L, 0, publishedAt, List.of(edge.edgeId()));

        now.set(publishedAt + bound); // exactly at bound (not strictly past)
        inv.checkAll(List.of(edge), now.get(), e -> true);
        assertEquals(0, activity.deliveryViolationCount(),
                "no violation at-or-before the bound");

        long overshoot = 30L;
        now.set(publishedAt + bound + overshoot);
        inv.checkAll(List.of(edge), now.get(), e -> true);

        assertEquals(1, activity.deliveryViolationCount(),
                "a delivery past the bound must be recorded");
        EdgeActivity.DeliveryViolation v = activity.deliveryViolations().get(0);
        assertEquals(7L, v.seq());
        assertEquals(edge.edgeId(), v.edgeId());
        assertEquals(publishedAt, v.publishedAtMs());
        assertEquals(overshoot, v.latenessMs(),
                "lateness must be (now - publishedAt - bound) = " + overshoot);
        assertEquals(overshoot, activity.perEdgeMaxLatenessMs().get(edge.edgeId()));
        System.out.println("[capture d] recorded delivery violation " + v);
    }

    @Test
    void eventualDeliveryViolationIsNotRecordedWhenEdgeObservesInTime() {
        EdgeActivity activity = new EdgeActivity();
        long bound = EdgeInvariants.BOUND_MS;
        EdgeInvariants inv = new EdgeInvariants(SEED, activity, bound);
        EdgeActor edge = newEdge(0);

        long publishedAt = now.get();
        inv.recordPublication(3L, 0, publishedAt, List.of(edge.edgeId()));

        edge.deliver(new EdgeStream.Notify(notification(3, 0, 3, "k", "v")));
        edge.tick();
        assertEquals(3, edge.cursor(), "edge must have observed seq 3");

        now.set(publishedAt + bound + 1000);
        inv.checkAll(List.of(edge), now.get(), e -> true);
        assertEquals(0, activity.deliveryViolationCount(),
                "no violation when the edge observed the seq before the bound");
    }


    private static io.configd.distribution.CommitNotification notification(
            long seq, long fromVersion, long toVersion, String key, String value) {
        var mutations = List.<io.configd.store.ConfigMutation>of(
                new io.configd.store.ConfigMutation.Put(key, value.getBytes(StandardCharsets.UTF_8)));
        var delta = new io.configd.store.ConfigDelta(fromVersion, toVersion, mutations);
        return new io.configd.distribution.CommitNotification(seq, 1_700_000_000_000L, delta);
    }

    private static String value(EdgeActor edge, String key) {
        var r = edge.get(key);
        return r.found() ? new String(r.value(), StandardCharsets.UTF_8) : null;
    }
}
