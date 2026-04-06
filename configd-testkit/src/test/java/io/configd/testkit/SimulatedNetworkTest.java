package io.configd.testkit;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedNetworkTest {

    private static final NodeId NODE_0 = NodeId.of(0);
    private static final NodeId NODE_1 = NodeId.of(1);
    private static final NodeId NODE_2 = NodeId.of(2);

    @Nested
    class SendAndDelivery {

        private SimulatedNetwork network;
        private List<Object> delivered;
        private List<NodeId> deliveredTo;

        @BeforeEach
        void setUp() {
            // Use fixed latency range (5-5ms) for predictable delivery time
            network = new SimulatedNetwork(42L, 5, 5);
            delivered = new ArrayList<>();
            deliveredTo = new ArrayList<>();
            network.setDeliveryHandler((to, msg) -> {
                deliveredTo.add(to);
                delivered.add(msg);
            });
        }

        @Test
        void messageArrivesAfterLatency() {
            network.send(NODE_0, NODE_1, "hello", 1000L);
            int count = network.deliverDue(1005L);
            assertEquals(1, count);
            assertEquals(List.of("hello"), delivered);
            assertEquals(List.of(NODE_1), deliveredTo);
        }

        @Test
        void messageNotDeliveredBeforeItsTime() {
            network.send(NODE_0, NODE_1, "hello", 1000L);
            // With latency=5, deliver at 1005. Try at 1004.
            int count = network.deliverDue(1004L);
            assertEquals(0, count);
            assertTrue(delivered.isEmpty());
        }

        @Test
        void messageDeliveredExactlyAtDeliveryTime() {
            network.send(NODE_0, NODE_1, "hello", 1000L);
            int count = network.deliverDue(1005L);
            assertEquals(1, count);
            assertEquals(1, delivered.size());
        }

        @Test
        void multipleMessagesDeliveredInOrder() {
            // Send messages at different times, all with 5ms latency
            network.send(NODE_0, NODE_1, "first", 1000L);   // delivers at 1005
            network.send(NODE_0, NODE_1, "second", 1002L);  // delivers at 1007
            network.send(NODE_0, NODE_1, "third", 1001L);   // delivers at 1006

            int count = network.deliverDue(1010L);
            assertEquals(3, count);
            // PriorityQueue orders by deliverAtMs
            assertEquals(List.of("first", "third", "second"), delivered);
        }

        @Test
        void noDeliveryWithoutHandler() {
            SimulatedNetwork net = new SimulatedNetwork(42L, 5, 5);
            // No handler set
            net.send(NODE_0, NODE_1, "msg", 1000L);
            // deliverDue should still remove and count the message
            int count = net.deliverDue(1005L);
            assertEquals(1, count);
            assertEquals(0, net.pendingCount());
        }

        @Test
        void deliverDueReturnsZeroWhenNoPending() {
            int count = network.deliverDue(9999L);
            assertEquals(0, count);
        }
    }

    @Nested
    class PendingState {

        private SimulatedNetwork network;

        @BeforeEach
        void setUp() {
            network = new SimulatedNetwork(42L, 5, 5);
        }

        @Test
        void pendingCountReflectsQueuedMessages() {
            assertEquals(0, network.pendingCount());
            network.send(NODE_0, NODE_1, "a", 1000L);
            assertEquals(1, network.pendingCount());
            network.send(NODE_0, NODE_1, "b", 1000L);
            assertEquals(2, network.pendingCount());
        }

        @Test
        void hasPendingAfterSend() {
            assertFalse(network.hasPending());
            network.send(NODE_0, NODE_1, "msg", 1000L);
            assertTrue(network.hasPending());
        }

        @Test
        void notHasPendingAfterFullDelivery() {
            network.send(NODE_0, NODE_1, "msg", 1000L);
            assertTrue(network.hasPending());
            network.deliverDue(1005L);
            assertFalse(network.hasPending());
        }

        @Test
        void nextDeliveryTimeReturnsCorrectTime() {
            assertEquals(Long.MAX_VALUE, network.nextDeliveryTime());
            network.send(NODE_0, NODE_1, "msg", 1000L);
            // With fixed latency=5, delivery at 1005
            assertEquals(1005L, network.nextDeliveryTime());
        }

        @Test
        void nextDeliveryTimeReturnsEarliestTime() {
            network.send(NODE_0, NODE_1, "later", 1010L);   // delivers at 1015
            network.send(NODE_0, NODE_1, "earlier", 1000L);  // delivers at 1005
            assertEquals(1005L, network.nextDeliveryTime());
        }

        @Test
        void nextDeliveryTimeReturnsMaxValueWhenEmpty() {
            assertEquals(Long.MAX_VALUE, network.nextDeliveryTime());
        }
    }

    @Nested
    class Partitions {

        private SimulatedNetwork network;
        private List<Object> delivered;

        @BeforeEach
        void setUp() {
            network = new SimulatedNetwork(42L, 5, 5);
            delivered = new ArrayList<>();
            network.setDeliveryHandler((to, msg) -> delivered.add(msg));
        }

        @Test
        void partitionDropsMessagesInPartitionedDirection() {
            network.addPartition(NODE_0, NODE_1);
            network.send(NODE_0, NODE_1, "blocked", 1000L);
            // Message should be dropped at send time
            assertEquals(0, network.pendingCount());
            network.deliverDue(1005L);
            assertTrue(delivered.isEmpty());
        }

        @Test
        void partitionIsUnidirectional() {
            network.addPartition(NODE_0, NODE_1);

            // A->B is blocked
            network.send(NODE_0, NODE_1, "blocked", 1000L);
            assertEquals(0, network.pendingCount());

            // B->A is still open
            network.send(NODE_1, NODE_0, "allowed", 1000L);
            assertEquals(1, network.pendingCount());
            network.deliverDue(1005L);
            assertEquals(List.of("allowed"), delivered);
        }

        @Test
        void isolateCreatesBidirectionalPartition() {
            network.isolate(NODE_0, NODE_1);

            network.send(NODE_0, NODE_1, "ab", 1000L);
            network.send(NODE_1, NODE_0, "ba", 1000L);

            assertEquals(0, network.pendingCount());
            network.deliverDue(1005L);
            assertTrue(delivered.isEmpty());
        }

        @Test
        void isolateDoesNotAffectThirdParty() {
            network.isolate(NODE_0, NODE_1);

            // NODE_0 -> NODE_2 should still work
            network.send(NODE_0, NODE_2, "ok", 1000L);
            assertEquals(1, network.pendingCount());
            network.deliverDue(1005L);
            assertEquals(List.of("ok"), delivered);
        }

        @Test
        void removePartitionRestoresConnectivity() {
            network.addPartition(NODE_0, NODE_1);
            network.removePartition(NODE_0, NODE_1);

            network.send(NODE_0, NODE_1, "restored", 1000L);
            assertEquals(1, network.pendingCount());
            network.deliverDue(1005L);
            assertEquals(List.of("restored"), delivered);
        }

        @Test
        void healAllRemovesAllPartitions() {
            network.isolate(NODE_0, NODE_1);
            network.addPartition(NODE_0, NODE_2);
            network.healAll();

            network.send(NODE_0, NODE_1, "healed1", 1000L);
            network.send(NODE_1, NODE_0, "healed2", 1000L);
            network.send(NODE_0, NODE_2, "healed3", 1000L);

            assertEquals(3, network.pendingCount());
            network.deliverDue(1005L);
            assertEquals(3, delivered.size());
        }

        @Test
        void partitionAddedAfterSendBlocksDelivery() {
            // Send message first (gets enqueued)
            network.send(NODE_0, NODE_1, "enqueued", 1000L);
            assertEquals(1, network.pendingCount());

            // Add partition before delivery
            network.addPartition(NODE_0, NODE_1);

            // deliverDue checks partition at delivery time
            int count = network.deliverDue(1005L);
            assertEquals(0, count);
            assertTrue(delivered.isEmpty());
            // Message is removed from queue even though not delivered
            assertEquals(0, network.pendingCount());
        }
    }

    @Nested
    class DropRate {

        @Test
        void zeroDropRateDeliversAllMessages() {
            SimulatedNetwork network = new SimulatedNetwork(42L, 5, 5);
            network.setDropRate(0.0);
            AtomicInteger deliveredCount = new AtomicInteger();
            network.setDeliveryHandler((to, msg) -> deliveredCount.incrementAndGet());

            int sendCount = 100;
            for (int i = 0; i < sendCount; i++) {
                network.send(NODE_0, NODE_1, "msg" + i, 1000L);
            }
            network.deliverDue(1005L);
            assertEquals(sendCount, deliveredCount.get());
        }

        @Test
        void fullDropRateDeliversNoMessages() {
            SimulatedNetwork network = new SimulatedNetwork(42L, 5, 5);
            network.setDropRate(1.0);

            int sendCount = 100;
            for (int i = 0; i < sendCount; i++) {
                network.send(NODE_0, NODE_1, "msg" + i, 1000L);
            }
            // All messages should be dropped at send time
            assertEquals(0, network.pendingCount());
        }

        @Test
        void partialDropRateDropsSomeMessages() {
            SimulatedNetwork network = new SimulatedNetwork(42L, 5, 5);
            network.setDropRate(0.5);

            int sendCount = 1000;
            for (int i = 0; i < sendCount; i++) {
                network.send(NODE_0, NODE_1, "msg" + i, 1000L);
            }
            int pending = network.pendingCount();
            // With 50% drop rate over 1000 messages, we expect roughly 500 to get through.
            // Allow wide tolerance for randomness.
            assertTrue(pending > 100 && pending < 900,
                    "Expected roughly 500 pending messages but got " + pending);
        }
    }

    @Nested
    class LatencyRange {

        @Test
        void messagesDeliveredWithinLatencyRange() {
            int minLatency = 10;
            int maxLatency = 20;
            SimulatedNetwork network = new SimulatedNetwork(42L, minLatency, maxLatency);

            long sendTime = 1000L;
            network.send(NODE_0, NODE_1, "msg", sendTime);

            long deliveryTime = network.nextDeliveryTime();
            assertTrue(deliveryTime >= sendTime + minLatency,
                    "Delivery time " + deliveryTime + " is before min latency");
            assertTrue(deliveryTime <= sendTime + maxLatency,
                    "Delivery time " + deliveryTime + " is after max latency");
        }
    }

    @Nested
    class PendingMessageRecord {

        @Test
        void comparableOrdersByDeliveryTime() {
            var early = new SimulatedNetwork.PendingMessage(100L, NODE_0, NODE_1, "early");
            var late = new SimulatedNetwork.PendingMessage(200L, NODE_0, NODE_1, "late");
            assertTrue(early.compareTo(late) < 0);
            assertTrue(late.compareTo(early) > 0);
        }

        @Test
        void equalDeliveryTimesCompareAsZero() {
            var a = new SimulatedNetwork.PendingMessage(100L, NODE_0, NODE_1, "a");
            var b = new SimulatedNetwork.PendingMessage(100L, NODE_0, NODE_1, "b");
            assertEquals(0, a.compareTo(b));
        }

        @Test
        void recordAccessors() {
            var msg = new SimulatedNetwork.PendingMessage(150L, NODE_0, NODE_1, "payload");
            assertEquals(150L, msg.deliverAtMs());
            assertEquals(NODE_0, msg.from());
            assertEquals(NODE_1, msg.to());
            assertEquals("payload", msg.message());
        }
    }
}
