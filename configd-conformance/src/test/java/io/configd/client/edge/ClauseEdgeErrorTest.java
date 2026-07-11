package io.configd.client.edge;

import io.configd.client.AuthFailedException;
import io.configd.client.Carrier;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.ConfigdException;
import io.configd.client.CredentialExpiredException;
import io.configd.client.ErrorClassifier;
import io.configd.client.ForbiddenException;
import io.configd.client.GapUnrecoverableException;
import io.configd.client.HostileServerLimits;
import io.configd.client.QuarantinedException;
import io.configd.client.Reaction;
import io.configd.client.RetryPolicy;
import io.configd.client.StaleTopologyException;
import io.configd.client.UnavailableException;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference client centralizes the normative {@code (ErrorCode, carrier) -> reaction} mapping in
 * {@link ErrorClassifier} (the single place the client encodes the "each type is its reaction" rule), so
 * asserting the classifier directly is asserting the client's conformance to the edge error taxonomy: the
 * catch-up ladder, the rule that reaction scope is code plus carrier (a pure code switch is insufficient
 * for codes 4, 6, 7, 9, 11, and 12), and the edge column of the 401/403/session-expired split.
 *
 * <p>The classifier assertions are deterministic and exercise the exact production reaction logic the
 * connection state machine drives, with no timing and no sockets. One end-to-end behavioral case
 * additionally drives the real {@link ConfigdEdgeClient} against a {@link MockEdgeServer} that emits a
 * {@code WATCH_CANCELED} with a specific {@code ErrorCode}, proving the per-watch reaction surfaces at the
 * client boundary.
 */
@Timeout(30)
class ClauseEdgeErrorTest {

    @Test
    @Tag("clause:E3-3")
    void reactionScopeIsCodePlusCarrierNotCodeAlone() {
        // The numeric code names the reason; the carrier frame names the scope. An ERROR_CLOSE is
        // connection-fatal (except the non-fatal DEMOTED_TO_CATCHUP); a WATCH_CANCELED is per-watch (the
        // connection and sibling watches survive). For the scope-overloaded codes a driver must key its
        // reaction on both -- the same code yields a different reaction under a different carrier.

        // Code 6 GAP_UNRECOVERABLE: connection-fatal on ERROR_CLOSE (legacy plane) vs per-watch on
        // WATCH_CANCELED (the 0x02 plane: siblings survive). Same exception type, different scope.
        assertFatal(classify(ErrorCode.GAP_UNRECOVERABLE, Carrier.ERROR_CLOSE), GapUnrecoverableException.class);
        assertPerWatch(classify(ErrorCode.GAP_UNRECOVERABLE, Carrier.WATCH_CANCELED), GapUnrecoverableException.class);

        // Code 11 NOT_AUTHORIZED: the 403-class per-watch reject on WATCH_CANCELED (siblings survive);
        // connection-fatal if it ever rides an ERROR_CLOSE. Different scope per carrier.
        assertPerWatch(classify(ErrorCode.NOT_AUTHORIZED, Carrier.WATCH_CANCELED), ForbiddenException.class);
        assertFatal(classify(ErrorCode.NOT_AUTHORIZED, Carrier.ERROR_CLOSE), ForbiddenException.class);

        // Code 12 STALE_TOPOLOGY: carrier-dependent scope (per-watch for a watch, connection-fatal for a
        // legacy SUBSCRIBE). Not used by the older wire version, but the reaction mapping is pinned here now.
        assertFatal(classify(ErrorCode.STALE_TOPOLOGY, Carrier.ERROR_CLOSE), StaleTopologyException.class);
        assertPerWatch(classify(ErrorCode.STALE_TOPOLOGY, Carrier.WATCH_CANCELED), StaleTopologyException.class);

        // Code 9 SERVER_SHUTDOWN: the sharpest carrier split. An ERROR_CLOSE is a genuine server-side close
        // (reconnect), whereas a WATCH_CANCELED is the expected acknowledgement of the driver's own
        // WATCH_CANCEL -- do not reconnect. Same code, two entirely different reaction classes.
        assertFatal(classify(ErrorCode.SERVER_SHUTDOWN, Carrier.ERROR_CLOSE), UnavailableException.class);
        assertInstanceOf(Reaction.CancelAck.class, classify(ErrorCode.SERVER_SHUTDOWN, Carrier.WATCH_CANCELED),
                "SERVER_SHUTDOWN on a WATCH_CANCELED is a cancel-ack, not a reconnect signal");

        // Code 7 DEMOTED_TO_CATCHUP: carrier-independent. The sole non-fatal code, a mode switch regardless
        // of carrier -- the exception that proves the rule above, and the top of the catch-up ladder.
        assertInstanceOf(Reaction.CatchUp.class, classify(ErrorCode.DEMOTED_TO_CATCHUP, Carrier.ERROR_CLOSE));
        assertInstanceOf(Reaction.CatchUp.class, classify(ErrorCode.DEMOTED_TO_CATCHUP, Carrier.WATCH_CANCELED));

        // Code 4 AUTH_FAIL: carrier-overloaded between a framed ERROR_CLOSE (token/basic edge) and an
        // unframed mTLS handshake rejection (a TLS-layer failure that never reaches this classifier). The
        // framed carrier is connection-fatal: (re)authenticate.
        assertFatal(classify(ErrorCode.AUTH_FAIL, Carrier.ERROR_CLOSE), AuthFailedException.class);
    }

