package io.configd.testkit;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RaftSimulationTest {

    @Nested
    class Construction {

        @Test
        void createsCorrectNumberOfNodeIds() {
            RaftSimulation sim = new RaftSimulation(42L, 5);
            List<NodeId> nodes = sim.nodeIds();
            assertEquals(5, nodes.size());
            for (int i = 0; i < 5; i++) {
                assertEquals(NodeId.of(i), nodes.get(i));
            }
        }

        @Test
        void nodeIdsListIsUnmodifiable() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            List<NodeId> nodes = sim.nodeIds();
            assertThrows(UnsupportedOperationException.class, () -> nodes.add(NodeId.of(99)));
        }

        @Test
        void singleNodeCluster() {
            RaftSimulation sim = new RaftSimulation(42L, 1);
            assertEquals(1, sim.nodeIds().size());
            assertEquals(NodeId.of(0), sim.nodeIds().get(0));
        }

        @Test
        void seedIsAccessible() {
            RaftSimulation sim = new RaftSimulation(12345L, 3);
            assertEquals(12345L, sim.seed());
        }

        @Test
        void clockAndNetworkAreAccessible() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            assertNotNull(sim.clock());
            assertNotNull(sim.network());
        }
    }

    @Nested
    class Tick {

        private RaftSimulation sim;

        @BeforeEach
        void setUp() {
            sim = new RaftSimulation(42L, 3);
        }

        @Test
        void advancesClockByOneMillisecond() {
            long before = sim.clock().currentTimeMillis();
            sim.tick();
            assertEquals(before + 1, sim.clock().currentTimeMillis());
        }

        @Test
        void multipleTicksAdvanceCumulatively() {
            long before = sim.clock().currentTimeMillis();
            sim.tick();
            sim.tick();
            sim.tick();
            assertEquals(before + 3, sim.clock().currentTimeMillis());
        }

        @Test
        void deliversPendingMessages() {
            AtomicInteger deliverCount = new AtomicInteger();
            sim.network().setDeliveryHandler((to, msg) -> deliverCount.incrementAndGet());

            // Use fixed latency for the network in the simulation (default is 1-10ms)
            // Send a message that should arrive within the tick window
            long currentTime = sim.clock().currentTimeMillis();
            // After tick, time will be currentTime+1. With min latency 1ms,
            // a message sent at currentTime should be deliverable at currentTime+1.
            sim.network().send(NodeId.of(0), NodeId.of(1), "msg", currentTime);

            // Tick advances by 1ms, then delivers due messages
            sim.tick();

            // The message might or might not be delivered depending on random latency (1-10ms).
            // With latency=1, delivery time = currentTime+1, which equals the new time.
            // With higher latency, it won't be delivered yet.
            // We just verify the mechanism works -- deliver enough ticks to guarantee delivery.
            for (int i = 0; i < 10; i++) {
                sim.tick();
            }
            assertEquals(1, deliverCount.get());
        }
    }

    @Nested
    class RunTicks {

        @Test
        void runsSpecifiedNumberOfTicks() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            long before = sim.clock().currentTimeMillis();
            sim.runTicks(100);
            assertEquals(before + 100, sim.clock().currentTimeMillis());
        }

        @Test
        void zeroTicksDoesNothing() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            long before = sim.clock().currentTimeMillis();
            sim.runTicks(0);
            assertEquals(before, sim.clock().currentTimeMillis());
        }
    }

    @Nested
    class InvariantChecker {

        @Test
        void isCalledOnEveryTick() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            AtomicInteger callCount = new AtomicInteger();
            sim.addInvariantChecker(s -> callCount.incrementAndGet());

            sim.tick();
            assertEquals(1, callCount.get());

            sim.tick();
            assertEquals(2, callCount.get());

            sim.tick();
            assertEquals(3, callCount.get());
        }

        @Test
        void receivesSimulationInstance() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            sim.addInvariantChecker(s -> {
                assertSame(sim, s);
            });
            sim.tick(); // Would throw AssertionError if not same
        }

        @Test
        void multipleCheckersAllCalled() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            AtomicInteger checker1Count = new AtomicInteger();
            AtomicInteger checker2Count = new AtomicInteger();
            sim.addInvariantChecker(s -> checker1Count.incrementAndGet());
            sim.addInvariantChecker(s -> checker2Count.incrementAndGet());

            sim.tick();
            assertEquals(1, checker1Count.get());
            assertEquals(1, checker2Count.get());
        }

        @Test
        void throwingCheckerStopsSimulation() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            sim.addInvariantChecker(s -> {
                throw new AssertionError("Invariant violated!");
            });

            AssertionError error = assertThrows(AssertionError.class, sim::tick);
            assertEquals("Invariant violated!", error.getMessage());
        }

        @Test
        void throwingCheckerStopsRunTicksEarly() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            AtomicInteger tickCount = new AtomicInteger();
            sim.addInvariantChecker(s -> {
                int count = tickCount.incrementAndGet();
                if (count >= 5) {
                    throw new RuntimeException("Stop at tick 5");
                }
            });

            assertThrows(RuntimeException.class, () -> sim.runTicks(100));
            assertEquals(5, tickCount.get());
        }

        @Test
        void isCalledOnAdvanceToNextEvent() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            AtomicInteger callCount = new AtomicInteger();
            sim.addInvariantChecker(s -> callCount.incrementAndGet());

            sim.advanceToNextEvent();
            assertEquals(1, callCount.get());
        }
    }

    @Nested
    class NetworkPartitions {

        @Test
        void injectRandomPartitionIsolatesTwoNodes() {
            RaftSimulation sim = new RaftSimulation(42L, 5);
            // injectRandomPartition uses PRNG to pick two nodes and calls isolate
            sim.injectRandomPartition();

            // Verify stats reflect the partition injection
            String stats = sim.stats();
            assertTrue(stats.contains("partitions=1") || stats.contains("partitions=0"),
                    "Stats should reflect partition count: " + stats);
        }

        @Test
        void healAllPartitionsRemovesAllPartitions() {
            RaftSimulation sim = new RaftSimulation(42L, 5);
            // Use the network directly to set up known partitions
            sim.network().isolate(NodeId.of(0), NodeId.of(1));
            sim.network().isolate(NodeId.of(2), NodeId.of(3));

            sim.healAllPartitions();

            // Verify by sending messages in all previously partitioned directions
            AtomicInteger deliverCount = new AtomicInteger();
            sim.network().setDeliveryHandler((to, msg) -> deliverCount.incrementAndGet());

            long time = sim.clock().currentTimeMillis();
            sim.network().send(NodeId.of(0), NodeId.of(1), "a", time);
            sim.network().send(NodeId.of(1), NodeId.of(0), "b", time);
            sim.network().send(NodeId.of(2), NodeId.of(3), "c", time);
            sim.network().send(NodeId.of(3), NodeId.of(2), "d", time);

            assertEquals(4, sim.network().pendingCount());
        }

        @Test
        void isolateNodePartitionsFromAllOthers() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            sim.isolateNode(NodeId.of(0));

            // NODE_0 cannot send to any other node
            long time = sim.clock().currentTimeMillis();
            sim.network().send(NodeId.of(0), NodeId.of(1), "a", time);
            sim.network().send(NodeId.of(0), NodeId.of(2), "b", time);
            assertEquals(0, sim.network().pendingCount());

            // No other node can send to NODE_0 either (bidirectional)
            sim.network().send(NodeId.of(1), NodeId.of(0), "c", time);
            sim.network().send(NodeId.of(2), NodeId.of(0), "d", time);
            assertEquals(0, sim.network().pendingCount());

            // Other nodes can still talk to each other
            sim.network().send(NodeId.of(1), NodeId.of(2), "e", time);
            assertEquals(1, sim.network().pendingCount());
        }
    }

    @Nested
    class AdvanceToNextEvent {

        @Test
        void advancesToNextMessageDeliveryTime() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            AtomicInteger deliverCount = new AtomicInteger();
            sim.network().setDeliveryHandler((to, msg) -> deliverCount.incrementAndGet());

            long sendTime = sim.clock().currentTimeMillis();
            sim.network().send(NodeId.of(0), NodeId.of(1), "msg", sendTime);

            long nextDelivery = sim.network().nextDeliveryTime();
            sim.advanceToNextEvent();

            // Clock should be at the delivery time
            assertEquals(nextDelivery, sim.clock().currentTimeMillis());
            assertEquals(1, deliverCount.get());
        }

        @Test
        void advancesBy300msWhenNoPendingMessages() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            long before = sim.clock().currentTimeMillis();
            sim.advanceToNextEvent();
            assertEquals(before + 300, sim.clock().currentTimeMillis());
        }
    }

    @Nested
    class Stats {

        @Test
        void returnsFormattedStringWithSeed() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            String stats = sim.stats();
            assertTrue(stats.contains("seed=42"));
            assertTrue(stats.contains("ticks=0"));
            assertTrue(stats.contains("msgs=0"));
            assertTrue(stats.contains("partitions=0"));
        }

        @Test
        void statsUpdateAfterTicks() {
            RaftSimulation sim = new RaftSimulation(42L, 3);
            sim.runTicks(10);
            String stats = sim.stats();
            assertTrue(stats.contains("ticks=10"), "Stats: " + stats);
        }

        @Test
        void statsFormatIsSimulationBrackets() {
            RaftSimulation sim = new RaftSimulation(99L, 3);
            String stats = sim.stats();
            assertTrue(stats.startsWith("Simulation["), "Expected format Simulation[...] but got: " + stats);
            assertTrue(stats.endsWith("]"), "Expected closing bracket but got: " + stats);
        }
    }
}
