package io.configd.distribution.fanout;

import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.store.ConfigSnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Watch revocation (W7-7) — re-authorize on ACL policy-version change")
class WatchRevocationTest {

    private static final class FakeAuthorizer implements WatchAuthorizer {
        volatile long version = 0L;
        Predicate<WatchTarget> allow = t -> true;
        int authorizeCalls = 0;

        @Override
        public boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target) {
            authorizeCalls++;
            return allow.test(target);
        }

        @Override
        public long policyVersion() {
            return version;
        }
    }

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink out = new RecordingTransportSink();
    private final List<ErrorCode> teardowns = new ArrayList<>();
    private FanOutConnectionDriver driver;

    private FanOutConnectionDriver newDriver(WatchAuthorizer authz) {
        FanOutBuffer buffer = new FanOutBuffer(64); // empty store, so from-now watches TAIL
        ReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        return new FanOutConnectionDriver(buffer, replay, out, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock, gov, "edge-cert-dn", (c, m) -> teardowns.add(c), authz);
    }

    private void createWatch(long watchId, String path) {
        driver.onInboundFrame(new EdgeFrame.WatchCreate(watchId, 0, EdgeFrame.WATCH_TARGET_KEY,
                path.getBytes(StandardCharsets.UTF_8), WatchCursor.fromNow(), 0));
        driver.drainInboundCommands();
    }

    @Test
    @DisplayName("8 — a reload that revokes the grant force-closes the watch with NOT_AUTHORIZED")
    void revocationForceClosesWatch() {
        FakeAuthorizer authz = new FakeAuthorizer();
        driver = newDriver(authz);
        createWatch(1L, "/a");
        assertEquals(1, out.sentOfType(EdgeFrame.WatchCreated.class).size(), "watch authorized + created");
        out.clear();

        authz.allow = t -> false;
        authz.version = 5L;
        driver.maybeReauthorizeWatches(); // the session-loop re-auth step (bounded latency, about one tick)

        List<EdgeFrame.WatchCanceled> cancels = out.sentOfType(EdgeFrame.WatchCanceled.class);
        assertEquals(1, cancels.size(), "the revoked watch is force-closed");
        assertEquals(1L, cancels.get(0).watchId());
        assertEquals(ErrorCode.NOT_AUTHORIZED, cancels.get(0).code(), "revocation surfaces as NOT_AUTHORIZED");
        assertTrue(teardowns.isEmpty(), "the connection survives (only the watch is closed)");
    }

    @Test
    @DisplayName("9 — a reload that revokes an UNRELATED principal does NOT close this watch")
    void unrelatedReloadDoesNotCloseStillAuthorizedWatch() {
        FakeAuthorizer authz = new FakeAuthorizer();
        driver = newDriver(authz);
        createWatch(1L, "/a");
        out.clear();

        authz.version = 7L; // allow predicate unchanged (still true)
        driver.maybeReauthorizeWatches();

        assertTrue(out.sentOfType(EdgeFrame.WatchCanceled.class).isEmpty(),
                "a still-authorized watch is not closed by an unrelated reload");
    }

    @Test
    @DisplayName("zero cost — no re-authorization when the policy version is unchanged")
    void noPolicyChangeMeansNoReauth() {
        FakeAuthorizer authz = new FakeAuthorizer();
        driver = newDriver(authz);
        createWatch(1L, "/a");
        int callsAfterCreate = authz.authorizeCalls; // the single authorize call from the create

        driver.maybeReauthorizeWatches();
        driver.maybeReauthorizeWatches();
        driver.maybeReauthorizeWatches();

        assertEquals(callsAfterCreate, authz.authorizeCalls,
                "the policy version is unchanged ⇒ authorizeWatch is NOT re-invoked (W7-7 zero cost)");
        assertTrue(out.sentOfType(EdgeFrame.WatchCanceled.class).isEmpty());
    }

    @Test
    @DisplayName("multiplex — a reload closes only the revoked watches, leaving the rest live")
    void onlyRevokedWatchesCloseOnReauth() {
        FakeAuthorizer authz = new FakeAuthorizer();
        driver = newDriver(authz);
        createWatch(1L, "/a");
        createWatch(2L, "/b");
        out.clear();

        authz.allow = t -> t.path().equals("/a");
        authz.version = 3L;
        driver.maybeReauthorizeWatches();

        List<EdgeFrame.WatchCanceled> cancels = out.sentOfType(EdgeFrame.WatchCanceled.class);
        assertEquals(1, cancels.size(), "only the revoked watch closes");
        assertEquals(2L, cancels.get(0).watchId(), "watch /b (id 2) is the one revoked");
        assertEquals(ErrorCode.NOT_AUTHORIZED, cancels.get(0).code());
    }

    @Test
    @DisplayName("seed TOCTOU — a revoking reload racing the create is caught on the first re-auth")
    void revocationRacingTheCreateIsCaughtOnFirstReauth() {
        // Models an _acl/ reload whose revocation commits on the apply thread during the
        // authorize-to-seed window of the create: the create authorizes against the
        // pre-revoke snapshot, then the version advances and the verdict flips to deny.
        // Because the seed is read before the authorize gate, it is behind the
        // post-create version, so the first re-auth detects the change and force-closes.
        // This is the TOCTOU guard: if the seed were read after authorize, it would bake
        // in the post-revoke version and never re-check.
        WatchAuthorizer racingReload = new WatchAuthorizer() {
            long version = 10L;
            boolean denyNow = false;

            @Override
            public boolean authorizeWatch(String p, Set<String> r, WatchTarget t) {
                boolean verdict = !denyNow;
                version = 20L;   // the revoking reload lands right after this create-time authz decision
                denyNow = true;
                return verdict;  // the create is authorized against the pre-revoke snapshot
            }

            @Override
            public long policyVersion() {
                return version;
            }
        };
        driver = newDriver(racingReload);
        createWatch(1L, "/a"); // authorized at v10; the reload to v20 with deny lands during the create
        out.clear();

        driver.maybeReauthorizeWatches(); // policyVersion() is 20, different from the seed (10), so it re-authorizes, is now denied, and closes

        List<EdgeFrame.WatchCanceled> cancels = out.sentOfType(EdgeFrame.WatchCanceled.class);
        assertEquals(1, cancels.size(),
                "a revocation racing the create is caught on the first re-auth (seed-before-authorize fix)");
        assertEquals(ErrorCode.NOT_AUTHORIZED, cancels.get(0).code());
    }

    @Test
    @DisplayName("a no-policy authorizer (default policyVersion 0) never triggers re-auth")
    void defaultPolicyVersionNeverReauths() {
        // A lambda authorizer uses the SPI default policyVersion() of 0, so the version
        // never advances.
        WatchAuthorizer constant = (p, r, t) -> true;
        assertEquals(0L, constant.policyVersion(), "the SPI default policy version is the constant 0");
        driver = newDriver(constant);
        createWatch(1L, "/a");
        driver.maybeReauthorizeWatches();
        assertTrue(out.sentOfType(EdgeFrame.WatchCanceled.class).isEmpty(),
                "a constant-version authorizer never re-authorizes (Gate-1 lambdas unaffected)");
    }
}
