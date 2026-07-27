package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Replay source no longer holds requested range: cursor older than server's retained history.
 * Reaction: re-bootstrap from snapshot (do not keep retrying same cursor). Distinct from
 * {@link StaleTopologyException} (which drops cursor entirely).
 */
public final class GapUnrecoverableException extends ConfigdException {

    public GapUnrecoverableException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }

    /**
     * Client-detected chain gap: local state cannot be continued; re-bootstrap from fresh snapshot.
     */
    public GapUnrecoverableException(String message) {
        super(message);
    }
}
