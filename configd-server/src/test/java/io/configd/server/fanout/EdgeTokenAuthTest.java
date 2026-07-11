package io.configd.server.fanout;

import io.configd.api.AclService;
import io.configd.api.Policy;
import io.configd.api.PolicyRule;
import io.configd.api.Role;
import io.configd.common.Clock;
import io.configd.common.auth.Authenticator;
import io.configd.common.auth.AuthResult;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.Credential;
import io.configd.common.auth.DenyReason;
import io.configd.common.auth.Principal;
import io.configd.common.config.ConfigSource;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.fanout.WatchAuthorizer;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The token-authentication contract for the edge fan-out endpoint - proven on both transports (the
 * JDK {@link FanOutServer} inline reader gate and the Netty {@link NettyFanOutServer}
 * {@link EdgeAuthGateHandler}) by varying only which server {@link #startTokenServer} constructs. Over
 * a plaintext token endpoint (a certificate-less connection must present an {@code AUTH} frame), it
 * exercises the admission table, the authenticate {@code ->} authorize {@code ->} subscribe
 * {@code ->} receive path against the same in-core {@link AclService} the admin plane uses, and the
 * token-TTL expiry. The mTLS-on-a-token-edge byte-identity (a cert client authenticates at the
 * handshake with no {@code AUTH} frame) is the sibling {@code EdgeTokenAuthMtlsTest}.
 */
@Timeout(120)
class EdgeTokenAuthTest {

    private static final String TOKEN = "s3cr3t-edge-token";
    private static final String PRINCIPAL = "edge-token-svc";
    private static final long LONG_TTL_MS = 3_600_000L;

    /** Single-shard resolver: every target covers gid 0 (the N=1 shape). */
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};

    private FanOutEndpoint server;
    private FanOutBuffer buffer;
    private final AtomicReference<ConfigSnapshot> replayState =
            new AtomicReference<>(ConfigSnapshot.EMPTY);
    private long seq;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private void bearerAuthThenSubscribeReceives(boolean netty) throws Exception {
        AclService acl = new AclService();
        acl.grant("", PRINCIPAL, EnumSet.of(AclService.Permission.READ)); // whole-store READ for the token id
        int port = startTokenServer(netty, new AclServiceWatchAuthorizer(acl), LONG_TTL_MS);

        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.authenticateBearer(TOKEN);
            edge.subscribeFullStore("wire-claimed-id", 0L); // the wire id is advisory; the token binds identity
            EdgeFrame.SubscribeOk ok = (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertEquals(EdgeFrame.Mode.TAIL, ok.mode(), "empty buffer at subscribe -> TAIL");

            publish("svc/a", "v-a");
            long s = expectVerbatimNotify(edge, "svc/a", "v-a");
            assertTrue(s > 0, "the authenticated + authorized token session must receive the NOTIFY");
        }
    }

    @Test
    void jdkBearerAuthThenSubscribeReceives() throws Exception {
        bearerAuthThenSubscribeReceives(false);
    }

    @Test
    void nettyBearerAuthThenSubscribeReceives() throws Exception {
        bearerAuthThenSubscribeReceives(true);
    }

    private void subscribeBeforeAuthIsRejected(boolean netty) throws Exception {
        int port = startTokenServer(netty, null, LONG_TTL_MS); // authorizer irrelevant: the gate closes first
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 8_000)) {
            edge.subscribeFullStore("no-auth", 0L); // a business frame before AUTH
            assertNull(readSubscribeOkOrNull(edge),
                    "a SUBSCRIBE before AUTH must never be acknowledged");
            assertTrue(drainUntilClosed(edge), "the connection must close");
        }
    }

    @Test
    void jdkSubscribeBeforeAuthIsRejected() throws Exception {
        subscribeBeforeAuthIsRejected(false);
    }

    @Test
    void nettySubscribeBeforeAuthIsRejected() throws Exception {
        subscribeBeforeAuthIsRejected(true);
    }

    private void badTokenIsRejectedAuthFail(boolean netty) throws Exception {
        int port = startTokenServer(netty, null, LONG_TTL_MS);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 8_000)) {
            edge.authenticateBearer("not-the-token");
            EdgeFrame.ErrorClose close = (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.AUTH_FAIL, close.code(), "a rejected token closes AUTH_FAIL");
            assertTrue(drainUntilClosed(edge), "the rejected connection must close (single attempt)");
        }
    }

    @Test
    void jdkBadTokenIsRejectedAuthFail() throws Exception {
        badTokenIsRejectedAuthFail(false);
    }

    @Test
    void nettyBadTokenIsRejectedAuthFail() throws Exception {
        badTokenIsRejectedAuthFail(true);
    }

    private void refreshBeforeAuthIsRejected(boolean netty) throws Exception {
        int port = startTokenServer(netty, null, LONG_TTL_MS);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 8_000)) {
            edge.refreshBearer(TOKEN); // REFRESH_AUTH before ever authenticating
            EdgeFrame.ErrorClose close = (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.PROTOCOL_VIOLATION, close.code(),
                    "REFRESH_AUTH before AUTH is a protocol violation");
            assertTrue(drainUntilClosed(edge), "the connection must close");
        }
    }

    @Test
    void jdkRefreshBeforeAuthIsRejected() throws Exception {
        refreshBeforeAuthIsRejected(false);
    }

    @Test
    void nettyRefreshBeforeAuthIsRejected() throws Exception {
        refreshBeforeAuthIsRejected(true);
    }

    private void unauthorizedPrincipalDeniedNotAuthorized(boolean netty) throws Exception {
        // The token authenticates (valid token -> PRINCIPAL), but the ACL grants PRINCIPAL nothing, so
        // the whole-store SUBSCRIBE is refused NOT_AUTHORIZED - authentication and authorization are
        // distinct gates, and the authenticated identity is what authorization evaluates.
        int port = startTokenServer(netty, new AclServiceWatchAuthorizer(new AclService()), LONG_TTL_MS);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 8_000)) {
            edge.authenticateBearer(TOKEN);
            edge.subscribeFullStore("x", 0L);
            EdgeFrame.ErrorClose close = (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.NOT_AUTHORIZED, close.code(),
                    "an authenticated but ungranted token id is refused NOT_AUTHORIZED");
        }
    }

    @Test
    void jdkUnauthorizedPrincipalDeniedNotAuthorized() throws Exception {
        unauthorizedPrincipalDeniedNotAuthorized(false);
    }

    @Test
    void nettyUnauthorizedPrincipalDeniedNotAuthorized() throws Exception {
        unauthorizedPrincipalDeniedNotAuthorized(true);
    }

    private void preAuthOversizeFrameIsRejected(boolean netty) throws Exception {
        int port = startTokenServer(netty, null, LONG_TTL_MS);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 8_000)) {
            // A 4-byte length prefix declaring 131072 bytes (> the 16 KiB pre-auth ceiling), sent BEFORE
            // any AUTH: the decoder rejects it on the DECLARED length before allocating or waiting for the
            // body - a hostile peer cannot force even a mid-size allocation pre-auth. Both transports emit
            // a clean ERROR_CLOSE(FRAME_TOO_LARGE) bye (the Netty gate owns the pre-auth decode-failure
            // path via exceptionCaught, matching the JDK reader).
            edge.sendRaw(new byte[]{0x00, 0x02, 0x00, 0x00, /* version */ 0x01, /* type */ 0x01});
            EdgeFrame.ErrorClose close = (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.FRAME_TOO_LARGE, close.code(),
                    "an oversize pre-auth frame is rejected FRAME_TOO_LARGE before allocation");
            assertTrue(drainUntilClosed(edge), "the connection must close");
        }
    }

    @Test
    void jdkPreAuthOversizeFrameIsRejected() throws Exception {
        preAuthOversizeFrameIsRejected(false);
    }

    @Test
    void nettyPreAuthOversizeFrameIsRejected() throws Exception {
        preAuthOversizeFrameIsRejected(true);
    }

    private void expiryClosesWithCredentialExpired(boolean netty) throws Exception {
        // A short TTL, and NO subscribe (an unsubscribed session emits no frames), so the expiry's
        // terminal ERROR_CLOSE cannot race a heartbeat write - the CREDENTIAL_EXPIRED is clean.
        int port = startTokenServer(netty, null, 400L);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 8_000)) {
            edge.authenticateBearer(TOKEN);
            EdgeFrame.ErrorClose close = (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.CREDENTIAL_EXPIRED, close.code(),
                    "the armed token TTL must fire a CREDENTIAL_EXPIRED close");
            assertTrue(drainUntilClosed(edge), "the expired connection must close");
        }
    }

    @Test
    void jdkExpiryClosesWithCredentialExpired() throws Exception {
        expiryClosesWithCredentialExpired(false);
    }

    @Test
    void nettyExpiryClosesWithCredentialExpired() throws Exception {
        expiryClosesWithCredentialExpired(true);
    }

    private void roleBasedGrantAuthorizesEdge(boolean netty) throws Exception {
        // The token's subject id has NO direct grant; a whole-store READ grant exists only on the role
        // the token asserts. If the edge honored only the id, this would be NOT_AUTHORIZED; honoring
        // the authenticator-asserted roles (as the HTTP plane does) authorizes it.
        AclService acl = new AclService();
        acl.defineRole(new Role("edge-reader", List.of(new Policy("p",
                List.of(new PolicyRule("", Set.of(AclService.Permission.READ), Set.of()))))));
        int port = startTokenServer(netty, bearerChain("svc-role-only", "edge-reader"),
                new AclServiceWatchAuthorizer(acl), LONG_TTL_MS);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.authenticateBearer(TOKEN);
            edge.subscribeFullStore("wire-id", 0L);
            readUntil(edge, EdgeFrame.SubscribeOk.class);
            publish("role/k", "v");
            assertTrue(expectVerbatimNotify(edge, "role/k", "v") > 0,
                    "a token authorized ONLY by its asserted role must receive the feed");
        }
    }

    @Test
    void jdkRoleBasedGrantAuthorizesEdge() throws Exception {
        roleBasedGrantAuthorizesEdge(false);
    }

    @Test
    void nettyRoleBasedGrantAuthorizesEdge() throws Exception {
        roleBasedGrantAuthorizesEdge(true);
    }

    private void refreshSamePrincipalExtends(boolean netty) throws Exception {
        int port = startTokenServer(netty, twoPrincipalChain(), null, LONG_TTL_MS);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.authenticateBearer("tok-alice");
            edge.refreshBearer("tok-alice"); // SAME identity -> accepted, session extended
            edge.subscribeFullStore("x", 0L);
            assertNotNull(readUntil(edge, EdgeFrame.SubscribeOk.class),
                    "a same-identity refresh keeps the connection authenticated and usable");
        }
    }

    @Test
    void jdkRefreshSamePrincipalExtends() throws Exception {
        refreshSamePrincipalExtends(false);
    }

    @Test
    void nettyRefreshSamePrincipalExtends() throws Exception {
        refreshSamePrincipalExtends(true);
    }

    private void refreshDifferentPrincipalIsClosed(boolean netty) throws Exception {
        int port = startTokenServer(netty, twoPrincipalChain(), null, LONG_TTL_MS);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.authenticateBearer("tok-alice");
            edge.refreshBearer("tok-bob"); // a DIFFERENT identity on an established connection
            EdgeFrame.ErrorClose close = (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.AUTH_FAIL, close.code(),
                    "a refresh that resolves to a different identity must fail closed");
            assertTrue(drainUntilClosed(edge), "the connection must close");
        }
    }

    @Test
    void jdkRefreshDifferentPrincipalIsClosed() throws Exception {
        refreshDifferentPrincipalIsClosed(false);
    }

    @Test
    void nettyRefreshDifferentPrincipalIsClosed() throws Exception {
        refreshDifferentPrincipalIsClosed(true);
    }

    /** A single-bearer chain: the valid {@link #TOKEN} authenticates as {@code principal} with {@code roles}. */
    private static AuthenticatorChain bearerChain(String principal, String rolesCsv) {
        Map<String, String> m = new HashMap<>();
        m.put("configd.auth.bearer.token", TOKEN);
        m.put("configd.auth.bearer.principal", principal);
        if (!rolesCsv.isEmpty()) {
            m.put("configd.auth.bearer.roles", rolesCsv);
        }
        ConfigSource cfg = new ConfigSource() {
            @Override public Optional<String> getString(String key) {
                return Optional.ofNullable(m.get(key));
            }
            @Override public Set<String> keysWithPrefix(String prefix) {
                return m.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
            }
        };
        return AuthenticatorChain.build(List.of("bearer"), cfg);
    }

    /** A test chain mapping two distinct tokens to two distinct principals (for the REFRESH-identity tests). */
    private static AuthenticatorChain twoPrincipalChain() {
        Authenticator a = new Authenticator() {
            @Override public String type() {
                return "test-bearer";
            }
            @Override public boolean canAttempt(Credential c) {
                return c instanceof Credential.BearerToken;
            }
            @Override public AuthResult authenticate(Credential c) {
                String tok = ((Credential.BearerToken) c).token();
                return switch (tok) {
                    case "tok-alice" -> new AuthResult.Authenticated(new Principal("alice", Set.of(), "test"));
                    case "tok-bob" -> new AuthResult.Authenticated(new Principal("bob", Set.of(), "test"));
                    default -> new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL, "unknown token");
                };
            }
        };
        return new AuthenticatorChain(List.of(a));
    }

    private int startTokenServer(boolean netty, WatchAuthorizer authorizer, long ttlMs) throws IOException {
        return startTokenServer(netty, bearerChain(PRINCIPAL, ""), authorizer, ttlMs);
    }

    private int startTokenServer(boolean netty, AuthenticatorChain chain, WatchAuthorizer authorizer,
                                 long ttlMs) throws IOException {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        buffer = new FanOutBuffer(10_000);
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics);
        EdgeAuthConfig edgeAuth = new EdgeAuthConfig(chain, 16_384, 8_192, ttlMs);
        InetSocketAddress bind = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        Map<Integer, CommitNotificationSource> sources = Map.of(0, buffer);
        Map<Integer, ReplaySource> replays = Map.of(0, new SnapshotReplaySource(replayState::get));
        FanOutConfig config = FanOutConfig.defaults();
        Clock clock = Clock.system();
        server = netty
                ? new NettyFanOutServer(sources, replays, new int[]{0}, SINGLE_SHARD,
                        WatchCursor.INITIAL_TOPOLOGY_EPOCH, bind, null, config,
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                        governor, metrics, clock, authorizer, edgeAuth, EdgeCertGate.OFF)
                : new FanOutServer(sources, replays, new int[]{0}, SINGLE_SHARD,
                        WatchCursor.INITIAL_TOPOLOGY_EPOCH, bind, null, config,
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                        governor, metrics, clock, authorizer, edgeAuth, EdgeCertGate.OFF);
        server.start();
        return server.localPort();
    }

    /** Publishes one committed mutation (the buffer notification + the replay snapshot). */
    private void publish(String key, String value) {
        long s = ++seq;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ConfigDelta delta = new ConfigDelta(s - 1, s, List.of(new ConfigMutation.Put(key, bytes)));
        ConfigSnapshot current = replayState.get();
        HamtMap<String, VersionedValue> data = current.data().put(key, new VersionedValue(bytes, s, 0L));
        replayState.set(new ConfigSnapshot(data, s, 0L));
        buffer.publish(new CommitNotification(s, 0L, delta));
    }

    private static EdgeFrame readUntil(EdgeProtocolClient edge, Class<? extends EdgeFrame> type)
            throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed while waiting for " + type.getSimpleName());
            }
            if (type.isInstance(f)) {
                return f;
            }
        }
        fail("did not receive a " + type.getSimpleName() + " within the deadline");
        return null;
    }

    /** Reads a bounded number of frames looking for SUBSCRIBE_OK; null on EOF / close / decode error. */
    private static EdgeFrame readSubscribeOkOrNull(EdgeProtocolClient edge) throws IOException {
        for (int i = 0; i < 4; i++) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                return null;
            } catch (EdgeFrameCodec.CodecException e) {
                return null;
            }
            if (f == null) {
                return null;
            }
            if (f instanceof EdgeFrame.SubscribeOk) {
                return f;
            }
        }
        return null;
    }

    private static long expectVerbatimNotify(EdgeProtocolClient edge, String key, String value)
            throws IOException {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed while waiting for a NOTIFY of " + key);
            }
            if (f instanceof EdgeFrame.Notify n) {
                for (CommitNotification cn : n.notifications()) {
                    for (ConfigMutation m : cn.delta().mutations()) {
                        if (m instanceof ConfigMutation.Put put && put.key().equals(key)) {
                            assertArrayEquals(expected, put.value(), "verbatim NOTIFY value for " + key);
                            return cn.seq();
                        }
                    }
                }
            }
        }
        fail("did not receive a NOTIFY for " + key);
        return -1;
    }

    private static boolean drainUntilClosed(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (edge.readFrame() == null) {
                    return true;
                }
            } catch (java.net.SocketTimeoutException e) {
                // keep polling
            } catch (IOException | EdgeFrameCodec.CodecException e) {
                return true;
            }
        }
        return false;
    }
}
