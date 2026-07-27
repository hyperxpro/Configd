package io.configd.edge.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
