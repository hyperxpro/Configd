package io.configd.store;

import io.configd.common.Clock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AssertionTwinFiringTest {

    static final class RecordingChecker implements ConfigStateMachine.InvariantChecker {
        final List<String> fired = new ArrayList<>();

        @Override
        public void check(String name, boolean condition, String message) {
            if (!condition) {
                fired.add(name);
                throw new AssertionError("twin fired [" + name + "]: " + message);
            }
        }
    }

    static final class CountingMetrics implements StateMachineMetrics {
        volatile int ownerViolations;
        @Override public void onWriteCommitSuccess(long applyDurationNanos) { }
        @Override public void onWriteCommitFailure() { }
        @Override public void onSnapshotRebuildSuccess() { }
        @Override public void onSnapshotInstallFailed() { }
        @Override public void onApplyOwnerThreadViolation() { ownerViolations++; }
    }

    @Test
    void perKeyOrderTwinIsObservedFiring() throws Exception {
        RecordingChecker checker = new RecordingChecker();
        ConfigStateMachine sm = new ConfigStateMachine(
                new VersionedConfigStore(), Clock.system(), checker, null);

        sm.apply(1L, 1L, CommandCodec.encodePut("k", new byte[]{1}));

        Field scf = ConfigStateMachine.class.getDeclaredField("sequenceCounter");
        scf.setAccessible(true);
        scf.setLong(sm, 0L);

        try {
            sm.apply(2L, 1L, CommandCodec.encodePut("k", new byte[]{2}));
            fail("expected per_key_order to fire on a non-monotonic version");
        } catch (AssertionError expected) {
        }
        assertTrue(checker.fired.contains("per_key_order"),
                "per_key_order must be observed firing; fired=" + checker.fired);
    }

    @Test
    void applyOwnerThreadTwinIsObservedFiring() throws Exception {
        RecordingChecker checker = new RecordingChecker();
        CountingMetrics metrics = new CountingMetrics();
        ConfigStateMachine sm = new ConfigStateMachine(
                new VersionedConfigStore(), Clock.system(), checker, null, metrics);

        sm.apply(1L, 1L, CommandCodec.encodePut("a", new byte[]{1}));
        assertTrue(checker.fired.isEmpty(), "no violation on the owner thread");
        assertEquals(0, metrics.ownerViolations);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread offOwner = new Thread(() -> {
            try {
                sm.apply(2L, 1L, CommandCodec.encodePut("b", new byte[]{2}));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "off-owner-apply");
        offOwner.start();
        offOwner.join(5_000);

        assertNotNull(thrown.get(),
                "apply off the owner thread must throw via the wired checker (W-1)");
        assertTrue(thrown.get() instanceof AssertionError,
                "expected AssertionError from the invariant checker, got " + thrown.get());
        assertTrue(checker.fired.contains("apply_owner_thread"),
                "apply_owner_thread must be observed firing; fired=" + checker.fired);
        assertEquals(1, metrics.ownerViolations,
                "the owner-thread violation metric must increment (prod path)");
    }
}
