package io.configd.replication;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FlowController} — credit acquisition, release,
 * throttling, and boundary conditions.
 */
class FlowControllerTest {

    private static final int INITIAL_CREDITS = 10;
    private static final NodeId FOLLOWER_1 = NodeId.of(2);
    private static final NodeId FOLLOWER_2 = NodeId.of(3);

    private FlowController controller;

    @BeforeEach
    void setUp() {
        controller = new FlowController(INITIAL_CREDITS);
    }

    // ========================================================================
    // Constructor validation
    // ========================================================================

    @Nested
    class ConstructorValidation {

        @Test
        void rejectsZeroCredits() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FlowController(0));
        }

        @Test
        void rejectsNegativeCredits() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FlowController(-5));
        }

        @Test
        void initialCreditsAccessor() {
            assertEquals(INITIAL_CREDITS, controller.initialCredits());
        }
    }

    // ========================================================================
    // Follower management
    // ========================================================================

    @Nested
    class FollowerManagement {

        @Test
        void addFollowerInitializesWithFullCredits() {
            controller.addFollower(FOLLOWER_1);
            assertEquals(INITIAL_CREDITS, controller.availableCredits(FOLLOWER_1));
        }

        @Test
        void addFollowerIsIdempotent() {
            controller.addFollower(FOLLOWER_1);
            controller.acquireCredits(FOLLOWER_1, 5);
            // Adding again should preserve existing credits
            controller.addFollower(FOLLOWER_1);
            assertEquals(INITIAL_CREDITS - 5, controller.availableCredits(FOLLOWER_1));
        }

        @Test
        void addFollowerRejectsNull() {
            assertThrows(NullPointerException.class,
                    () -> controller.addFollower(null));
        }

        @Test
        void removeFollowerSucceeds() {
            controller.addFollower(FOLLOWER_1);
            controller.removeFollower(FOLLOWER_1);
            assertThrows(IllegalStateException.class,
                    () -> controller.availableCredits(FOLLOWER_1));
        }

        @Test
        void removeUnregisteredFollowerThrows() {
            assertThrows(IllegalStateException.class,
                    () -> controller.removeFollower(FOLLOWER_1));
        }

        @Test
        void removeFollowerRejectsNull() {
            assertThrows(NullPointerException.class,
                    () -> controller.removeFollower(null));
        }
    }

    // ========================================================================
    // Credit acquisition
    // ========================================================================

    @Nested
    class CreditAcquisition {

        @BeforeEach
        void addFollower() {
            controller.addFollower(FOLLOWER_1);
        }

        @Test
        void acquireFullAmount() {
            int granted = controller.acquireCredits(FOLLOWER_1, 5);
            assertEquals(5, granted);
            assertEquals(INITIAL_CREDITS - 5, controller.availableCredits(FOLLOWER_1));
        }

        @Test
        void acquireMoreThanAvailable() {
            // Consume most credits
            controller.acquireCredits(FOLLOWER_1, 8);

            // Request more than remaining
            int granted = controller.acquireCredits(FOLLOWER_1, 5);
            assertEquals(2, granted); // Only 2 remaining
            assertEquals(0, controller.availableCredits(FOLLOWER_1));
        }

        @Test
        void acquireWhenThrottledReturnsZero() {
            controller.acquireCredits(FOLLOWER_1, INITIAL_CREDITS); // exhaust all
            int granted = controller.acquireCredits(FOLLOWER_1, 1);
            assertEquals(0, granted);
        }

        @Test
        void acquireAllCreditsAtOnce() {
            int granted = controller.acquireCredits(FOLLOWER_1, INITIAL_CREDITS);
            assertEquals(INITIAL_CREDITS, granted);
            assertEquals(0, controller.availableCredits(FOLLOWER_1));
        }

        @Test
        void acquireRejectsNonPositiveCount() {
            assertThrows(IllegalArgumentException.class,
                    () -> controller.acquireCredits(FOLLOWER_1, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> controller.acquireCredits(FOLLOWER_1, -1));
        }

        @Test
        void acquireRejectsNullFollower() {
            assertThrows(NullPointerException.class,
                    () -> controller.acquireCredits(null, 1));
        }

        @Test
        void acquireFromUnregisteredFollowerThrows() {
            assertThrows(IllegalStateException.class,
                    () -> controller.acquireCredits(NodeId.of(99), 1));
        }
    }

    // ========================================================================
    // Credit release
    // ========================================================================

    @Nested
    class CreditRelease {

        @BeforeEach
        void addFollower() {
            controller.addFollower(FOLLOWER_1);
        }

        @Test
        void releaseRestoresCredits() {
            controller.acquireCredits(FOLLOWER_1, 5);
            controller.releaseCredits(FOLLOWER_1, 3);
            assertEquals(INITIAL_CREDITS - 5 + 3, controller.availableCredits(FOLLOWER_1));
        }

        @Test
        void releaseCapsAtInitialCredits() {
            // Release without acquiring — should not exceed initial
            controller.releaseCredits(FOLLOWER_1, 5);
            assertEquals(INITIAL_CREDITS, controller.availableCredits(FOLLOWER_1));
        }

        @Test
        void releaseFromFullThrottledToPartial() {
            controller.acquireCredits(FOLLOWER_1, INITIAL_CREDITS);
            assertTrue(controller.isThrottled(FOLLOWER_1));

            controller.releaseCredits(FOLLOWER_1, 1);
            assertFalse(controller.isThrottled(FOLLOWER_1));
            assertEquals(1, controller.availableCredits(FOLLOWER_1));
        }

        @Test
        void releaseRejectsNonPositiveCount() {
            assertThrows(IllegalArgumentException.class,
                    () -> controller.releaseCredits(FOLLOWER_1, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> controller.releaseCredits(FOLLOWER_1, -1));
        }

        @Test
        void releaseRejectsNullFollower() {
            assertThrows(NullPointerException.class,
                    () -> controller.releaseCredits(null, 1));
        }

        @Test
        void releaseFromUnregisteredFollowerThrows() {
            assertThrows(IllegalStateException.class,
                    () -> controller.releaseCredits(NodeId.of(99), 1));
        }
    }

    // ========================================================================
    // Throttling
    // ========================================================================

    @Nested
    class Throttling {

        @Test
        void notThrottledWithFullCredits() {
            controller.addFollower(FOLLOWER_1);
            assertFalse(controller.isThrottled(FOLLOWER_1));
        }

        @Test
        void throttledWhenCreditsExhausted() {
            controller.addFollower(FOLLOWER_1);
            controller.acquireCredits(FOLLOWER_1, INITIAL_CREDITS);
            assertTrue(controller.isThrottled(FOLLOWER_1));
        }

        @Test
        void throttleCheckRejectsUnregistered() {
            assertThrows(IllegalStateException.class,
                    () -> controller.isThrottled(NodeId.of(99)));
        }
    }

    // ========================================================================
    // Reset all
    // ========================================================================

    @Nested
    class ResetAll {

        @Test
        void resetAllRestoresAllFollowersToFullCredits() {
            controller.addFollower(FOLLOWER_1);
            controller.addFollower(FOLLOWER_2);

            controller.acquireCredits(FOLLOWER_1, 7);
            controller.acquireCredits(FOLLOWER_2, INITIAL_CREDITS);

            controller.resetAll();

            assertEquals(INITIAL_CREDITS, controller.availableCredits(FOLLOWER_1));
            assertEquals(INITIAL_CREDITS, controller.availableCredits(FOLLOWER_2));
            assertFalse(controller.isThrottled(FOLLOWER_1));
            assertFalse(controller.isThrottled(FOLLOWER_2));
        }

        @Test
        void resetAllOnEmptyControllerIsNoOp() {
            assertDoesNotThrow(() -> controller.resetAll());
        }
    }

    // ========================================================================
    // Multi-follower isolation
    // ========================================================================

    @Nested
    class MultiFollowerIsolation {

        @Test
        void creditsAreIndependentPerFollower() {
            controller.addFollower(FOLLOWER_1);
            controller.addFollower(FOLLOWER_2);

            controller.acquireCredits(FOLLOWER_1, 8);

            // Follower 2 should be unaffected
            assertEquals(INITIAL_CREDITS, controller.availableCredits(FOLLOWER_2));
            assertEquals(INITIAL_CREDITS - 8, controller.availableCredits(FOLLOWER_1));
        }
    }
}
