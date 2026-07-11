package io.configd.edge;

import io.configd.common.Clock;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The edge filtered-stream apply matrix: forward-only gap detection and the version-bridged
 * store apply. A forward version jump is expected under server-side filtering (dropped
 * non-matching deltas bumped the global version), a regression below the applied version is
 * a genuine gap, and classic mode is unchanged.
 */
class DeltaApplierFilteredModeTest {

    private static final class TestClock implements Clock {
        long timeMs = 10_000L;
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
    }

    private TestClock clock;
    private EdgeConfigClient client;
    private DeltaApplier applier;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        client = new EdgeConfigClient(clock);
        client.loadSnapshot(new ConfigSnapshot(HamtMap.<String, VersionedValue>empty(), 0L, 0L));
        applier = new DeltaApplier(client);
        applier.setFilteredMode(true);
    }

    private static ConfigDelta delta(long from, long to, String key) {
        return new ConfigDelta(from, to,
                List.of(new ConfigMutation.Put(key, "v".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void forwardJumpIsAcceptedAndBridgedToToVersion() {
        // Apply a contiguous delta first (0 -> 1), then a JUMP (5 -> 6): under filtering the
        // intervening 1..5 were dropped server-side, so 5 -> 6 is expected, not a gap.
        assertEquals(DeltaApplier.ApplyResult.APPLIED, applier.offer(delta(0, 1, "svc/a"), clock.timeMs));
        assertEquals(1L, client.currentVersion());
        assertEquals(DeltaApplier.ApplyResult.APPLIED, applier.offer(delta(5, 6, "svc/b"), clock.timeMs));
        // The store bridged the jump: its version stepped straight to 6.
        assertEquals(6L, client.currentVersion(), "the store version bridges to toVersion, not fromVersion");
        assertEquals("v", new String(client.get("svc/b").value(), StandardCharsets.UTF_8));
    }

    @Test
    void contiguousDeltaStillAppliesInFilteredMode() {
        assertEquals(DeltaApplier.ApplyResult.APPLIED, applier.offer(delta(0, 1, "svc/a"), clock.timeMs));
        assertEquals(DeltaApplier.ApplyResult.APPLIED, applier.offer(delta(1, 2, "svc/b"), clock.timeMs));
        assertEquals(2L, client.currentVersion());
    }

    @Test
    void regressionBelowAppliedVersionIsGap() {
        applier.offer(delta(0, 10, "svc/a"), clock.timeMs); // jump to 10
        assertEquals(10L, client.currentVersion());
        // A delta whose fromVersion is BELOW the applied version (a malformed covered-S) is a gap.
        assertEquals(DeltaApplier.ApplyResult.GAP_DETECTED, applier.offer(delta(3, 11, "svc/b"), clock.timeMs));
    }

    @Test
    void staleDeltaIgnoredInFilteredMode() {
        applier.offer(delta(0, 5, "svc/a"), clock.timeMs);
        assertEquals(DeltaApplier.ApplyResult.STALE_DELTA, applier.offer(delta(2, 4, "svc/b"), clock.timeMs));
        assertEquals(5L, client.currentVersion());
    }

    @Test
    void classicModeStillRequiresStrictContiguity() {
        // With filtering OFF, a forward jump (a dropped MATCHING delta on a full-chain stream)
        // is a genuine gap - classic contiguity is preserved.
        applier.setFilteredMode(false);
        assertEquals(DeltaApplier.ApplyResult.APPLIED, applier.offer(delta(0, 1, "svc/a"), clock.timeMs));
        assertEquals(DeltaApplier.ApplyResult.GAP_DETECTED, applier.offer(delta(5, 6, "svc/b"), clock.timeMs));
        assertEquals(1L, client.currentVersion(), "the jump did not apply in classic mode");
    }
}
