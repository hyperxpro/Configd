package io.configd.testkit;

import io.configd.edge.LocalConfigStore;
import io.configd.edge.StalenessTracker;
import io.configd.edge.VersionCursor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.DeltaComputer;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test verifying the full write -> store -> edge pipeline.
 * Exercises {@link VersionedConfigStore} (control plane),
 * {@link DeltaComputer}, {@link LocalConfigStore} (edge),
 * {@link VersionCursor}, and {@link StalenessTracker} together.
 */
class EndToEndTest {

    private VersionedConfigStore controlPlane;
    private LocalConfigStore edge;

    @BeforeEach
    void setUp() {
        controlPlane = new VersionedConfigStore();
        edge = new LocalConfigStore();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Full pipeline: write -> snapshot -> edge load -> delta -> edge apply
    // -----------------------------------------------------------------------

    @Nested
    class FullPipeline {

        @Test
        void writeToControlPlaneThenSyncToEdge() {
            // 1. Write several config entries to control plane
            controlPlane.put("db.host", bytes("prod-db.internal"), 1);
            controlPlane.put("db.port", bytes("5432"), 2);
            controlPlane.put("cache.ttl", bytes("300"), 3);

            // 2. Take a snapshot
            ConfigSnapshot snap = controlPlane.snapshot();
            assertEquals(3, snap.version());
            assertEquals(3, snap.size());

            // 3. Load snapshot into edge store
            edge.loadSnapshot(snap);

            // 4. Verify reads from edge return correct values
            ReadResult dbHost = edge.get("db.host");
            assertTrue(dbHost.found());
            assertArrayEquals(bytes("prod-db.internal"), dbHost.value());

            ReadResult dbPort = edge.get("db.port");
            assertTrue(dbPort.found());
            assertArrayEquals(bytes("5432"), dbPort.value());

            ReadResult cacheTtl = edge.get("cache.ttl");
            assertTrue(cacheTtl.found());
            assertArrayEquals(bytes("300"), cacheTtl.value());

            assertEquals(3, edge.currentVersion());
        }

        @Test
        void computeAndApplyDeltaToEdge() {
            // Set up initial state on both sides
            controlPlane.put("a", bytes("1"), 1);
            controlPlane.put("b", bytes("2"), 2);
            ConfigSnapshot snap1 = controlPlane.snapshot();
            edge.loadSnapshot(snap1);

            // Make changes on control plane
            controlPlane.put("b", bytes("updated"), 3);
            controlPlane.put("c", bytes("3"), 4);
            controlPlane.delete("a", 5);
            ConfigSnapshot snap2 = controlPlane.snapshot();

            // Compute delta
            ConfigDelta delta = DeltaComputer.compute(snap1, snap2);
            assertEquals(2, delta.fromVersion());
            assertEquals(5, delta.toVersion());

            // Apply delta to edge
            edge.applyDelta(delta);

            // Verify edge reflects the changes
            assertFalse(edge.get("a").found(), "key 'a' should be deleted");
            assertArrayEquals(bytes("updated"), edge.get("b").value());
            assertArrayEquals(bytes("3"), edge.get("c").value());
            assertEquals(5, edge.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Version cursor enforcement
    // -----------------------------------------------------------------------

    @Nested
    class VersionCursorEnforcement {

        @Test
        void readWithOldCursorSucceeds() {
            controlPlane.put("key", bytes("value"), 5);
            edge.loadSnapshot(controlPlane.snapshot());

            VersionCursor oldCursor = new VersionCursor(3, 3000);
            ReadResult result = edge.get("key", oldCursor);
            assertTrue(result.found());
            assertArrayEquals(bytes("value"), result.value());
        }

        @Test
        void readWithFutureCursorReturnsNotFound() {
            controlPlane.put("key", bytes("value"), 5);
            edge.loadSnapshot(controlPlane.snapshot());

            VersionCursor futureCursor = new VersionCursor(10, 10000);
            ReadResult result = edge.get("key", futureCursor);
            assertFalse(result.found());
        }

        @Test
        void readWithExactCursorSucceeds() {
            controlPlane.put("key", bytes("value"), 5);
            edge.loadSnapshot(controlPlane.snapshot());

            VersionCursor exactCursor = new VersionCursor(5, 5000);
            ReadResult result = edge.get("key", exactCursor);
            assertTrue(result.found());
        }

        @Test
        void initialCursorAlwaysSucceeds() {
            controlPlane.put("key", bytes("value"), 1);
            edge.loadSnapshot(controlPlane.snapshot());

            ReadResult result = edge.get("key", VersionCursor.INITIAL);
            assertTrue(result.found());
        }
    }

    // -----------------------------------------------------------------------
    // Multiple sequential deltas
    // -----------------------------------------------------------------------

    @Nested
    class SequentialDeltas {

        @Test
        void multipleSequentialDeltasAppliedCorrectly() {
            // Initial load
            controlPlane.put("a", bytes("1"), 1);
            ConfigSnapshot snap0 = controlPlane.snapshot();
            edge.loadSnapshot(snap0);

            // Delta 1: add "b"
            controlPlane.put("b", bytes("2"), 2);
            ConfigSnapshot snap1 = controlPlane.snapshot();
            ConfigDelta delta1 = DeltaComputer.compute(snap0, snap1);
            edge.applyDelta(delta1);

            assertEquals(2, edge.currentVersion());
            assertArrayEquals(bytes("1"), edge.get("a").value());
            assertArrayEquals(bytes("2"), edge.get("b").value());

            // Delta 2: update "a", add "c"
            controlPlane.put("a", bytes("updated"), 3);
            controlPlane.put("c", bytes("3"), 4);
            ConfigSnapshot snap2 = controlPlane.snapshot();
            ConfigDelta delta2 = DeltaComputer.compute(snap1, snap2);
            edge.applyDelta(delta2);

            assertEquals(4, edge.currentVersion());
            assertArrayEquals(bytes("updated"), edge.get("a").value());
            assertArrayEquals(bytes("2"), edge.get("b").value());
            assertArrayEquals(bytes("3"), edge.get("c").value());

            // Delta 3: delete "b"
            controlPlane.delete("b", 5);
            ConfigSnapshot snap3 = controlPlane.snapshot();
            ConfigDelta delta3 = DeltaComputer.compute(snap2, snap3);
            edge.applyDelta(delta3);

            assertEquals(5, edge.currentVersion());
            assertArrayEquals(bytes("updated"), edge.get("a").value());
            assertFalse(edge.get("b").found());
            assertArrayEquals(bytes("3"), edge.get("c").value());
        }
    }

    // -----------------------------------------------------------------------
    // Delete propagation through delta
    // -----------------------------------------------------------------------

    @Nested
    class DeletePropagation {

        @Test
        void deleteOperationsPropagatesThroughDelta() {
            controlPlane.put("x", bytes("val-x"), 1);
            controlPlane.put("y", bytes("val-y"), 2);
            controlPlane.put("z", bytes("val-z"), 3);
            ConfigSnapshot snap1 = controlPlane.snapshot();
            edge.loadSnapshot(snap1);

            // Delete all keys
            controlPlane.delete("x", 4);
            controlPlane.delete("y", 5);
            controlPlane.delete("z", 6);
            ConfigSnapshot snap2 = controlPlane.snapshot();

            ConfigDelta delta = DeltaComputer.compute(snap1, snap2);
            assertEquals(3, delta.size());
            for (ConfigMutation m : delta.mutations()) {
                assertInstanceOf(ConfigMutation.Delete.class, m);
            }

            edge.applyDelta(delta);

            assertFalse(edge.get("x").found());
            assertFalse(edge.get("y").found());
            assertFalse(edge.get("z").found());
            assertEquals(6, edge.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Version monotonicity
    // -----------------------------------------------------------------------

    @Nested
    class VersionMonotonicity {

        @Test
        void edgeVersionIncreasesWithEachDelta() {
            controlPlane.put("a", bytes("1"), 1);
            ConfigSnapshot prev = controlPlane.snapshot();
            edge.loadSnapshot(prev);
            long lastVersion = edge.currentVersion();

            for (int seq = 2; seq <= 10; seq++) {
                controlPlane.put("a", bytes("v" + seq), seq);
                ConfigSnapshot next = controlPlane.snapshot();
                ConfigDelta delta = DeltaComputer.compute(prev, next);
                edge.applyDelta(delta);

                long currentVersion = edge.currentVersion();
                assertTrue(currentVersion > lastVersion,
                        "Edge version must increase: " + lastVersion + " -> " + currentVersion);
                lastVersion = currentVersion;
                prev = next;
            }

            assertEquals(10, edge.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot load replaces entire state
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotLoadReplacesState {

        @Test
        void loadSnapshotReplacesEntireEdgeState() {
            // Load initial state with keys a, b, c
            controlPlane.put("a", bytes("1"), 1);
            controlPlane.put("b", bytes("2"), 2);
            controlPlane.put("c", bytes("3"), 3);
            edge.loadSnapshot(controlPlane.snapshot());

            assertTrue(edge.get("a").found());
            assertTrue(edge.get("b").found());
            assertTrue(edge.get("c").found());

            // Create a completely different snapshot with only key "x"
            VersionedConfigStore otherStore = new VersionedConfigStore();
            otherStore.put("x", bytes("new-world"), 1);
            edge.loadSnapshot(otherStore.snapshot());

            // Old keys should be gone
            assertFalse(edge.get("a").found());
            assertFalse(edge.get("b").found());
            assertFalse(edge.get("c").found());

            // New key should be present
            assertTrue(edge.get("x").found());
            assertArrayEquals(bytes("new-world"), edge.get("x").value());
        }
    }

    // -----------------------------------------------------------------------
    // Staleness tracker with controlled clock
    // -----------------------------------------------------------------------

    @Nested
    class StalenessWithSimulatedClock {

        @Test
        void stalenessStateTransitionsWithControlledClock() {
            SimulatedClock clock = new SimulatedClock(10_000);
            StalenessTracker tracker = new StalenessTracker(clock);

            // Initially DISCONNECTED
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());

            // Record update -> CURRENT
            tracker.recordUpdate(1, 10_000);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());

            // Advance 400ms -> still CURRENT
            clock.advanceMs(400);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());

            // Advance to 501ms total -> STALE
            clock.advanceMs(101);
            assertEquals(StalenessTracker.State.STALE, tracker.currentState());

            // Advance to 5001ms total -> DEGRADED
            clock.advanceMs(4500);
            assertEquals(StalenessTracker.State.DEGRADED, tracker.currentState());

            // Advance to 30001ms total -> DISCONNECTED
            clock.advanceMs(25000);
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());

            // Record update -> back to CURRENT
            tracker.recordUpdate(2, 40_001);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
            assertEquals(2, tracker.lastVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Delta version mismatch detection
    // -----------------------------------------------------------------------

    @Nested
    class DeltaVersionMismatch {

        @Test
        void applyingDeltaWithWrongFromVersionThrows() {
            controlPlane.put("a", bytes("1"), 1);
            edge.loadSnapshot(controlPlane.snapshot());

            // Create a delta that claims to be from version 5, but edge is at version 1
            ConfigDelta badDelta = new ConfigDelta(5, 6, List.of(
                    new ConfigMutation.Put("b", bytes("2"))
            ));

            assertThrows(IllegalArgumentException.class, () -> edge.applyDelta(badDelta));
        }
    }

    // -----------------------------------------------------------------------
    // Empty delta (no-op)
    // -----------------------------------------------------------------------

    @Nested
    class EmptyDelta {

        @Test
        void emptyDeltaDoesNotChangeEdgeState() {
            controlPlane.put("a", bytes("1"), 1);
            ConfigSnapshot snap = controlPlane.snapshot();
            edge.loadSnapshot(snap);

            // Compute delta between identical snapshots
            ConfigDelta delta = DeltaComputer.compute(snap, snap);
            assertTrue(delta.isEmpty());

            // Apply the empty delta -- fromVersion must match
            edge.applyDelta(delta);

            // State unchanged
            assertEquals(1, edge.currentVersion());
            assertArrayEquals(bytes("1"), edge.get("a").value());
        }
    }
}
