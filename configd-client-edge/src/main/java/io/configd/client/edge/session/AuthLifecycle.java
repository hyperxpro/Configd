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
 * The four auth modes as the client presents them, and the proactive-refresh timers.
 *
 * <ul>
 *   <li><b>mTLS</b>: the client certificate authenticates at the TLS handshake — <b>no</b> {@code AUTH}
 *       frame is sent. A cert cannot refresh in-band, so a lead-time <b>reconnect</b> is armed before the
 *       certificate's {@code notAfter}.</li>
 *   <li><b>token / basic</b>: send <b>exactly one</b> pre-auth {@code AUTH} frame (0x04) as the first routed
 *       frame; there is no AUTH-OK on the wire, so success is optimistic-present (a rejection
 *       surfaces asynchronously as a framed {@code ERROR_CLOSE(AUTH_FAIL)}). A proactive {@code REFRESH_AUTH}
 *       is scheduled in the lead-time window {@code W = clamp(0.20·lifetime, 30 s, 5 m)} before expiry,
 *       carrying a freshly-minted credential that renews the <b>same</b> identity.</li>
 *   <li><b>no-auth</b>: present nothing, but stay ready.</li>
 * </ul>
 *
 * <p>A driver <b>MUST NOT</b> hot-loop {@code AUTH} on one connection: this class sends the single pre-auth
 * frame and never retries it in-connection; a rejection costs a fresh connection (owned by the client).
 */
public final class AuthLifecycle {

    /** Token refresh lead-time bounds: W = clamp(0.20·lifetime, 30 s, 5 m). */
    private static final long TOKEN_LEAD_MIN_MS = 30_000L;
    private static final long TOKEN_LEAD_MAX_MS = 300_000L;
    private static final double TOKEN_LEAD_FRACTION = 0.20;

    /** Certificate reconnect lead-time bounds: clamp(0.10·lifetime, 5 m, 1 h). */
    private static final long CERT_LEAD_MIN_MS = 300_000L;
    private static final long CERT_LEAD_MAX_MS = 3_600_000L;
    private static final double CERT_LEAD_FRACTION = 0.10;

    /** A floor on any scheduled refresh delay, so a misconfigured near-expiry token cannot busy-loop. */
    private static final long MIN_SCHEDULE_DELAY_MS = 200L;

    private final AuthMode mode;
    private final CredentialSource credentialSource; // null for MTLS / NO_AUTH
    private final ClientTls tls;                     // for the cert notAfter (MTLS)
    private final ScheduledExecutorService scheduler;
    private final Runnable proactiveReconnect;       // invoked at the cert lead-time (MTLS)

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

    /**
     * Presents the credential on {@code conn} per the mode and arms the proactive timer. For mTLS/no-auth this
     * only marks the connection authenticated (the handshake already did the work). For a token/basic edge it
     * writes the single {@code AUTH} frame and schedules the lead-time {@code REFRESH_AUTH}.
     *
     * @throws IOException if the {@code AUTH} frame cannot be written
     */
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
                conn.markAuthenticated(); // optimistic-present: the wire carries no AUTH-OK
                scheduleTokenRefresh(provided.expiresAt());
            }
        }
    }

    /**
     * Sends a {@code REFRESH_AUTH} now with a freshly-minted credential (token/basic only) and re-arms the
     * lead-time timer from the new expiry. A refresh renews the same identity. A no-op off the
     * token path.
     *
     * @throws IOException if the frame cannot be written
     */
    public void refreshNow() throws IOException {
        if (mode != AuthMode.TOKEN) {
            return;
        }
        EdgeConnection conn = connection;
        if (conn == null || conn.state() != EdgeConnectionState.AUTHENTICATED) {
            return; // nothing live to refresh
        }
        CredentialSource.Provided provided = credentialSource.provide();
        conn.send(new EdgeFrame.RefreshAuth(provided.credential()), EdgeFrameCodec.EDGE_WIRE_VERSION_V4);
        scheduleTokenRefresh(provided.expiresAt());
    }

    /** Cancels any pending proactive timer (on close or reconnect). */
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
            return; // no known expiry — rely on the server's CREDENTIAL_EXPIRED close to trigger a reconnect
        }
        long delay = leadTimeDelayMs(expiresAt.get(),
                TOKEN_LEAD_FRACTION, TOKEN_LEAD_MIN_MS, TOKEN_LEAD_MAX_MS);
        scheduled = scheduler.schedule(this::onRefreshTick, delay, TimeUnit.MILLISECONDS);
    }

    private void onRefreshTick() {
        try {
            refreshNow();
        } catch (IOException io) {
            // The connection died before we could refresh; the reader/terminal path already surfaced it and a
            // reconnect (if the terminal warrants one) will re-authenticate. Nothing to do here but stop.
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
            return; // notAfter enforcement is off — nothing to pre-empt
        }
        long delay = leadTimeDelayMs(notAfter.get(), CERT_LEAD_FRACTION, CERT_LEAD_MIN_MS, CERT_LEAD_MAX_MS);
        scheduled = scheduler.schedule(proactiveReconnect, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * The delay until the lead-time window before {@code deadline}: {@code deadline − clamp(fraction·lifetime,
     * min, max)}, floored at {@link #MIN_SCHEDULE_DELAY_MS}. A deadline already inside the window schedules at
     * the floor rather than immediately, so a misconfigured near-expiry credential cannot busy-loop.
     */
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
