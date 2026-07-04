package io.configd.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production anchor-witness mode defaults to STRICT (Gate 3c, operator ruling). The available mode
 * (self-counting boot quorum) carries an adversary-reachable R-a' residual, so the out-of-box posture
 * must be strict; {@code -Dconfigd.raft.witnessStrict=false} is the explicit opt-in to the higher-
 * availability mode. This pins the default that {@code ConfigdServer.buildRaftGroup} arms nodes with.
 */
final class WitnessStrictDefaultTest {

    private static final String PROP = "configd.raft.witnessStrict";

    @AfterEach
    void clearProp() {
        System.clearProperty(PROP);
    }

    @Test
    void unsetDefaultsToStrict() {
        System.clearProperty(PROP);
        assertTrue(ConfigdServer.witnessStrictEnabled(),
                "out-of-box (unset) must arm STRICT - the recommended posture must close R-a'");
    }

    @Test
    void explicitFalseSelectsAvailableMode() {
        System.setProperty(PROP, "false");
        assertFalse(ConfigdServer.witnessStrictEnabled(),
                "-Dconfigd.raft.witnessStrict=false is the explicit opt-in to the available (residual) mode");
    }

    @Test
    void explicitTrueIsStrict() {
        System.setProperty(PROP, "true");
        assertTrue(ConfigdServer.witnessStrictEnabled());
    }

    @Test
    void anyOtherValueIsStrict_failClosedToTheSafeMode() {
        // A typo or garbage value must not silently drop to the residual-carrying available mode.
        System.setProperty(PROP, "no");
        assertTrue(ConfigdServer.witnessStrictEnabled(), "only an explicit 'false' opts out; anything else is strict");
        System.setProperty(PROP, "");
        assertTrue(ConfigdServer.witnessStrictEnabled(), "empty string is not 'false' - stays strict");
    }
}
