package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The off/lax/strict revocation posture: the admit + alarm decision matrix and fail-closed config. */
class RevocationPolicyTest {

    private static ConfigSource cfg(Map<String, String> m) {
        return new ConfigSource() {
            @Override public Optional<String> getString(String key) {
                return Optional.ofNullable(m.get(key));
            }
            @Override public Set<String> keysWithPrefix(String prefix) {
                return m.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
            }
        };
    }

    private static RevocationPolicy mode(RevocationMode m) {
        return new RevocationPolicy(m, true, 3_000L);
    }

    // ---- the admission matrix (the heart of lax-vs-strict) ----

    @Test
    void offAdmitsEverything() {
        RevocationPolicy off = mode(RevocationMode.OFF);
        for (RevocationStatus s : RevocationStatus.values()) {
            assertTrue(off.admits(s), "OFF admits " + s);
            assertFalse(off.shouldAlarm(s), "OFF never alarms");
        }
        assertFalse(off.enabled());
    }

    @Test
    void laxFailsOpenOnUnknownButRejectsRevoked() {
        RevocationPolicy lax = mode(RevocationMode.LAX);
        assertTrue(lax.admits(RevocationStatus.GOOD));
        assertFalse(lax.admits(RevocationStatus.REVOKED), "lax still rejects a definite revoked");
        assertTrue(lax.admits(RevocationStatus.UNKNOWN), "lax fails OPEN on an unreachable responder");
        assertTrue(lax.shouldAlarm(RevocationStatus.UNKNOWN), "lax alarms on unreachable");
        assertFalse(lax.shouldAlarm(RevocationStatus.GOOD));
        assertTrue(lax.enabled());
    }

    @Test
    void strictFailsClosedOnUnknownAndRevoked() {
        RevocationPolicy strict = mode(RevocationMode.STRICT);
        assertTrue(strict.admits(RevocationStatus.GOOD), "strict admits only a definite good");
        assertFalse(strict.admits(RevocationStatus.REVOKED));
        assertFalse(strict.admits(RevocationStatus.UNKNOWN), "strict fails CLOSED on an unreachable responder");
        assertTrue(strict.shouldAlarm(RevocationStatus.UNKNOWN), "strict also alarms on unreachable");
    }

    // ---- mode parsing: fail-closed ----

    @Test
    void parseIsCaseInsensitive() {
        assertEquals(RevocationMode.OFF, RevocationMode.parse("OFF"));
        assertEquals(RevocationMode.LAX, RevocationMode.parse(" lax "));
        assertEquals(RevocationMode.STRICT, RevocationMode.parse("Strict"));
    }

    @Test
    void parseRejectsAnUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> RevocationMode.parse("soft-fail"));
    }

    // ---- fromConfig: safe default + the exempt-inter-node invariant ----

    @Test
    void fromConfigDefaultsToOffAndInteriorExempt() {
        RevocationPolicy p = RevocationPolicy.fromConfig(cfg(Map.of()));
        assertEquals(RevocationMode.OFF, p.mode(), "unconfigured revocation is OFF (byte-identical)");
        assertTrue(p.exemptInterNode(), "the interior is exempt by default");
        assertFalse(p.enabled());
    }

    @Test
    void fromConfigReadsModeAndExemption() {
        RevocationPolicy p = RevocationPolicy.fromConfig(cfg(Map.of(
                "configd.auth.revocation.mode", "strict",
                "configd.auth.revocation.exemptInterNode", "false")));
        assertEquals(RevocationMode.STRICT, p.mode());
        assertFalse(p.exemptInterNode(), "an operator can (dangerously) disable the exemption flag");
    }

    @Test
    void fromConfigFailsClosedOnAGarbageMode() {
        assertThrows(IllegalArgumentException.class, () -> RevocationPolicy.fromConfig(
                cfg(Map.of("configd.auth.revocation.mode", "sometimes"))));
    }
}
