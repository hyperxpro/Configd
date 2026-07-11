package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * The replay source no longer holds the requested range: {@link ErrorCode#GAP_UNRECOVERABLE} (6) — the resume
 * cursor is older than the server's retained history. Carrier-dependent scope: per-watch
 * ({@code WATCH_CANCELED}, siblings survive) on the {@code 0x02} plane, connection-fatal ({@code ERROR_CLOSE})
 * on the legacy plane.
 *
 * <p><b>Reaction:</b> <b>re-bootstrap from a snapshot</b> ({@code with_initial_snapshot}) — the affected
 * watch, or the whole connection on the legacy plane — and <b>do not</b> keep retrying the same cursor.
 * Distinct from {@link StaleTopologyException} (which drops the cursor entirely).
 */
public final class GapUnrecoverableException extends ConfigdException {

    public GapUnrecoverableException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }

    /**
     * A client-detected chain gap or truncated / mismatched hydration snapshot: the local state cannot be
     * continued from what arrived, so the client re-bootstraps from a fresh snapshot rather than applying a
     * partial state. Same reaction (re-bootstrap), no server {@link ErrorCode}.
     */
    public GapUnrecoverableException(String message) {
        super(message);
    }
}
