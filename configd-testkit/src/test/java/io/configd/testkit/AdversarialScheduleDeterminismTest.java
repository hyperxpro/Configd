package io.configd.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The seed-derived {@link AdversarialSchedule} must be a pure function of the
 * master seed: same seed => identical fault + workload event lists; different seeds
 * => different lists (the schedule is not vacuously constant). This is the
 * foundation that makes a failing seed replayable and shrinkable (section 5 ddmin).
 */
class AdversarialScheduleDeterminismTest {

    private static final int NODES = 5;
    private static final int TICKS = 1_500;

    @Test
    void sameSeedYieldsIdenticalSchedule() {
        AdversarialSchedule a = new AdversarialSchedule(42L, NODES, TICKS);
        AdversarialSchedule b = new AdversarialSchedule(42L, NODES, TICKS);
        assertEquals(a.events().toString(), b.events().toString(),
                "fault events must be identical for the same seed");
        assertEquals(a.ops().toString(), b.ops().toString(),
                "client ops must be identical for the same seed");
    }

    @Test
    void distinctSeedsYieldDistinctSchedules() {
        AdversarialSchedule a = new AdversarialSchedule(1L, NODES, TICKS);
        AdversarialSchedule b = new AdversarialSchedule(2L, NODES, TICKS);
        assertNotEquals(a.events().toString() + a.ops(),
                b.events().toString() + b.ops(),
                "different seeds must drive different schedules");
    }

    @Test
    void scheduleContainsRealFaultsAndOps() {
        AdversarialSchedule s = new AdversarialSchedule(7L, NODES, TICKS);
        assertFalse(s.events().isEmpty(), "must schedule faults");
        assertFalse(s.ops().isEmpty(), "must schedule client ops");
        // Events and ops are tick-ordered (the harness consumes them in order).
        assertTrue(isSortedByTick(s), "schedule must be tick-ordered");
    }

    private static boolean isSortedByTick(AdversarialSchedule s) {
        int prev = Integer.MIN_VALUE;
        for (var e : s.events()) {
            if (e.tick() < prev) {
                return false;
            }
            prev = e.tick();
        }
        prev = Integer.MIN_VALUE;
        for (var o : s.ops()) {
            if (o.tick() < prev) {
                return false;
            }
            prev = o.tick();
        }
        return true;
    }
}
