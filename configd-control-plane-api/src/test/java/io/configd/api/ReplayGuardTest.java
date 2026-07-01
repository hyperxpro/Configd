package io.configd.api;

import io.configd.common.Clock;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReplayGuard} unit coverage. The HTTP-level verbatim-replay attack lives
 * in {@code ConfigHandlerReplayTest}; these tests pin the guard's
 * accept/stale/replay decisions and its bound deterministically with a fake clock.
 */
final class ReplayGuardTest {

    private static Clock clock(AtomicLong millis) {
        return new Clock() {
            @Override public long currentTimeMillis() { return millis.get(); }
            @Override public long nanoTime() { return millis.get() * 1_000_000L; }
        };
    }

    @Test
    void freshRequestIsAccepted() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ReplayGuard guard = new ReplayGuard(clock(now), 300_000L, 1000);
        assertEquals(ReplayGuard.Decision.ACCEPTED,
                guard.check(String.valueOf(now.get()), "nonce-1"));
    }

    @Test
    void replayingTheSameNonceIsRejected() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ReplayGuard guard = new ReplayGuard(clock(now), 300_000L, 1000);
        assertEquals(ReplayGuard.Decision.ACCEPTED, guard.check(String.valueOf(now.get()), "nonce-1"));
        // Verbatim replay (same nonce, same in-window timestamp) -> REPLAY.
        assertEquals(ReplayGuard.Decision.REPLAY, guard.check(String.valueOf(now.get()), "nonce-1"));
    }

    @Test
    void differentNoncesAreEachAccepted() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ReplayGuard guard = new ReplayGuard(clock(now), 300_000L, 1000);
        assertEquals(ReplayGuard.Decision.ACCEPTED, guard.check(String.valueOf(now.get()), "n-1"));
        assertEquals(ReplayGuard.Decision.ACCEPTED, guard.check(String.valueOf(now.get()), "n-2"));
    }

    @Test
    void staleTimestampIsRejected() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ReplayGuard guard = new ReplayGuard(clock(now), 300_000L, 1000);
        long stale = now.get() - 300_001L; // just outside the window
        assertEquals(ReplayGuard.Decision.STALE, guard.check(String.valueOf(stale), "nonce-1"));
    }

    @Test
    void futureTimestampOutsideWindowIsRejected() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ReplayGuard guard = new ReplayGuard(clock(now), 300_000L, 1000);
        long future = now.get() + 300_001L;
        assertEquals(ReplayGuard.Decision.STALE, guard.check(String.valueOf(future), "nonce-1"));
    }

    @Test
    void missingOrMalformedHeadersAreRejected() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ReplayGuard guard = new ReplayGuard(clock(now), 300_000L, 1000);
        assertEquals(ReplayGuard.Decision.MALFORMED, guard.check(null, "n"));
        assertEquals(ReplayGuard.Decision.MALFORMED, guard.check(String.valueOf(now.get()), null));
        assertEquals(ReplayGuard.Decision.MALFORMED, guard.check("not-a-number", "n"));
        assertEquals(ReplayGuard.Decision.MALFORMED, guard.check("  ", "n"));
    }

    @Test
    void aNonceBecomesReusableOnceItsTimestampLeavesTheWindow() {
        // Honest scope: the nonce is only retained for the window. Once time
        // advances past the window, the same nonce string would itself be STALE
        // (its old timestamp), so eviction loses no protection.
        AtomicLong now = new AtomicLong(1_000_000L);
        ReplayGuard guard = new ReplayGuard(clock(now), 1000L, 1000); // 1s window
        assertEquals(ReplayGuard.Decision.ACCEPTED, guard.check(String.valueOf(now.get()), "n"));
        // Advance well past the window; the entry is evicted lazily on next check.
        now.addAndGet(5000L);
        // A fresh request with a CURRENT timestamp but the recycled nonce is
        // accepted again (the old sighting expired). A replay with the OLD
        // timestamp would be STALE regardless.
        assertEquals(ReplayGuard.Decision.ACCEPTED, guard.check(String.valueOf(now.get()), "n"));
    }

    @Test
    void nonceStoreIsBoundedByMaxNonces() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ReplayGuard guard = new ReplayGuard(clock(now), 300_000L, 100); // tiny cap
        // Flood 10_000 unique nonces, all in-window (same instant).
        for (int i = 0; i < 10_000; i++) {
            guard.check(String.valueOf(now.get()), "flood-" + i);
        }
        assertTrue(guard.trackedNonces() <= 100,
                "the nonce store must stay within the cap under a flood: " + guard.trackedNonces());
    }

    @Test
    void constructorRejectsNonPositiveBounds() {
        AtomicLong now = new AtomicLong(0L);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ReplayGuard(clock(now), 0L, 100));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ReplayGuard(clock(now), 100L, 0));
    }
}
