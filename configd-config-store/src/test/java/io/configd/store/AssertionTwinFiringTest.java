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

/**
 * Assertion-twin firing harness - config-store half.
 * <p>
 * Fires the two twins that live in {@link ConfigStateMachine}:
 * <ul>
 *   <li>{@code per_key_order} - a PUT whose assigned version is not strictly greater than
 *       the existing value's version. Driven by reflectively rewinding the sequence counter
 *       (only the precondition is injected - the detection, throw, and metric path are the
 *       real production code).</li>
 *   <li>{@code apply_owner_thread} - the single-writer owner-thread tripwire. We bind the
 *       owner on one thread (first apply), then drive {@code apply} from a SECOND thread and
 *       assert (a) the wired {@link ConfigStateMachine.InvariantChecker} throws and (b)
 *       {@code onApplyOwnerThreadViolation} increments.</li>
 * </ul>
 * This class is run alongside the consensus-core half ({@code io.configd.raft.AssertionTwinFiringTest}).
 */
class AssertionTwinFiringTest {

    /** Records every fired twin name and still throws (test/sim semantics). */
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

    /** Counts owner-thread violations so the metric path is asserted too. */
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

        // Rewind the sequence counter so the next PUT computes a version <= existing.
        Field scf = ConfigStateMachine.class.getDeclaredField("sequenceCounter");
        scf.setAccessible(true);
        scf.setLong(sm, 0L);

        try {
            sm.apply(2L, 1L, CommandCodec.encodePut("k", new byte[]{2}));
            fail("expected per_key_order to fire on a non-monotonic version");
        } catch (AssertionError expected) {
            // production check threw via the wired checker
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

        // First apply on THIS thread binds the owner.
        sm.apply(1L, 1L, CommandCodec.encodePut("a", new byte[]{1}));
        assertTrue(checker.fired.isEmpty(), "no violation on the owner thread");
        assertEquals(0, metrics.ownerViolations);

        // Second apply from a DIFFERENT thread must trip the owner-thread tripwire.
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
