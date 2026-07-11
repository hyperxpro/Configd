package io.configd.testkit;

import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the ddmin schedule minimizer: given a failing schedule and a
 * failure predicate, it reduces to a strictly smaller, 1-minimal schedule that still
 * fails, and emits a standalone replayable artifact.
 * <p>
 * There is (happily) no real failing seed in the current system, so the failure
 * predicate is synthetic but representative: "fails iff a {@code PARTITION_ADD}
 * event is present" - the kind of single-fault root cause ddmin must isolate from a
 * busy schedule. ddmin must shrink to exactly the events/ops required to keep the
 * predicate true (here: one PARTITION_ADD, zero ops).
 */
class ScheduleMinimizerTest {

    private static final int NODES = 5;
    private static final int TICKS = 1_500;

    @Test
    void ddminReducesToMinimalFailingSchedule() {
        // Find a seed whose expanded schedule contains a PARTITION_ADD.
        AdversarialSchedule failing = null;
        for (long seed = 0; seed < 50 && failing == null; seed++) {
            AdversarialSchedule s = new AdversarialSchedule(seed, NODES, TICKS);
            if (s.events().stream()
                    .anyMatch(e -> e.kind() == AdversarialSchedule.FaultKind.PARTITION_ADD)) {
                failing = s;
            }
        }
        assertNotNull(failing, "expected some seed to schedule a PARTITION_ADD");

        Predicate<AdversarialSchedule> stillFails = s -> s.events().stream()
                .anyMatch(e -> e.kind() == AdversarialSchedule.FaultKind.PARTITION_ADD);

        int beforeEvents = failing.events().size();
        int beforeOps = failing.ops().size();

        ScheduleMinimizer minimizer = new ScheduleMinimizer(stillFails);
        AdversarialSchedule minimized = minimizer.minimize(failing);

        // Still reproduces.
        assertTrue(stillFails.test(minimized), "minimized schedule must still fail");
        // Strictly smaller.
        int afterTotal = minimized.events().size() + minimized.ops().size();
        assertTrue(afterTotal < beforeEvents + beforeOps,
                "minimized schedule must be strictly smaller (" + afterTotal + " < "
                        + (beforeEvents + beforeOps) + ")");
        // 1-minimal w.r.t. this predicate: exactly the one needed PARTITION_ADD, no ops.
        assertEquals(1, minimized.events().size(),
                "ddmin must isolate the single root-cause event");
        assertEquals(AdversarialSchedule.FaultKind.PARTITION_ADD,
                minimized.events().get(0).kind());
        assertTrue(minimized.ops().isEmpty(),
                "ops irrelevant to the failure must be removed");

        // The result is a standalone, replayable JSON artifact.
        String json = ScheduleMinimizer.toJson(minimized);
        assertTrue(json.contains("\"seed\":") && json.contains("PARTITION_ADD"),
                "artifact must be a self-describing replayable schedule");
    }
}
