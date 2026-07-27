package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Resume token's topologyEpoch no longer matches server's current epoch: cursor's topology generation is
 * superseded. Reaction: drop cursor entirely and fully re-hydrate from scratch (not just resume from earlier S,
 * which is {@link GapUnrecoverableException}). Deployed topology is static so this never fires today; exists for
 * future runtime topology changes.
 */
public final class StaleTopologyException extends ConfigdException {

    public StaleTopologyException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
