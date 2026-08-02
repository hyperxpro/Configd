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


final class EdgeAuthGateHandler extends ChannelInboundHandlerAdapter {

    
    private static final String TOKEN_EXPIRED_MESSAGE = "token credential expired";

    
    private static final int MAX_PENDING_PREAUTH_FRAMES = 8;

    private final EdgeAuthConfig auth;
    private final EdgeCertGate certGate;
    private final RegistryFanOutSessionMetrics metrics;
    private final Clock clock;
    private final int firstFrameDeadlineMs;
    
    private final Executor authWorker;

    
    private ScheduledFuture<?> preAuthDeadline;
    
    private ScheduledFuture<?> expiry;
    
    private boolean authenticated;
    
    private boolean resolving;
    
    private List<EdgeFrame> pendingPreAuthFrames;

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
            // The gate owns the handshake outcome on a token edge: it is not forwarded, so the
            // downstream FanOutConnection starts only from the EdgeAuthenticated event we fire.
            if (handshake.isSuccess()) {
                List<X509Certificate> chain = verifiedPeerChain(ctx);
                if (chain != null && auth.mtlsConfigured()) {
                    authenticateCertificate(ctx, chain);
                } else {
                    // Certificate-less, or a cert on a token-only edge (mtls not in the chain): do not
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
        // Mid-connection cert expiry: NO_EXPIRY when enforcement is off, otherwise a close at
        // notAfter + leeway. A cert cannot refresh in-band, so the CREDENTIAL_EXPIRED close is a
        // reconnect signal - the client re-handshakes with its rotated cert.
        long certDeadline = certGate.certCloseDeadlineMillis(chain);
        install(ctx, a.principal(), certDeadline,
                certDeadline == AuthState.NO_EXPIRY ? null : EdgeCertGate.CERT_EXPIRED_MESSAGE);
    }

    
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

    private void onUnauthenticatedFrame(ChannelHandlerContext ctx, EdgeFrame frame) {
        if (resolving) {
            if (frame instanceof EdgeFrame.Auth) {
                // A second AUTH frame while the first is still being verified off the event loop: a genuine
                // double pre-auth attempt. Fail closed - one credential resolution per handshake (a retry
                // costs a fresh handshake), so neither a stray nor a hostile second AUTH amplifies PBKDF2.
                closePreAuth(ctx, ErrorCode.PROTOCOL_VIOLATION,
                        "a second AUTH frame arrived while the first was being verified");
                return;
            }
            // A business frame pipelined right behind the AUTH (the driver does not wait for an ack before
            // sending its SUBSCRIBE - there is no AUTH-OK frame). Hold it until authentication resolves, then
            // replay it into the started session - rejecting it as a pre-auth violation would break the
            // legitimate AUTH-then-SUBSCRIBE pipelining the driver relies on.
            bufferPendingFrame(ctx, frame);
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
                    // could not be verified - distinct from a bad credential (a down IdP locking out
                    // legitimate clients). The wire close stays AUTH_FAIL (the on-wire error taxonomy is
                    // pinned by conformance tests); only the server metric reason distinguishes so an
                    // operator can alert on IdP health.
                    closePreAuthReason(ctx, ErrorCode.AUTH_FAIL, "AUTH_UNAVAILABLE",
                            "authentication temporarily unavailable");
                } else {
                    closePreAuth(ctx, ErrorCode.AUTH_FAIL, "authentication failed");
                }
            }, () -> closePreAuthReason(ctx, ErrorCode.AUTH_FAIL, "AUTH_UNAVAILABLE",
                    "authentication temporarily unavailable (server busy)"));
        } else {
            closePreAuth(ctx, ErrorCode.PROTOCOL_VIOLATION,
                    "expected an AUTH frame before authenticating, got " + frame.type());
        }
    }

    
    private void bufferPendingFrame(ChannelHandlerContext ctx, EdgeFrame frame) {
        if (pendingPreAuthFrames != null && pendingPreAuthFrames.size() >= MAX_PENDING_PREAUTH_FRAMES) {
            closePreAuth(ctx, ErrorCode.PROTOCOL_VIOLATION,
                    "too many frames pipelined before authentication completed");
            return;
        }
        if (pendingPreAuthFrames == null) {
            pendingPreAuthFrames = new ArrayList<>(4);
        }
        pendingPreAuthFrames.add(frame);
    }

    
    private void replayPendingFrames(ChannelHandlerContext ctx) {
        if (pendingPreAuthFrames == null) {
            return;
        }
        List<EdgeFrame> pending = pendingPreAuthFrames;
        pendingPreAuthFrames = null;
        for (EdgeFrame f : pending) {
            channelRead(ctx, f);
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
                            // A refresh renews the same identity's token; a different identity on an
                            // established connection is anomalous - fail closed rather than silently extend
                            // (the driver's identity is fixed at first authentication, so extending would
                            // desync the bound id from the presented credential).
                            closePostAuth(ctx, ErrorCode.AUTH_FAIL,
                                    "refresh credential resolves to a different identity than the "
                                            + "connection is bound to");
                            return;
                        }
                        // Re-arm the session lifetime; the identity is not re-bound on a refresh.
                        ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE)
                                .set(AuthState.authenticated(a.principal(),
                                        auth.tokenCloseDeadlineMillis(a, clock.currentTimeMillis())));
                        armExpiry(ctx, TOKEN_EXPIRED_MESSAGE);
                    } else {
                        closePostAuth(ctx, ErrorCode.CREDENTIAL_EXPIRED, "credential refresh rejected");
                    }
                }, () -> {
                    // Overloaded (the worker queue is saturated): drop this refresh rather than close. The
                    // session stays valid until its current expiry, so a shed refresh under load is not fatal
                    // - the client re-sends REFRESH_AUTH before the deadline.
                });
            }
            case EdgeFrame.Auth ignored -> closePostAuth(ctx, ErrorCode.PROTOCOL_VIOLATION,
                    "AUTH received on an already-authenticated connection");
            default -> ctx.fireChannelRead(frame);
        }
    }

    
    private void resolveOffLoop(ChannelHandlerContext ctx, Credential credential,
                               Consumer<AuthResult> resume, Runnable onOverload) {
        if (authWorker == null) {
            resume.accept(safeResolve(credential)); // safety net: no pool => inline (we are on the EL)
            return;
        }
        resolving = true;
        try {
            authWorker.execute(() -> {
                if (!ctx.channel().isActive()) {
                    // The connection died while this task waited in the worker queue - the signature of a
                    // connect -> AUTH -> RST churn flood. Skip the (deliberately expensive) PBKDF2 for a
                    // connection that is already gone, so queued verifications for dead connections can never
                    // become the drain bottleneck; just clear the guard back on the event loop.
                    credential.wipeSecret();
                    ctx.executor().execute(() -> resolving = false);
                    return;
                }
                AuthResult result = safeResolve(credential);
                ctx.executor().execute(() -> {
                    resolving = false;
                    if (ctx.channel().isActive()) {
                        resume.accept(result);
                    }
                });
            });
        } catch (RejectedExecutionException rejected) {
            // The bounded worker queue is saturated (a credential-verification flood) or the pool is shutting
            // down. Do not fall back to resolving inline on the event loop - that is exactly the stall this
            // off-load exists to prevent, and a flood would re-stall the shared worker. Fail closed and let
            // the caller decide (a pre-auth connection closes; an authenticated refresh is dropped, its
            // session still valid).
            resolving = false;
            credential.wipeSecret();
            onOverload.run();
        }
    }

    
    private AuthResult safeResolve(Credential credential) {
        try {
            return auth.resolveFrameCredential(credential);
        } catch (Throwable t) {
            return new AuthResult.Unavailable("edge credential verification error");
        } finally {
            credential.wipeSecret(); // zero any Basic password char[] once verification is done
        }
    }

    
    private void install(ChannelHandlerContext ctx, Principal principal, long expiresAtMillis,
                         String expiryReason) {
        cancelPreAuthDeadline();
        ctx.channel().attr(ByteToEdgeFrameDecoder.AUTH_STATE)
                .set(AuthState.authenticated(principal, expiresAtMillis));
        authenticated = true;
        armExpiry(ctx, expiryReason);
        ctx.fireUserEventTriggered(new EdgeAuthenticated(principal));
        // Any frames the client pipelined behind its AUTH (its SUBSCRIBE) now flow into the started session,
        // in arrival order - the connection behaves as it did when resolution was synchronous.
        replayPendingFrames(ctx);
    }

    
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
        pendingPreAuthFrames = null; // drop any un-replayed pipelined frames (the connection is gone)
    }

    
    private void closePreAuth(ChannelHandlerContext ctx, ErrorCode code, String message) {
        closePreAuthReason(ctx, code, code.name(), message);
    }

    
    private void closePreAuthReason(ChannelHandlerContext ctx, ErrorCode code, String metricReason,
                                    String message) {
        metrics.onSessionClosed(metricReason);
        close(ctx, code, message);
    }

    
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
