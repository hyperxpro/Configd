package io.configd.client.edge.session;

import io.configd.client.CredentialSource;
import io.configd.client.edge.AuthMode;
import io.configd.client.tls.ClientTls;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Auth lifecycle and proactive-refresh timers. mTLS: cert authenticates at handshake, proactive reconnect
 * before notAfter. Token/basic: single pre-auth AUTH frame, proactive REFRESH_AUTH at lead-time
 * (0.20·lifetime, clamped 30s-5m). No hot-loop: rejection costs a fresh connection.
 */
public final class AuthLifecycle {

    private static final long TOKEN_LEAD_MIN_MS = 30_000L;
    private static final long TOKEN_LEAD_MAX_MS = 300_000L;
    private static final double TOKEN_LEAD_FRACTION = 0.20;

    private static final long CERT_LEAD_MIN_MS = 300_000L;
    private static final long CERT_LEAD_MAX_MS = 3_600_000L;
    private static final double CERT_LEAD_FRACTION = 0.10;

    private static final long MIN_SCHEDULE_DELAY_MS = 200L;

    private final AuthMode mode;
    private final CredentialSource credentialSource;
    private final ClientTls tls;
    private final ScheduledExecutorService scheduler;
    private final Runnable proactiveReconnect;

    private volatile EdgeConnection connection;
    private volatile ScheduledFuture<?> scheduled;

    public AuthLifecycle(AuthMode mode, CredentialSource credentialSource, ClientTls tls,
                         ScheduledExecutorService scheduler, Runnable proactiveReconnect) {
        this.mode = mode;
        this.credentialSource = credentialSource;
        this.tls = tls;
        this.scheduler = scheduler;
        this.proactiveReconnect = proactiveReconnect;
    }

    public void authenticate(EdgeConnection conn) throws IOException {
        this.connection = conn;
        switch (mode) {
            case MTLS -> {
                conn.markAuthenticated();
                scheduleCertReconnect();
            }
            case NO_AUTH -> conn.markAuthenticated();
            case TOKEN -> {
                CredentialSource.Provided provided = credentialSource.provide();
                conn.send(new EdgeFrame.Auth(provided.credential()), EdgeFrameCodec.EDGE_WIRE_VERSION_V4);
                conn.markAuthenticated();
                scheduleTokenRefresh(provided.expiresAt());
            }
        }
    }
    public void refreshNow() throws IOException {
        if (mode != AuthMode.TOKEN) {
            return;
        }
        EdgeConnection conn = connection;
        if (conn == null || conn.state() != EdgeConnectionState.AUTHENTICATED) {
            return;
        }
        CredentialSource.Provided provided = credentialSource.provide();
        conn.send(new EdgeFrame.RefreshAuth(provided.credential()), EdgeFrameCodec.EDGE_WIRE_VERSION_V4);
        scheduleTokenRefresh(provided.expiresAt());
    }

    public void cancel() {
        ScheduledFuture<?> s = scheduled;
        if (s != null) {
            s.cancel(false);
            scheduled = null;
        }
    }

    private void scheduleTokenRefresh(Optional<Instant> expiresAt) {
        cancel();
        if (expiresAt.isEmpty()) {
            return;
        }
        long delay = leadTimeDelayMs(expiresAt.get(),
                TOKEN_LEAD_FRACTION, TOKEN_LEAD_MIN_MS, TOKEN_LEAD_MAX_MS);
        scheduled = scheduler.schedule(this::onRefreshTick, delay, TimeUnit.MILLISECONDS);
    }

    private void onRefreshTick() {
        try {
            refreshNow();
        } catch (IOException io) {
            cancel();
        }
    }

    private void scheduleCertReconnect() {
        cancel();
        if (proactiveReconnect == null) {
            return;
        }
        Optional<Instant> notAfter = tls == null ? Optional.empty() : tls.clientCertNotAfter();
        if (notAfter.isEmpty()) {
            return;
        }
        long delay = leadTimeDelayMs(notAfter.get(), CERT_LEAD_FRACTION, CERT_LEAD_MIN_MS, CERT_LEAD_MAX_MS);
        scheduled = scheduler.schedule(proactiveReconnect, delay, TimeUnit.MILLISECONDS);
    }

    /** Delay until lead-time window before deadline: deadline − clamp(fraction·lifetime, min, max), floored. */
    private static long leadTimeDelayMs(Instant deadline, double fraction, long minMs, long maxMs) {
        long now = System.currentTimeMillis();
        long lifetimeMs = Math.max(0L, deadline.toEpochMilli() - now);
        long lead = clamp((long) (fraction * lifetimeMs), minMs, maxMs);
        long delay = (deadline.toEpochMilli() - lead) - now;
        return Math.max(MIN_SCHEDULE_DELAY_MS, delay);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
