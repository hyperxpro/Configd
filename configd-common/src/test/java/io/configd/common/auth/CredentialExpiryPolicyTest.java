package io.configd.common.auth;

import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialExpiryPolicyTest {

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

    @Test
    void tokenWindowUsesFractionInsideTheBand() {
        CredentialExpiryPolicy p = CredentialExpiryPolicy.DEFAULTS; // token 0.20, floor 30s, ceil 5m
        assertEquals(300_000L, p.tokenRefreshWindowMs(3_600_000L), "0.20*1h exceeds the 5m ceil -> clamped");
        assertEquals(120_000L, p.tokenRefreshWindowMs(600_000L), "0.20*10m is inside the band");
        assertEquals(30_000L, p.tokenRefreshWindowMs(60_000L), "0.20*60s below floor -> floor");
    }

    @Test
    void certWindowUsesItsOwnBand() {
        CredentialExpiryPolicy p = CredentialExpiryPolicy.DEFAULTS; // cert 0.10, floor 5m, ceil 1h
        long ninetyDays = 90L * 24 * 3_600_000L;
        assertEquals(3_600_000L, p.certRefreshWindowMs(ninetyDays), "0.10*90d clamps to the 1h ceil");
        assertEquals(360_000L, p.certRefreshWindowMs(3_600_000L), "0.10*1h inside the band");
        assertEquals(300_000L, p.certRefreshWindowMs(600_000L), "0.10*10m below floor -> floor");
    }

    @Test
    void nonPositiveLifetimeYieldsTheFloor() {
        CredentialExpiryPolicy p = CredentialExpiryPolicy.DEFAULTS;
        assertEquals(30_000L, p.tokenRefreshWindowMs(0L), "zero lifetime -> floor");
        assertEquals(30_000L, p.tokenRefreshWindowMs(-1L), "negative lifetime -> floor");
    }

    @Test
    void closeDeadlineAddsLeeway() {
        CredentialExpiryPolicy p = CredentialExpiryPolicy.DEFAULTS; // leeway 60s
        assertEquals(1_000_060_000L, p.serverCloseDeadlineMillis(1_000_000_000L), "expiresAt + 60s leeway");
    }

    @Test
    void closeDeadlineSaturatesRatherThanOverflowing() {
        CredentialExpiryPolicy p = CredentialExpiryPolicy.DEFAULTS;
        assertEquals(Long.MAX_VALUE, p.serverCloseDeadlineMillis(Long.MAX_VALUE - 5),
                "a far-future notAfter + leeway must saturate, never wrap negative");
    }

    @Test
    void fromConfigDefaultsMatchTheFinding() {
        CredentialExpiryPolicy p = CredentialExpiryPolicy.fromConfig(cfg(Map.of()));
        assertEquals(CredentialExpiryPolicy.DEFAULTS, p, "an empty config reproduces the recommended defaults");
    }

    @Test
    void fromConfigOverridesAreApplied() {
        CredentialExpiryPolicy p = CredentialExpiryPolicy.fromConfig(cfg(Map.of(
                "configd.auth.expiry.tokenWindowFraction", "0.5",
                "configd.auth.expiry.tokenWindowFloorMs", "1000",
                "configd.auth.clockSkewLeewayMs", "5000")));
        assertEquals(0.5, p.tokenWindowFraction());
        assertEquals(1000L, p.tokenWindowFloorMs());
        assertEquals(5000L, p.clockSkewLeewayMs());
    }

    @Test
    void fromConfigFailsClosedOnAGarbageFraction() {
        ConfigException e = assertThrows(ConfigException.class, () -> CredentialExpiryPolicy.fromConfig(
                cfg(Map.of("configd.auth.expiry.tokenWindowFraction", "not-a-number"))));
        assertTrue(e.getMessage().contains("tokenWindowFraction"));
    }

    @Test
    void constructorRejectsAnOutOfRangeFraction() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialExpiryPolicy(
                1.5, 30_000L, 300_000L, 0.10, 300_000L, 3_600_000L, 60_000L));
    }

    @Test
    void constructorRejectsCeilBelowFloor() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialExpiryPolicy(
                0.20, 300_000L, 30_000L, 0.10, 300_000L, 3_600_000L, 60_000L));
    }
}
