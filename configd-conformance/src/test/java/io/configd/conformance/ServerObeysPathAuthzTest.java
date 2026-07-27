package io.configd.conformance;

import io.configd.api.AclService;
import io.configd.api.AclService.Permission;
import io.configd.client.BadSubscribeException;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.ConfigdException;
import io.configd.client.CredentialSource;
import io.configd.client.ForbiddenException;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.client.edge.ConfigdEdgeClient;
import io.configd.client.edge.Watch;
import io.configd.client.edge.WatchEvent;
import io.configd.client.edge.WatchOptions;
import io.configd.client.edge.WatchTarget;
import io.configd.common.Clock;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.config.ConfigSource;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.fanout.WatchAuthorizer;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.observability.MetricsRegistry;
import io.configd.server.fanout.AclServiceWatchAuthorizer;
import io.configd.server.fanout.EdgeAuthConfig;
import io.configd.server.fanout.EdgeCertGate;
import io.configd.server.fanout.FanOutServer;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Server-obeys conformance for the §01 paths / access-control SERVER halves: the live {@link FanOutServer} watch
 * surface enforcing the path grammar (A3-1..A3-3 seg-char, A3-4 canonicalization), the ordering contract
 * (A4-2 per-shard-only), the capability-relationship + watch-authorization contract (A5-2 WATCH implies READ,
 * A6-1..A6-5, A9-3), and the fail-closed reject of an unrecognized scope ordinal (A9-4).
 *
 * <p>The grammar / ordering / scope cases drive a live server with a permissive authorizer (so the path/scope
 * check is the gate under test). The authorization cases wire the production
 * {@link AclServiceWatchAuthorizer} over a real {@link AclService} with scenario-specific grants -- so the watch
 * gate decides byte-identically to the HTTP admin plane -- and each denial asserts the RFC's hard
 * requirement: a terminal reject with the correct {@link ErrorCode} and zero data frames emitted first.
 * A6-5's two mandatory negatives (over-broad target; non-root {@code full_chain_verify}) are proven explicitly.
 */
