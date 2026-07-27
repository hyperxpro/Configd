package io.configd.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anchor-witness VOTE mode default. The BOOT gate is always strict (peer-majority) and is not a
 * toggle - it closes the peer-quorum rollback residual at N=3 out of the box.
 * {@code witnessStrictEnabled()} controls only strict-VOTE (deferring voteGranted until a peer-majority
 * acks - the N&gt;=5 absolute close). It is OPT-IN (default fast-vote), because deferral breaks
 * single-fault leader failover (a CI smoke test caught full-strict-default deadlocking a 3-node
 * failover). Only an explicit {@code true} enables it, so a typo cannot silently break failover.
 */
final class WitnessStrictDefaultTest {

    private static final String PROP = "configd.raft.witnessStrict";

    @AfterEach
    void clearProp() {
        System.clearProperty(PROP);
    }

    @Test
    void unsetDefaultsToFastVote() {
        System.clearProperty(PROP);
        assertFalse(ConfigdServer.witnessStrictEnabled(),
                "out-of-box (unset) = fast-vote (strict-boot still closes R-a'); vote-deferral is opt-in");
    }

    @Test
    void explicitTrueEnablesStrictVote() {
        System.setProperty(PROP, "true");
        assertTrue(ConfigdServer.witnessStrictEnabled(),
                "-Dconfigd.raft.witnessStrict=true opts into vote-deferral (N>=5 absolute close)");
    }

    @Test
    void explicitFalseIsFastVote() {
        System.setProperty(PROP, "false");
        assertFalse(ConfigdServer.witnessStrictEnabled());
    }

    @Test
    void anyNonTrueValueIsFastVote_typoCannotBreakFailover() {
        System.setProperty(PROP, "yes");
        assertFalse(ConfigdServer.witnessStrictEnabled(), "only an explicit 'true' opts in; anything else is fast-vote");
        System.setProperty(PROP, "");
        assertFalse(ConfigdServer.witnessStrictEnabled(), "empty string is not 'true' - stays fast-vote");
    }
}
