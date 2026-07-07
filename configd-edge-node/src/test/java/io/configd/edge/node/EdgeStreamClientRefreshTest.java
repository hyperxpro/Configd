package io.configd.edge.node;

import io.configd.common.auth.CredentialExpiryPolicy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure proactive-refresh decision: the edge client renews a re-presentable token ONCE it enters the
 * lead-time window {@code now >= expiresAt - W}, and never for a credential with no expiry. The window
 * math itself is proven by {@code CredentialExpiryPolicyTest}; this pins the client's use of it.
 */
class EdgeStreamClientRefreshTest {

    private static final CredentialExpiryPolicy DEFAULTS = CredentialExpiryPolicy.DEFAULTS;

    @Test
    void neverRefreshesACredentialWithoutExpiry() {
        assertFalse(EdgeStreamClient.shouldRefreshNow(
                Long.MAX_VALUE - 1, EdgeStreamClient.NO_EXPIRY, 600_000L, DEFAULTS),
                "a static token / cert (NO_EXPIRY) is never proactively refreshed");
    }

    @Test
    void doesNotRefreshBeforeTheWindowOpens() {
        // lifetime 10m -> token window W = 0.20*600_000 = 120_000ms (inside [30s, 5m]).
        // expiresAt = 1_000_000; window opens at 880_000.
        assertFalse(EdgeStreamClient.shouldRefreshNow(879_999L, 1_000_000L, 600_000L, DEFAULTS),
                "one ms before the window opens -> no refresh yet");
    }

    @Test
    void refreshesOnceTheWindowOpens() {
        assertTrue(EdgeStreamClient.shouldRefreshNow(880_000L, 1_000_000L, 600_000L, DEFAULTS),
                "at the window boundary -> refresh");
        assertTrue(EdgeStreamClient.shouldRefreshNow(999_999L, 1_000_000L, 600_000L, DEFAULTS),
                "inside the window (still ahead of hard-expiry) -> refresh");
    }

    @Test
    void refreshesEvenPastExpiryAsABackstop() {
        // Past hard-expiry the client still tries to renew (the server closes CREDENTIAL_EXPIRED; a renew
        // in-flight can still land before the close reaches the reader).
        assertTrue(EdgeStreamClient.shouldRefreshNow(1_000_001L, 1_000_000L, 600_000L, DEFAULTS));
    }
}