@Timeout(180)
@Tag("clause:A9-3")
class ServerObeysPathAuthzTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final String TOKEN = "s3cr3t-watch-token";
    private static final String PRINCIPAL = "edge-watch-svc"; // the bearer principal edgeIdentity resolves to
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};
    /** Admits every watch (so the PATH-grammar / scope check is the gate under test, not authorization). */
    private static final WatchAuthorizer PERMISSIVE = (principal, roles, target) -> true;

    private FanOutServer server;
    private FanOutBuffer buffer;
    private final AtomicReference<ConfigSnapshot> replayState = new AtomicReference<>(ConfigSnapshot.EMPTY);

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @Tag("clause:A3-4")
    void serverIndependentlyEnforcesKeyCanonicalityDefenseInDepth() throws Exception {
        // A3-4, server side (defense in depth). The shared client grammar (PathGrammar) rejects seg-char, `//`,
        // and `.`/`..` spellings at WatchTarget construction, so a conforming client cannot put them on the wire
        // (proven client-side in ClausePathGrammarTest). PathGrammar tolerates one trailing slash generically
        // (the `/a/` subtree form), so `WatchTarget.key("/a/b/")` still constructs and reaches the wire -- but a
        // concrete key must be canonical (no trailing slash), and the live server enforces that independently,
        // rejecting it BAD_SUBSCRIBE with zero data frames. This proves the server does not rely on client
        // validation for key canonicality -- the one non-canonical spelling that still traverses a conforming
        // client. (Full seg-char / `//` / `.`/`..` server-side enforcement would need a hostile raw-frame client
        // to bypass PathGrammar; the WatchTargetValidator control still exists for that case.)
        FanOutServer srv = newServer(PERMISSIVE);
        try {
            assertRejectedOn(srv, WatchTarget.key("/a/b/"), ErrorCode.BAD_SUBSCRIBE, BadSubscribeException.class);
            // The canonical concrete key is accepted (positive control).
            assertCreatedOn(srv, WatchTarget.key("/a/b"));
        } finally {
            srv.close();
        }
    }

    @Test
    @Tag("clause:A4-2")
    void watchOrderingIsPerShardOnlyNeverAssumedCrossShard() throws Exception {
        // A4-2: the only ordering a driver may rely on is per-key and per-shard (a shard's results ordered by
        // its applied-mutation sequence S). With a single static shard (gid 0), cross-shard order is dormant
        // and must not be assumed. This proves the live server delivers two commits per-shard-ordered (ascending
        // S) and advances the per-shard cursor vector -- the client's only ordering handle.
        FanOutServer srv = newServer(PERMISSIVE);
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(clientConfig(srv.localPort()))) {
            Watch w = client.watch(WatchTarget.full(), WatchOptions.defaults());
            List<WatchEvent> events = subscribe(w);
            w.awaitCreated(Duration.ofSeconds(20));
            publish(1, "/app/a", "1");
            publish(2, "/app/b", "2");
            await("two events tailed from the real server", () -> countChanges(events) >= 2);
            await("per-shard cursor advanced to (0,2)", () -> componentS(w.cursor(), 0) == 2L);
            assertTrue(events.size() >= 2);
            assertEquals(0, events.get(0).gid(), "the sole shard is gid 0 (A4-2 per-shard)");
            assertTrue(WatchEvent.ordered(events.get(0), events.get(events.size() - 1)),
                    "events on one shard are ordered by ascending S (A4-2 per-shard)");
        } finally {
            srv.close();
        }
    }

    @Test
    @Tag("clause:A5-2")
    @Tag("clause:A6-1")
    void watchRequiresBothReadAndWatchNotEitherAlone() throws Exception {
        // A5-2: WATCH requires READ (effective WATCH = WATCH and READ) -- a watch is a streaming read and must
        // never expose what a read could not. We drive the production AclServiceWatchAuthorizer over a real AclService.

        // WATCH granted but READ withheld: the floor removes effective WATCH, so the result is deny.
        AclService watchOnly = new AclService();
        watchOnly.grant("/app/", PRINCIPAL, Set.of(Permission.WATCH));
        assertRejected(new AclServiceWatchAuthorizer(watchOnly), WatchTarget.key("/app/x"),
                ErrorCode.NOT_AUTHORIZED, ForbiddenException.class);

        // READ granted but WATCH withheld: WATCH is not in the effective set, so the result is deny (WATCH is
        // its own grant).
        AclService readOnly = new AclService();
        readOnly.grant("/app/", PRINCIPAL, Set.of(Permission.READ));
        assertRejected(new AclServiceWatchAuthorizer(readOnly), WatchTarget.key("/app/x"),
                ErrorCode.NOT_AUTHORIZED, ForbiddenException.class);

        // Both READ and WATCH granted: authorized (positive control -- the floor is satisfied).
        AclService both = new AclService();
        both.grant("/app/", PRINCIPAL, Set.of(Permission.READ, Permission.WATCH));
        assertCreated(new AclServiceWatchAuthorizer(both), WatchTarget.key("/app/x"));
    }


    @Test
    @Tag("clause:A6-2")
    @Tag("clause:A6-1")
    void overBroadTargetIsRejectedNotSilentlyNarrowed() throws Exception {
        // A6-2: a target extending beyond the principal's authorized region MUST be rejected, never narrowed to
        // the authorized subset (silent narrowing would give a false-completeness view). Grant covers only the
        // /app/public/ subtree; a watch on the broader /app/ has no ancestor-or-equal ALLOW covering it, so the
        // result is deny, with zero data frames (not a narrowed stream of /app/public/).
        AclService acl = new AclService();
        acl.grant("/app/public/", PRINCIPAL, Set.of(Permission.READ, Permission.WATCH));
        AclServiceWatchAuthorizer authorizer = new AclServiceWatchAuthorizer(acl);
        assertRejected(authorizer, WatchTarget.prefix("/app/"), ErrorCode.NOT_AUTHORIZED, ForbiddenException.class);
        // The exactly-covered subtree is authorized (positive control -- proves the deny above is about breadth,
        // not a blanket refusal).
        assertCreated(authorizer, WatchTarget.prefix("/app/public/"));
    }

    @Test
    @Tag("clause:A6-3")
    @Tag("clause:A6-1")
    void fullChainVerifyAndFullTargetsRequireRootScope() throws Exception {
        // A6-3: a full_chain_verify watch, or a FULL (whole-store) target, streams the entire signed chain with
        // no edge filtering, so it requires READ and WATCH over the root. A principal holding only a /app/ subtree
        // grant is rejected for both a FULL target and a full_chain_verify prefix -- the latter is the sharp case:
        // the /app/ grant DOES cover the plain /app/ prefix, yet full_chain_verify escalates the requirement to
        // root, so it is still denied (it must not receive other subtrees' data under local-verification cover).
        AclService subtreeOnly = new AclService();
        subtreeOnly.grant("/app/", PRINCIPAL, Set.of(Permission.READ, Permission.WATCH));
        AclServiceWatchAuthorizer subtreeAuthorizer = new AclServiceWatchAuthorizer(subtreeOnly);
        assertRejected(subtreeAuthorizer, WatchTarget.full(), ErrorCode.NOT_AUTHORIZED, ForbiddenException.class);
        assertRejected(subtreeAuthorizer,
                WatchTarget.prefix("/app/").with(WatchTarget.Flag.FULL_CHAIN_VERIFY),
                ErrorCode.NOT_AUTHORIZED, ForbiddenException.class);

        // A root-scope grant authorizes a FULL watch (positive control).
        AclService rootGrant = new AclService();
        rootGrant.grant("", PRINCIPAL, Set.of(Permission.READ, Permission.WATCH));
        assertCreated(new AclServiceWatchAuthorizer(rootGrant), WatchTarget.full());
    }

    @Test
    @Tag("clause:A6-4_INV-WATCH-READ")
    @Tag("clause:A6-1")
    void interiorReadDenySinksTheWholeSubtreeWatch() throws Exception {
        // A6-4 (INV-WATCH-READ): for EVERY key a subtree watch could deliver, if READ would be denied the watch
        // MUST be denied. A READ DENY on an interior descendant (/app/secret/) carves a hole inside the /app/
        // subtree, so a PREFIX watch on /app/ -- which could deliver /app/secret/* -- must be rejected as a whole
        // (the whole-target cover-check's interior-DENY term), never partially streamed.
        AclService acl = new AclService();
        acl.grant("/app/", PRINCIPAL, Set.of(Permission.READ, Permission.WATCH));
        acl.deny("/app/secret/", PRINCIPAL, Set.of(Permission.READ));
        AclServiceWatchAuthorizer authorizer = new AclServiceWatchAuthorizer(acl);
        assertRejected(authorizer, WatchTarget.prefix("/app/"), ErrorCode.NOT_AUTHORIZED, ForbiddenException.class);
        // A concrete key OUTSIDE the carved hole is still watchable (the deny is scoped to /app/secret/, proving
        // the reject above is the interior hole, not a blanket refusal of /app/).
        assertCreated(authorizer, WatchTarget.key("/app/public/x"));
    }

    @Test
    @Tag("clause:A6-5")
    @Tag("clause:A6-1")
    void unauthorizedSubscriptionIsTerminal403ClassWithZeroDataFrames() throws Exception {
        // A6-5 (REQUIRED regression): an unauthorized subscription MUST be terminated with a 403-class ErrorCode
        // (authorization), distinct from 401-class (authentication), with no data frame emitted first -- and the
        // spec names two cases a conforming implementation MUST prove: (a) an over-broad target, and (b) a
        // non-root full_chain_verify/FULL watch. Both are asserted here to end in a terminal NOT_AUTHORIZED (11,
        // a ForbiddenException -- 403-class, not AUTH_FAIL/401) with zero SNAPSHOT_*/WATCH_EVENT/WATCH_PROGRESS
        // frames preceding the reject.

        // (a) over-broad target: grant covers only /app/public/, watch requests the broader /app/.
        AclService overBroad = new AclService();
        overBroad.grant("/app/public/", PRINCIPAL, Set.of(Permission.READ, Permission.WATCH));
        assertUnauthorizedZeroFrames(new AclServiceWatchAuthorizer(overBroad), WatchTarget.prefix("/app/"));

        // (b) non-root full_chain_verify: grant covers only the /app/ subtree, watch sets full_chain_verify.
        AclService subtreeOnly = new AclService();
        subtreeOnly.grant("/app/", PRINCIPAL, Set.of(Permission.READ, Permission.WATCH));
        assertUnauthorizedZeroFrames(new AclServiceWatchAuthorizer(subtreeOnly),
                WatchTarget.prefix("/app/").with(WatchTarget.Flag.FULL_CHAIN_VERIFY));
    }

    @Test
    @Tag("clause:A9-4")
    void serverFailsClosedOnAnUnrecognizedScopeOrdinal() throws Exception {
        // A9-4 / A1.3 (fail closed on the unrecognized), SERVER half: an out-of-range scope ordinal is the one
        // unrecognized identifier a conforming client can still put on the wire (the WatchTarget u8 field admits
        // 0..255; the server recognizes only GLOBAL/REGIONAL/LOCAL = 0..2). The server must fail closed --
        // BAD_SUBSCRIBE, zero data frames -- rather than route it to an assumed default. (The closed-enum client
        // half -- a driver cannot even express an unknown capability/flag/kind -- is asserted in
        // ClausePathGrammarTest under the same tag; the unknown-WATCH-flag-bit fail-closed is W5-4a/W1-3.)
        WatchTarget unknownScope = new WatchTarget(9 /* not GLOBAL/REGIONAL/LOCAL */, WatchTarget.Kind.KEY,
                "/app/x", EnumSet.noneOf(WatchTarget.Flag.class));
        assertRejected(PERMISSIVE, unknownScope, ErrorCode.BAD_SUBSCRIBE, BadSubscribeException.class);
    }


    /** Starts a fresh live server (plaintext transport + bearer AUTH) with the given watch authorizer. */
    private FanOutServer newServer(WatchAuthorizer authorizer) throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        FanOutBuffer buf = new FanOutBuffer(10_000);
        this.buffer = buf;
        this.replayState.set(ConfigSnapshot.EMPTY);
        SlowConsumerGovernor governor = new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics);
        FanOutServer srv = new FanOutServer(
                Map.of(0, buf),
                Map.of(0, new SnapshotReplaySource(replayState::get)),
                new int[]{0}, SINGLE_SHARD, WatchCursor.INITIAL_TOPOLOGY_EPOCH,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, FanOutConfig.defaults(),
                FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                governor, metrics, Clock.system(), authorizer,
                new EdgeAuthConfig(bearerChain(), 16_384, 8_192, Duration.ofHours(1).toMillis()),
                EdgeCertGate.OFF);
        srv.start();
        return srv;
    }

    /** As {@link #assertRejectedOn} but spins up (and tears down) a fresh server with {@code authorizer}. */
    private void assertRejected(WatchAuthorizer authorizer, WatchTarget target,
                                ErrorCode expectedCode, Class<? extends ConfigdException> expectedType)
            throws Exception {
        FanOutServer srv = newServer(authorizer);
        try {
            assertRejectedOn(srv, target, expectedCode, expectedType);
        } finally {
            srv.close();
        }
    }

    /**
     * Opens {@code target} as a dedicated watch against the running {@code srv} and asserts it is terminated with
     * {@code expectedCode} (surfaced as {@code expectedType}) having emitted ZERO data frames.
     */
    private void assertRejectedOn(FanOutServer srv, WatchTarget target,
                                  ErrorCode expectedCode, Class<? extends ConfigdException> expectedType)
            throws Exception {
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(clientConfig(srv.localPort()))) {
            Watch w = client.watch(target, WatchOptions.defaults());
            List<WatchEvent> events = subscribe(w);
            ConfigdException ex = assertThrows(expectedType, () -> w.awaitCreated(Duration.ofSeconds(20)));
            assertEquals(expectedCode, ex.edgeCode().orElse(null), "terminal edge ErrorCode");
            assertTrue(events.isEmpty(),
                    "no SNAPSHOT_*/WATCH_EVENT/WATCH_PROGRESS frame precedes the terminal reject; got " + events);
        }
    }

    /** The A6-5 shape: a terminal 403-class NOT_AUTHORIZED (distinct from 401 AUTH_FAIL) with zero data frames. */
    private void assertUnauthorizedZeroFrames(WatchAuthorizer authorizer, WatchTarget target) throws Exception {
        FanOutServer srv = newServer(authorizer);
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(clientConfig(srv.localPort()))) {
            Watch w = client.watch(target, WatchOptions.defaults());
            List<WatchEvent> events = subscribe(w);
            ForbiddenException ex = assertThrows(ForbiddenException.class,
                    () -> w.awaitCreated(Duration.ofSeconds(20)), "authorization reject is a ForbiddenException (403-class)");
            assertEquals(ErrorCode.NOT_AUTHORIZED, ex.edgeCode().orElse(null), "the reason code is NOT_AUTHORIZED (11)");
            assertNotEquals(ErrorCode.AUTH_FAIL, ex.edgeCode().orElse(null), "403-class is DISTINCT from 401-class");
            assertTrue(events.isEmpty(),
                    "A6-5: zero data frames precede the terminal NOT_AUTHORIZED reject; got " + events);
        } finally {
            srv.close();
        }
    }

    /** As {@link #assertCreatedOn} but spins up (and tears down) a fresh server with {@code authorizer}. */
    private void assertCreated(WatchAuthorizer authorizer, WatchTarget target) throws Exception {
        FanOutServer srv = newServer(authorizer);
        try {
            assertCreatedOn(srv, target);
        } finally {
            srv.close();
        }
    }

    /** Asserts {@code target} is authorized + created against the running {@code srv} (no terminal reject). */
    private void assertCreatedOn(FanOutServer srv, WatchTarget target) throws Exception {
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(clientConfig(srv.localPort()))) {
            Watch w = client.watch(target, WatchOptions.defaults());
            subscribe(w);
            w.awaitCreated(Duration.ofSeconds(20));
        }
    }

    private ConfigdClientConfig clientConfig(int port) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .credentialSource(CredentialSource.staticBearer(TOKEN)) // watches require a real principal (W7)
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5))
                .limits(longIdle())
                .build();
    }

    private static List<WatchEvent> subscribe(Watch watch) {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        watch.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WatchEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        return events;
    }

    private void publish(long to, String key, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ConfigDelta delta = new ConfigDelta(to - 1, to, List.of(new ConfigMutation.Put(key, bytes)));
        HamtMap<String, VersionedValue> data = replayState.get().data().put(key, new VersionedValue(bytes, to, T0));
        replayState.set(new ConfigSnapshot(data, to, T0));
        buffer.publish(new CommitNotification(to, T0, delta));
    }

    private static int countChanges(List<WatchEvent> events) {
        return events.stream().mapToInt(e -> e.changes().size()).sum();
    }

    private static long componentS(WatchCursor cursor, int gid) {
        return cursor.components().stream().filter(c -> c.gid() == gid)
                .mapToLong(WatchCursor.Component::s).findFirst().orElse(-1L);
    }

    private static AuthenticatorChain bearerChain() {
        return AuthenticatorChain.build(List.of("bearer"), mapConfig(Map.of(
                "configd.auth.bearer.token", TOKEN,
                "configd.auth.bearer.principal", PRINCIPAL)));
    }

    private static ConfigSource mapConfig(Map<String, String> m) {
        return new ConfigSource() {
            @Override
            public Optional<String> getString(String key) {
                return Optional.ofNullable(m.get(key));
            }

            @Override
            public Set<String> keysWithPrefix(String prefix) {
                return m.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
            }
        };
    }

    private static HostileServerLimits longIdle() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("timed out awaiting: " + description);
    }
}
