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
import java.util.concurrent.TimeUnit;

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

    private final EdgeAuthConfig auth;
    private final RegistryFanOutSessionMetrics metrics;
    private final Clock clock;
    private final int firstFrameDeadlineMs;

    /** The pre-auth first-frame reaper (armed once the connection awaits an AUTH frame). */
    private ScheduledFuture<?> preAuthDeadline;
    /** The token-TTL expiry one-shot (armed on token auth, re-armed on REFRESH_AUTH). */
    private ScheduledFuture<?> expiry;
    /** Event-loop-only: whether an {@link EdgeAuthenticated} has been fired (post-auth teardown routing). */
    private boolean authenticated;

    EdgeAuthGateHandler(EdgeAuthConfig auth, RegistryFanOutSessionMetrics metrics, Clock clock) {
        this.auth = auth;
        this.metrics = metrics;
        this.clock = clock;
        this.firstFrameDeadlineMs = FanOutServer.firstFrameDeadlineMs();
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
                if (chain != null) {
                    authenticateCertificate(ctx, chain);
                } else {
                    armPreAuthDeadline(ctx); // certificate-less token client: await its AUTH frame
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
        EdgeFrameCodec.CodecException ce = asCodecException(cause);
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

    /** Unwraps a {@link EdgeFrameCodec.CodecException} from the cause chain (Netty wraps it in a DecoderException). */
    private static EdgeFrameCodec.CodecException asCodecException(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof EdgeFrameCodec.CodecException ce) {
                return ce;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // handshake certificate path (byte-identical identity to the pre-token edge)
    // -----------------------------------------------------------------------

    private void authenticateCertificate(ChannelHandlerContext ctx, List<X509Certificate> chain) {
        AuthResult result = auth.authenticateClientCertificate(chain);
        if (result instanceof AuthResult.Authenticated a) {
            install(ctx, a.principal(), AuthState.NO_EXPIRY); // no active expiry for the cert path
        } else {
            closePreAuth(ctx, ErrorCode.AUTH_FAIL, "edge client certificate rejected");
        }
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
        cancelPreAuthDeadline(); // the first routed frame has arrived; the pre-auth budget is spent
        if (frame instanceof EdgeFrame.Auth authFrame) {
            Credential credential = authFrame.credential();
            if (!auth.credentialWithinCaps(credential)) {
                closePreAuth(ctx, ErrorCode.AUTH_FAIL, "auth credential exceeds the permitted size");
                return;
            }
            AuthResult result = auth.resolveFrameCredential(credential);
            if (result instanceof AuthResult.Authenticated a) {
                install(ctx, a.principal(), clock.currentTimeMillis() + auth.tokenTtlMs());
                armExpiry(ctx);
            } else {
                closePreAuth(ctx, ErrorCode.AUTH_FAIL, "authentication failed");
            }
        } else {
            closePreAuth(ctx, ErrorCode.PROTOCOL_VIOLATION,
                    "expected an AUTH frame before authenticating, got " + frame.type());
        }
    }

    private void onAuthenticatedFrame(ChannelHandlerContext ctx, EdgeFrame frame) {
        switch (frame) {
            case EdgeFrame.RefreshAuth refresh -> {
                Credential credential = refresh.credential();
                if (!auth.credentialWithinCaps(credential)) {
                    closePostAuth(ctx, ErrorCode.CREDENTIAL_EXPIRED,
                            "refresh credential exceeds the permitted size");
                    return;
                }
                AuthResult result = auth.resolveFrameCredential(credential);
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
                                    clock.currentTimeMillis() + auth.tokenTtlMs()));
                    armExpiry(ctx);
                } else {
                    closePostAuth(ctx, ErrorCode.CREDENTIAL_EXPIRED, "credential refresh rejected");
                }
            }
            case EdgeFrame.Auth ignored -> closePostAuth(ctx, ErrorCode.PROTOCOL_VIOLATION,
                    "AUTH received on an already-authenticated connection");
            default -> ctx.fireChannelRead(frame); // business frame -> session
        }
    }

    /** Installs the authenticated state and fires the session-start event exactly once. */
    private void install(ChannelHandlerContext ctx, Principal principal, long expiresAtMillis) {
        cancelPreAuthDeadline();
        ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE)
                .set(AuthState.authenticated(principal, expiresAtMillis));
        authenticated = true;
        ctx.fireUserEventTriggered(new EdgeAuthenticated(principal));
    }

    // -----------------------------------------------------------------------
    // expiry + pre-auth deadline (event-loop scheduled)
    // -----------------------------------------------------------------------

    private void armExpiry(ChannelHandlerContext ctx) {
        cancelExpiry();
        expiry = ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                closePostAuth(ctx, ErrorCode.CREDENTIAL_EXPIRED, "token credential expired");
            }
        }, auth.tokenTtlMs(), TimeUnit.MILLISECONDS);
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
        metrics.onSessionClosed(code.name());
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
