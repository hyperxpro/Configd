package io.configd.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeFilterPostureTest {

    @AfterEach
    void clear() {
        System.clearProperty(ConfigdServer.EDGE_FILTER_PROP);
    }

    @Test
    void defaultsOn() {
        System.clearProperty(ConfigdServer.EDGE_FILTER_PROP);
        assertTrue(ConfigdServer.resolveEdgeFilterPosture(), "default posture is ON (trusted domain)");
    }

    @Test
    void onAndOffParse() {
        System.setProperty(ConfigdServer.EDGE_FILTER_PROP, "on");
        assertTrue(ConfigdServer.resolveEdgeFilterPosture());
        System.setProperty(ConfigdServer.EDGE_FILTER_PROP, "off");
        assertFalse(ConfigdServer.resolveEdgeFilterPosture());
        System.setProperty(ConfigdServer.EDGE_FILTER_PROP, "OFF");
        assertFalse(ConfigdServer.resolveEdgeFilterPosture(), "case-insensitive");
    }

    @Test
    void unknownValueFailsLoud() {
        System.setProperty(ConfigdServer.EDGE_FILTER_PROP, "maybe");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, ConfigdServer::resolveEdgeFilterPosture);
        assertEquals(true, ex.getMessage().contains(ConfigdServer.EDGE_FILTER_PROP));
    }
}
