package io.configd.distribution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RolloutControllerTest {

    private final AtomicLong fakeTimeMs = new AtomicLong(0);
    private RolloutController controller;

    @BeforeEach
    void setUp() {
        io.configd.common.Clock fakeClock = new io.configd.common.Clock() {
            @Override public long currentTimeMillis() { return fakeTimeMs.get(); }
            @Override public long nanoTime() { return fakeTimeMs.get() * 1_000_000; }
        };
        controller = new RolloutController(fakeClock);
    }

    @Test
    void progressiveRolloutStartsAtCanary() {
        var status = controller.startRollout("r1", RolloutController.RolloutPolicy.PROGRESSIVE);
        assertEquals(RolloutController.Stage.CANARY, status.currentStage());
        assertEquals(RolloutController.RolloutState.IN_PROGRESS, status.state());
    }

    @Test
    void immediateRolloutGoesDirectlyToFull() {
        var status = controller.startRollout("r1", RolloutController.RolloutPolicy.IMMEDIATE);
        assertEquals(RolloutController.Stage.FULL, status.currentStage());
        assertEquals(RolloutController.RolloutState.COMPLETED, status.state());
    }

    @Test
    void cannotAdvanceBeforeSoakTime() {
        controller.startRollout("r1", RolloutController.RolloutPolicy.PROGRESSIVE);
        var status = controller.advance("r1");
        assertEquals(RolloutController.Stage.CANARY, status.currentStage());
    }

    @Test
    void advancesAfterSoakTime() {
        controller.startRollout("r1", RolloutController.RolloutPolicy.PROGRESSIVE);
        fakeTimeMs.addAndGet(61_000); // past 60s canary soak

        var status = controller.advance("r1");
        assertEquals(RolloutController.Stage.ONE_PERCENT, status.currentStage());
    }

    @Test
    void cannotAdvanceWithFailingHealth() {
        controller.startRollout("r1", RolloutController.RolloutPolicy.PROGRESSIVE);
        controller.updateHealth("r1", false);
        fakeTimeMs.addAndGet(61_000);

        var status = controller.advance("r1");
        assertEquals(RolloutController.Stage.CANARY, status.currentStage());
    }

    @Test
    void fullProgressionThroughAllStages() {
        controller.setSoakTime(RolloutController.Stage.CANARY, 100);
        controller.setSoakTime(RolloutController.Stage.ONE_PERCENT, 100);
        controller.setSoakTime(RolloutController.Stage.TEN_PERCENT, 100);
        controller.setSoakTime(RolloutController.Stage.FIFTY_PERCENT, 100);

        controller.startRollout("r1", RolloutController.RolloutPolicy.PROGRESSIVE);

        RolloutController.Stage[] expected = {
                RolloutController.Stage.ONE_PERCENT,
                RolloutController.Stage.TEN_PERCENT,
                RolloutController.Stage.FIFTY_PERCENT,
                RolloutController.Stage.FULL
        };

        for (RolloutController.Stage stage : expected) {
            fakeTimeMs.addAndGet(200);
            var status = controller.advance("r1");
            assertEquals(stage, status.currentStage());
        }

        assertEquals(RolloutController.RolloutState.COMPLETED,
                controller.status("r1").state());
    }

    @Test
    void pauseAndResume() {
        controller.startRollout("r1", RolloutController.RolloutPolicy.PROGRESSIVE);
        controller.pause("r1");
        assertEquals(RolloutController.RolloutState.PAUSED, controller.status("r1").state());

        controller.resume("r1");
        assertEquals(RolloutController.RolloutState.IN_PROGRESS, controller.status("r1").state());
    }

    @Test
    void rollback() {
        controller.startRollout("r1", RolloutController.RolloutPolicy.PROGRESSIVE);
        controller.rollback("r1");
        assertEquals(RolloutController.RolloutState.ROLLED_BACK, controller.status("r1").state());
    }

    @Test
    void cleanup() {
        controller.startRollout("r1", RolloutController.RolloutPolicy.IMMEDIATE); // completed
        controller.startRollout("r2", RolloutController.RolloutPolicy.PROGRESSIVE); // in progress

        controller.cleanup();
        assertNull(controller.status("r1")); // cleaned up
        assertNotNull(controller.status("r2")); // still active
    }
}
