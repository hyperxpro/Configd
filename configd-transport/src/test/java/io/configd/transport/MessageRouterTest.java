package io.configd.transport;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MessageRouterTest {

    @Test
    void routeToRegisteredGroup() {
        MessageRouter router = new MessageRouter();
        AtomicReference<Object> received = new AtomicReference<>();

        router.registerGroup(1, (from, groupId, msg) -> received.set(msg));

        assertTrue(router.route(NodeId.of(1), 1, "hello"));
        assertEquals("hello", received.get());
    }

    @Test
    void routeToDefaultHandler() {
        MessageRouter router = new MessageRouter();
        AtomicReference<Object> received = new AtomicReference<>();

        router.setDefaultHandler((from, groupId, msg) -> received.set(msg));

        assertTrue(router.route(NodeId.of(1), 99, "fallback"));
        assertEquals("fallback", received.get());
    }

    @Test
    void routeUnhandledReturnsFalse() {
        MessageRouter router = new MessageRouter();
        assertFalse(router.route(NodeId.of(1), 1, "msg"));
    }

    @Test
    void unregisterGroup() {
        MessageRouter router = new MessageRouter();
        router.registerGroup(1, (from, groupId, msg) -> {});
        assertTrue(router.hasHandler(1));

        router.unregisterGroup(1);
        assertFalse(router.hasHandler(1));
    }

    @Test
    void groupCount() {
        MessageRouter router = new MessageRouter();
        assertEquals(0, router.groupCount());
        router.registerGroup(1, (from, groupId, msg) -> {});
        router.registerGroup(2, (from, groupId, msg) -> {});
        assertEquals(2, router.groupCount());
    }
}
