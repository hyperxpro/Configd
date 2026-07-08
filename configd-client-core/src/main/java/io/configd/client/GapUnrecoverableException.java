package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * The replay source no longer holds the requested range: {@link ErrorCode#GAP_UNRECOVERABLE} (6) — the resume
 * cursor is older than the server's retained history. Carrier-dependent scope: per-watch
 * ({@code WATCH_CANCELED}, siblings survive) on the {@code 0x02} plane, connection-fatal ({@code ERROR_CLOSE})
 * on the legacy plane (§02 W6-4).
 *
 * <p><b>§07 reaction:</b> <b>re-bootstrap from a snapshot</b> ({@code with_initial_snapshot}) — the affected
 * watch, or the whole connection on the legacy plane — and <b>do not</b> keep retrying the same cursor.
 * Distinct from {@link StaleTopologyException} (which drops the cursor entirely). Defined here in Gate 1 for
 * a complete classifier; the resume path that raises it is Gate 2/3.
 */
public final class GapUnrecoverableException extends ConfigdException {

    public GapUnrecoverableException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }

    /**
     * A client-detected chain gap or truncated / mismatched hydration snapshot: the local state cannot be
     * continued from what arrived, so the client re-bootstraps from a fresh snapshot rather than applying a
     * partial state. Same §07 reaction (re-bootstrap), no server {@link ErrorCode}.
     */
    public GapUnrecoverableException(String message) {
        super(message);
    }
}
