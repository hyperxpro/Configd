package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.common.auth.AuthResult;
import io.configd.common.auth.Credential;
import io.configd.common.auth.Principal;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.concurrent.ScheduledFuture;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The edge token-authentication gate: a per-connection inbound handler installed AFTER
 * {@link ByteToEdgeFrameDecoder} and BEFORE {@code FanOutConnection}, only when token/basic auth is
 * configured for the edge ({@link EdgeAuthConfig}). It admits exactly one authentication before any
 * business frame reaches the session, and it is the ONLY writer/reader of the per-connection
 * {@link AuthState} channel attribute (all transitions run on the event loop, so no synchronization
 * is needed; the decoder reads the same attribute to size its pre-auth ceiling).
 *
 * <h2>Admission table</h2>
 * <ul>
 *   <li><b>Verified client certificate</b> (the connection ran {@code wantClientAuth} and the peer
 *       presented a trusted cert): authenticated at the handshake via the identity-only
 *       {@code MtlsAuthenticator}. No {@code AUTH} frame, no active expiry (byte-identical identity to
 *       the pre-token edge). An {@link EdgeAuthenticated} event is fired so the session starts.</li>
 *   <li><b>Certificate-less, UNAUTHENTICATED</b>: only an {@code AUTH} frame is admitted. It is
 *       resolved once against the shared chain - {@code Authenticated} installs the token session and
 *       arms the TTL expiry; {@code Denied}/{@code Unavailable} closes {@code AUTH_FAIL} (a single
 *       pre-auth attempt: a rejected {@code AUTH} closes the connection, so a retry costs a fresh
 *       handshake, which bounds the pre-auth verification cost). Any other frame before authentication
 *       is a {@code PROTOCOL_VIOLATION}.</li>
 *   <li><b>AUTHENTICATED</b>: business frames pass through to the session. A {@code REFRESH_AUTH}
 *       re-resolves the credential and re-arms the expiry ({@code CREDENTIAL_EXPIRED} on any
 *       non-acceptance); an {@code AUTH} on an already-authenticated connection is a
 *       {@code PROTOCOL_VIOLATION}.</li>
 * </ul>
 *
 * <p>The identity is not re-bound on {@code REFRESH_AUTH} in v1 - a refresh only extends the session
 * lifetime; the driver's identity is fixed at the first authentication.
 *
 * <p>A pre-auth first-frame deadline (the same {@code configd.edge.firstFrameDeadlineMs} window the
 * post-admission {@code FanOutConnection} uses for the pre-SUBSCRIBE window) reaps a certificate-less
 * peer that completes admission then never sends its {@code AUTH} frame - the analogue of the JDK
 * reader's absolute first-frame budget, so a token connection cannot park a slot as a slow-loris
 * before proving identity.
 */
final class EdgeAuthGateHandler extends ChannelInboundHandlerAdapter {

    /** CREDENTIAL_EXPIRED close reason for a token whose lifetime elapsed (re-authenticate to resume). */
    private static final String TOKEN_EXPIRED_MESSAGE = "token credential expired";

    private final EdgeAuthConfig auth;
    private final EdgeCertGate certGate;
    private final RegistryFanOutSessionMetrics metrics;
    private final Clock clock;
    private final int firstFrameDeadlineMs;
    /**
     * Bounded off-loop pool for the (blocking, ~50-150ms PBKDF2) credential resolution. Never resolve a
     * credential inline on the event loop - that stalls every other connection on this shared Netty
     * worker. Nullable only as a can't-happen safety net (the gate is installed only when token auth is
     * configured, and the server always supplies a pool then); a null pool falls back to inline resolution.
     */
    private final Executor authWorker;

