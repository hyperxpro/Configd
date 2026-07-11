package io.configd.edge.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edge accept-filtered opt-in flag ({@code configd.edge.accept_filtered}) defaults off and
 * fails LOUD on an unknown value - an unconfigured edge stays byte-identical on the 0x01 wire.
 */
class AcceptFilteredPostureTest {

    @AfterEach
    void clear() {
        System.clearProperty(EdgeNodeMain.ACCEPT_FILTERED_PROP);
    }

    @Test
    void defaultsOff() {
        System.clearProperty(EdgeNodeMain.ACCEPT_FILTERED_PROP);
        assertFalse(EdgeNodeMain.resolveAcceptFiltered(), "default opt-in is OFF (byte-identical edge)");
    }

    @Test
    void onParses() {
        System.setProperty(EdgeNodeMain.ACCEPT_FILTERED_PROP, "on");
        assertTrue(EdgeNodeMain.resolveAcceptFiltered());
    }

    @Test
    void unknownValueFailsLoud() {
        System.setProperty(EdgeNodeMain.ACCEPT_FILTERED_PROP, "yes-please");
        assertThrows(IllegalArgumentException.class, EdgeNodeMain::resolveAcceptFiltered);
    }
}
