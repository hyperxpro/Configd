package io.configd.api;

import io.configd.common.Clock;
import io.configd.common.ConfigScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Per-principal rate limiting: one tenant's write flood must NOT starve another tenant's writes. A
 * per-principal token bucket (factory-minted, keyed by the authenticated principal) replaces the single
 * global bucket, so principal A exhausting its OWN budget leaves B's intact. The gate stays before the
 * Raft proposal (a CAS on the request thread, never the tick thread).
 */
class ConfigWriteServicePerPrincipalRateLimitTest {

    /** Fixed clock: time never advances, so a token bucket never refills - deterministic exhaustion. */
    private static final Clock FIXED = new Clock() {
        @Override public long currentTimeMillis() { return 1_000L; }
        @Override public long nanoTime() { return 1_000_000_000L; }
    };

    private static final ConfigWriteService.RaftProposer ALWAYS_COMMIT =
            (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Committed(1L);

    @Test
    void oneTenantsFloodDoesNotStarveAnother() {
        // Each principal gets its OWN bucket: rate 1/s, burst 1 (one token; no refill under FIXED).
        ConfigWriteService svc = new ConfigWriteService(
                ALWAYS_COMMIT, null, null, null,
                () -> new RateLimiter(FIXED, 1, 1));

        byte[] v = "v".getBytes();
        // Principal A: the first write consumes A's single token; the rest are shed by A's OWN bucket.
        assertInstanceOf(ConfigWriteService.WriteResult.Committed.class,
                svc.put("a1", v, ConfigScope.GLOBAL, "A"), "A's first write succeeds");
        assertInstanceOf(ConfigWriteService.WriteResult.Overloaded.class,
                svc.put("a2", v, ConfigScope.GLOBAL, "A"), "A's flood is shed by A's own bucket");
        assertInstanceOf(ConfigWriteService.WriteResult.Overloaded.class,
                svc.put("a3", v, ConfigScope.GLOBAL, "A"), "A stays shed");

        // Principal B: unaffected by A's flood - B's OWN fresh bucket grants its first write.
        assertInstanceOf(ConfigWriteService.WriteResult.Committed.class,
                svc.put("b1", v, ConfigScope.GLOBAL, "B"),
                "B is NOT starved by A's flood — per-principal isolation is the fix");
    }

    @Test
    void legacyGlobalLimiterStillGatesWhenNoPerPrincipalFactory() {
        // Backward compatibility: no per-principal factory -> the single global bucket gates (legacy).
        ConfigWriteService svc = new ConfigWriteService(
                ALWAYS_COMMIT, null, new RateLimiter(FIXED, 1, 1)); // 3-arg legacy ctor
        byte[] v = "v".getBytes();
        assertInstanceOf(ConfigWriteService.WriteResult.Committed.class,
                svc.put("k1", v, ConfigScope.GLOBAL, "A"));
        // Same global bucket: A's second AND B's first are both shed - the shared-bucket behavior
        // the per-principal path avoids (kept as a guard that the legacy path is unchanged).
        assertInstanceOf(ConfigWriteService.WriteResult.Overloaded.class,
                svc.put("k2", v, ConfigScope.GLOBAL, "A"));
        assertInstanceOf(ConfigWriteService.WriteResult.Overloaded.class,
                svc.put("k3", v, ConfigScope.GLOBAL, "B"),
                "without per-principal buckets B shares A's exhausted global bucket (legacy contrast)");
    }
}