    /** The pre-auth first-frame reaper (armed once the connection awaits an AUTH frame). */
    private ScheduledFuture<?> preAuthDeadline;
    /** The token-TTL expiry one-shot (armed on token auth, re-armed on REFRESH_AUTH). */
    private ScheduledFuture<?> expiry;
    /** Event-loop-only: whether an {@link EdgeAuthenticated} has been fired (post-auth teardown routing). */
    private boolean authenticated;
    /**
     * Event-loop-only single-flight guard: a credential resolution is dispatched to {@link #authWorker}
     * and not yet resumed. Bounds each connection to ONE in-flight PBKDF2 - a second pre-auth AUTH frame
     * fails closed and duplicate REFRESH_AUTH frames are dropped, so neither a stray frame nor
     * REFRESH_AUTH spam can amplify verification work.
     */
    private boolean resolving;

    EdgeAuthGateHandler(EdgeAuthConfig auth, EdgeCertGate certGate,
                        RegistryFanOutSessionMetrics metrics, Clock clock, Executor authWorker) {
        this.auth = auth;
        this.certGate = certGate;
        this.metrics = metrics;
        this.clock = clock;
        this.firstFrameDeadlineMs = FanOutServer.firstFrameDeadlineMs();
        this.authWorker = authWorker;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // Seed the state the decoder reads for its pre-auth ceiling; the gate owns every later transition.
        ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE).set(AuthState.UNAUTHENTICATED);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Plaintext: no handshake event will arrive, so the pre-auth window opens immediately (an AUTH
        // frame is the first thing we expect). A TLS connection instead opens it after the handshake
        // resolves certificate-less (below), mirroring the JDK reader's post-handshake deadline.
        if (ctx.pipeline().get(SslHandler.class) == null) {
            armPreAuthDeadline(ctx);
        }
        ctx.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof SslHandshakeCompletionEvent handshake) {
            // The gate owns the handshake outcome on a token edge: it is NOT forwarded, so the
            // downstream FanOutConnection starts only from the EdgeAuthenticated event we fire.
            if (handshake.isSuccess()) {
                List<X509Certificate> chain = verifiedPeerChain(ctx);
                if (chain != null && auth.mtlsConfigured()) {
                    authenticateCertificate(ctx, chain);
                } else {
                    // Certificate-less, OR a cert on a token-only edge (mtls not in the chain): do NOT
                    // auto-authenticate the presented cert - await the client's AUTH frame instead.
                    armPreAuthDeadline(ctx);
                }
            } else {
                closePreAuth(ctx, ErrorCode.AUTH_FAIL, "edge TLS handshake failed");
            }
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof EdgeFrame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }
        AuthState state = ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE).get();
        if (state != null && state.isAuthenticated()) {
            onAuthenticatedFrame(ctx, frame);
        } else {
            onUnauthenticatedFrame(ctx, frame);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        AuthState state = ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE).get();
        EdgeFrameCodec.CodecException ce = CodecExceptions.unwrap(cause);
        if (ce != null && (state == null || !state.isAuthenticated())) {
            // A pre-auth decode failure (e.g. the decoder's pre-auth frame ceiling, which the decoder
            // wraps in a Netty DecoderException) - close with a clean ERROR_CLOSE bye + the sessions_closed
            // accounting, matching the JDK reader. The downstream FanOutConnection never started a session
            // for this connection, so it would otherwise close it silently; owning it here keeps the
            // pre-auth reject path contract-equivalent.
            closePreAuth(ctx, ce.code(), "pre-auth decode error: " + ce.getMessage());
            return;
        }
        ctx.fireExceptionCaught(cause); // post-auth (or non-codec): FanOutConnection owns teardown
    }

    // -----------------------------------------------------------------------
    // handshake certificate path (byte-identical identity to the pre-token edge)
    // -----------------------------------------------------------------------

    private void authenticateCertificate(ChannelHandlerContext ctx, List<X509Certificate> chain) {
        AuthResult result = auth.authenticateClientCertificate(chain);
        if (!(result instanceof AuthResult.Authenticated a)) {
            closePreAuth(ctx, ErrorCode.AUTH_FAIL, "edge client certificate rejected");
            return;
        }
        if (!certGate.admit(chain)) {
            // Online revocation rejected the edge client cert (revoked, or unreachable-under-strict). This
            // is the edge/client plane only; the Raft interior is never checked (RevocationPolicy invariant).
            closePreAuth(ctx, ErrorCode.AUTH_FAIL, "edge client certificate revoked or unverifiable");
            return;
        }
        // Mid-connection cert-expiry: NO_EXPIRY (enforcement off) is byte-identical to Gate 3; otherwise a
        // close at notAfter + leeway. A cert cannot refresh in-band, so the CREDENTIAL_EXPIRED close is a
        // reconnect signal - the client re-handshakes with its rotated cert.
        long certDeadline = certGate.certCloseDeadlineMillis(chain);
        install(ctx, a.principal(), certDeadline,
                certDeadline == AuthState.NO_EXPIRY ? null : EdgeCertGate.CERT_EXPIRED_MESSAGE);
    }

    /** The verified peer certificate chain, or {@code null} if the peer presented none (certless). */
    private static List<X509Certificate> verifiedPeerChain(ChannelHandlerContext ctx) {
        SslHandler ssl = ctx.pipeline().get(SslHandler.class);
        if (ssl == null) {
            return null;
        }
        try {
            Certificate[] certs = ssl.engine().getSession().getPeerCertificates();
            List<X509Certificate> chain = new ArrayList<>(certs.length);
            for (Certificate c : certs) {
                if (c instanceof X509Certificate x) {
                    chain.add(x);
                }
            }
            return chain.isEmpty() ? null : chain;
        } catch (Exception e) {
            return null; // no verifiable client certificate -> treat as certificate-less
        }
    }

    // -----------------------------------------------------------------------
    // frame admission (event loop)
    // -----------------------------------------------------------------------

    private void onUnauthenticatedFrame(ChannelHandlerContext ctx, EdgeFrame frame) {
        if (resolving) {
            // A credential resolution is already in flight for this connection (the single pre-auth
            // attempt is being verified off the event loop). A second pre-auth frame before it completes
            // is anomalous and would dispatch a second PBKDF2 - fail closed, preserving the
            // one-attempt-per-handshake bound.
            closePreAuth(ctx, ErrorCode.PROTOCOL_VIOLATION,
                    "a pre-auth frame arrived while authentication was in progress");
            return;
        }
        cancelPreAuthDeadline(); // the first routed frame has arrived; the pre-auth budget is spent
        if (frame instanceof EdgeFrame.Auth authFrame) {
            Credential credential = authFrame.credential();
            if (!auth.credentialWithinCaps(credential)) {
                closePreAuth(ctx, ErrorCode.AUTH_FAIL, "auth credential exceeds the permitted size");
                return;
            }
            resolveOffLoop(ctx, credential, result -> {
                if (result instanceof AuthResult.Authenticated a) {
                    install(ctx, a.principal(),
                            auth.tokenCloseDeadlineMillis(a, clock.currentTimeMillis()), TOKEN_EXPIRED_MESSAGE);
                } else if (result instanceof AuthResult.Unavailable) {
                    // The authenticator's backend (OIDC JWKS, ...) was unreachable, so the credential
                    // could not be VERIFIED - distinct from a bad credential (a down IdP locking out
                    // legitimate clients). The wire close stays AUTH_FAIL (golden-pinned taxonomy); only
                    // the server metric reason distinguishes so an operator can alert on IdP health.
                    closePreAuthReason(ctx, ErrorCode.AUTH_FAIL, "AUTH_UNAVAILABLE",
                            "authentication temporarily unavailable");
                } else {
                    closePreAuth(ctx, ErrorCode.AUTH_FAIL, "authentication failed");
                }
            });
        } else {
            closePreAuth(ctx, ErrorCode.PROTOCOL_VIOLATION,
                    "expected an AUTH frame before authenticating, got " + frame.type());
        }
    }

    private void onAuthenticatedFrame(ChannelHandlerContext ctx, EdgeFrame frame) {
        switch (frame) {
            case EdgeFrame.RefreshAuth refresh -> {
                if (resolving) {
                    // A refresh is already being verified off the event loop; drop this duplicate rather
                    // than dispatch a second PBKDF2 (REFRESH_AUTH-spam amplification). The in-flight
                    // refresh still completes; the session stays valid until its current expiry.
                    return;
                }
                Credential credential = refresh.credential();
                if (!auth.credentialWithinCaps(credential)) {
                    closePostAuth(ctx, ErrorCode.CREDENTIAL_EXPIRED,
                            "refresh credential exceeds the permitted size");
                    return;
                }
                resolveOffLoop(ctx, credential, result -> {
                    if (result instanceof AuthResult.Authenticated a) {
                        AuthState current = ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE).get();
                        if (current instanceof AuthState.Authenticated bound
                                && !bound.principal().id().equals(a.principal().id())) {
                            // A refresh renews the SAME identity's token; a different identity on an
                            // established connection is anomalous - fail closed rather than silently extend
                            // (the driver's identity is fixed at first authentication, so extending would
                            // desync the bound id from the presented credential).
                            closePostAuth(ctx, ErrorCode.AUTH_FAIL,
                                    "refresh credential resolves to a different identity than the "
                                            + "connection is bound to");
                            return;
                        }
                        // Re-arm the session lifetime; the identity is NOT re-bound in v1.
                        ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE)
                                .set(AuthState.authenticated(a.principal(),
                                        auth.tokenCloseDeadlineMillis(a, clock.currentTimeMillis())));
                        armExpiry(ctx, TOKEN_EXPIRED_MESSAGE);
                    } else {
                        closePostAuth(ctx, ErrorCode.CREDENTIAL_EXPIRED, "credential refresh rejected");
                    }
                });
            }
            case EdgeFrame.Auth ignored -> closePostAuth(ctx, ErrorCode.PROTOCOL_VIOLATION,
                    "AUTH received on an already-authenticated connection");
            default -> ctx.fireChannelRead(frame); // business frame -> session
        }
    }

    /**
     * Resolves a credential OFF the event loop on the bounded {@link #authWorker}, then resumes
     * {@code resume} back ON the event loop with the outcome. Basic verification is a deliberately
     * expensive PBKDF2 (~50-150ms); running it inline would stall every other connection sharing this
     * Netty worker. Sets the {@link #resolving} single-flight guard for the dispatch window so a second
     * credential frame cannot spawn a concurrent PBKDF2 on the same connection. All state transitions
     * in {@code resume} run on the event loop.
     */
    private void resolveOffLoop(ChannelHandlerContext ctx, Credential credential,
                               Consumer<AuthResult> resume) {
        if (authWorker == null) {
            resume.accept(safeResolve(credential)); // safety net: no pool => inline (we are on the EL)
            return;
        }
        resolving = true;
        try {
            authWorker.execute(() -> {
                AuthResult result = safeResolve(credential);
                ctx.executor().execute(() -> {
                    resolving = false;
                    if (ctx.channel().isActive()) {
                        resume.accept(result);
                    }
                });
            });
        } catch (RejectedExecutionException rejected) {
            // The pool is shutting down (server close) or saturated beyond its queue: resolve inline on
            // the event loop rather than leave the connection hung. Rare; not the hot path.
            resolving = false;
            resume.accept(safeResolve(credential));
        }
    }

    /** Resolves a credential, mapping any verifier fault to a retryable {@code Unavailable} (never a hang). */
    private AuthResult safeResolve(Credential credential) {
        try {
            return auth.resolveFrameCredential(credential);
        } catch (Throwable t) {
            return new AuthResult.Unavailable("edge credential verification error");
        } finally {
            credential.wipeSecret(); // zero any Basic password char[] once verification is done
        }
    }

    /**
     * Installs the authenticated state, arms the expiry one-shot (unless {@code expiresAtMillis} is
     * {@link AuthState#NO_EXPIRY}), and fires the session-start event exactly once.
     *
     * @param expiryReason the CREDENTIAL_EXPIRED close reason to use if the expiry fires (ignored when
     *                     {@code expiresAtMillis == NO_EXPIRY})
     */
    private void install(ChannelHandlerContext ctx, Principal principal, long expiresAtMillis,
                         String expiryReason) {
        cancelPreAuthDeadline();
        ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE)
                .set(AuthState.authenticated(principal, expiresAtMillis));
        authenticated = true;
        armExpiry(ctx, expiryReason);
        ctx.fireUserEventTriggered(new EdgeAuthenticated(principal));
    }

    // -----------------------------------------------------------------------
    // expiry + pre-auth deadline (event-loop scheduled)
    // -----------------------------------------------------------------------

    /**
     * Arms (or re-arms) the credential-expiry one-shot from the connection's {@link AuthState} close
     * deadline. A {@link AuthState#NO_EXPIRY} deadline (a cert connection with {@code enforceCertNotAfter}
     * off) arms nothing - byte-identical to Gate 3. The delay is {@code max(0, deadline - now)}, so a
     * clock already past the deadline fires promptly rather than scheduling a negative delay.
     */
    private void armExpiry(ChannelHandlerContext ctx, String reason) {
        cancelExpiry();
        AuthState state = ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE).get();
        if (!(state instanceof AuthState.Authenticated a) || a.expiresAtMillis() == AuthState.NO_EXPIRY) {
            return;
        }
        long delay = Math.max(0L, a.expiresAtMillis() - clock.currentTimeMillis());
        expiry = ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                closePostAuth(ctx, ErrorCode.CREDENTIAL_EXPIRED, reason);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelExpiry() {
        if (expiry != null) {
            expiry.cancel(false);
            expiry = null;
        }
    }

    private void armPreAuthDeadline(ChannelHandlerContext ctx) {
        if (preAuthDeadline != null) {
            return;
        }
        preAuthDeadline = ctx.executor().schedule(() -> {
            metrics.onFirstFrameTimeout();
            closePreAuth(ctx, ErrorCode.PROTOCOL_VIOLATION, "pre-auth first-frame deadline elapsed");
        }, firstFrameDeadlineMs, TimeUnit.MILLISECONDS);
    }

    private void cancelPreAuthDeadline() {
        if (preAuthDeadline != null) {
            preAuthDeadline.cancel(false);
            preAuthDeadline = null;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelPreAuthDeadline();
        cancelExpiry();
    }

    // -----------------------------------------------------------------------
    // close helpers
    // -----------------------------------------------------------------------

    /**
     * Closes a connection that never authenticated. The downstream {@code FanOutConnection} never
     * started a session for it, so this path owns the {@code sessions_closed} accounting (the
     * subscriber-connected gauge was never incremented, so nothing decrements it).
     */
    private void closePreAuth(ChannelHandlerContext ctx, ErrorCode code, String message) {
        closePreAuthReason(ctx, code, code.name(), message);
    }

    /**
     * Pre-auth close with an explicit {@code metricReason} decoupled from the on-wire {@code code}, so
     * an {@code Unavailable} (IdP/JWKS outage) can land in its own {@code AUTH_UNAVAILABLE} series while
     * the wire still closes with the golden-pinned {@code AUTH_FAIL}.
     */
    private void closePreAuthReason(ChannelHandlerContext ctx, ErrorCode code, String metricReason,
                                    String message) {
        metrics.onSessionClosed(metricReason);
        close(ctx, code, message);
    }

    /**
     * Closes an authenticated connection (expiry / refresh reject / misuse). The session is running,
     * so {@code FanOutConnection}'s channel-inactive teardown owns the accounting; this only writes
     * the terminal frame and closes.
     */
    private void closePostAuth(ChannelHandlerContext ctx, ErrorCode code, String message) {
        close(ctx, code, message);
    }

    private void close(ChannelHandlerContext ctx, ErrorCode code, String message) {
        cancelPreAuthDeadline();
        cancelExpiry();
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(new EdgeFrame.ErrorClose(code, message))
                    .addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.close();
        }
    }
}