    @Test
    @Tag("clause:E3-2")
    void theCatchUpLadderDemotedIsNonFatalQuarantinedEndsTheSession() {
        // DEMOTED_TO_CATCHUP (7) rides an ERROR_CLOSE frame but does not close: it is a mode switch, so a
        // conforming driver keeps the session open (ingest the snapshot, drain and CURSOR_ACK promptly) rather
        // than treating it as a fatal close.
        Reaction demoted = classify(ErrorCode.DEMOTED_TO_CATCHUP, Carrier.ERROR_CLOSE);
        assertInstanceOf(Reaction.CatchUp.class, demoted, "7 is non-fatal — keep streaming in catch-up mode");

        // Ignoring the demotion escalates to QUARANTINED (8), which DOES end the session (back off with your own
        // bounded backoff, reconnect + re-bootstrap after the identity cooldown). This is the ladder: a driver
        // that treats 7 as fatal needlessly drops a recoverable stream; one that ignores it hot-loops the 8.
        Reaction quarantined = classify(ErrorCode.QUARANTINED, Carrier.ERROR_CLOSE);
        assertFatal(quarantined, QuarantinedException.class);
        assertTrue(demoted.getClass() != quarantined.getClass(),
                "the ladder is two rungs: 7 continues the session, 8 ends it");
    }

    @Test
    @Tag("clause:E4-1")
    void the401Vs403SplitOnTheEdgeMirrorsTheHttpPlane() {
        // The same authentication-vs-authorization split as HTTP 401/403, surfaced on the streaming plane
        // and mapped to the same logical reactions.
        // Authentication (no/invalid credential): AUTH_FAIL (4), similar to HTTP 401, means (re)authenticate.
        assertFatal(classify(ErrorCode.AUTH_FAIL, Carrier.ERROR_CLOSE), AuthFailedException.class);
        // Session expired (credential aged out): CREDENTIAL_EXPIRED (13), similar to HTTP 401 (re-present),
        // means re-auth on a fresh connection. Distinct type from AUTH_FAIL so a driver tells "aged out" from
        // "never valid".
        assertFatal(classify(ErrorCode.CREDENTIAL_EXPIRED, Carrier.ERROR_CLOSE), CredentialExpiredException.class);
        // Authorization (authenticated, not permitted): NOT_AUTHORIZED (11), similar to HTTP 403, means
        // permanently forbidden for that target (a per-watch WATCH_CANCELED -- the connection survives,
        // narrow the target).
        assertPerWatch(classify(ErrorCode.NOT_AUTHORIZED, Carrier.WATCH_CANCELED), ForbiddenException.class);
    }

    @Test
    @Tag("clause:E4-1")
    @Tag("clause:E3-3")
    void aWatchCanceledNotAuthorizedSurfacesForbiddenPerWatchAgainstTheRealClient() throws Exception {
        // End-to-end: the mock sends a WATCH_CANCELED carrying NOT_AUTHORIZED (11), the 403-class per-watch
        // reject. The real ConfigdEdgeClient must surface it as a ForbiddenException on the watch's terminal
        // future (do not retry the same target) and, because the carrier is per-watch, must not reconnect --
        // the connection is torn down once, not looped.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            long wid = ((EdgeFrame.WatchCreate) conn.readFrame()).watchId();
            conn.send(new EdgeFrame.WatchCanceled(wid, ErrorCode.NOT_AUTHORIZED, null, "over-broad target"),
                    EdgeFrameCodec.EDGE_WIRE_VERSION_V2);
        })) {
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(trustedConfig(server.port()))) {
                Watch watch = client.watch(WatchTarget.full(), WatchOptions.defaults());
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> watch.terminalFuture().get(10, TimeUnit.SECONDS));
                assertInstanceOf(ForbiddenException.class, ee.getCause(),
                        "NOT_AUTHORIZED on a WATCH_CANCELED ⇒ ForbiddenException (403-class)");
                assertEquals(1, server.connectionCount(), "a per-watch 403 reject does not reconnect");
            }
        }
    }

    // -----------------------------------------------------------------------

    private static ConfigdClientConfig trustedConfig(int port) {
        HostileServerLimits d = HostileServerLimits.defaults();
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .retryPolicy(new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(50), 5))
                // A generous read-idle deadline so the mock's post-cancel silence never trips a reconnect.
                .limits(new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                        30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks()))
                .build();
    }

    private static Reaction classify(ErrorCode code, Carrier carrier) {
        return ErrorClassifier.classify(code, carrier, "server diagnostic");
    }

    private static void assertFatal(Reaction reaction, Class<? extends ConfigdException> expected) {
        Reaction.Fatal fatal = assertInstanceOf(Reaction.Fatal.class, reaction,
                "expected a connection-fatal reaction");
        assertInstanceOf(expected, fatal.exception(), "the exception type IS the reaction");
    }

    private static void assertPerWatch(Reaction reaction, Class<? extends ConfigdException> expected) {
        Reaction.PerWatch perWatch = assertInstanceOf(Reaction.PerWatch.class, reaction,
                "expected a per-watch reaction (the connection survives)");
        assertInstanceOf(expected, perWatch.exception(), "the exception type IS the reaction");
    }
}
