package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * The resume token's bound {@code topologyEpoch} no longer matches the server's current
 * {@code ShardMap.epoch()}: {@link ErrorCode#STALE_TOPOLOGY} (12) — the whole topology generation the
 * cursor/{@code SUBSCRIBE} belongs to is superseded (the etcd {@code ErrCompacted} model). Carrier-dependent:
 * per-watch ({@code WATCH_CANCELED}) for a watch, connection-fatal ({@code ERROR_CLOSE}) for a legacy
 * {@code SUBSCRIBE}.
 *
 * <p><b>§07 reaction:</b> <b>drop the cursor entirely and fully re-hydrate from scratch</b> — <b>do not</b>
 * re-send the stale cursor, and do not merely resume from an earlier {@code S} (that is the
 * {@link GapUnrecoverableException} reaction). This is a <b>v2-only</b> code: at v1 static-N (one deploy-time
 * epoch = {@code 1}) it never fires. Defined here in Gate 1 for a complete classifier; the resume path that
 * raises it is Gate 2/3.
 */
public final class StaleTopologyException extends ConfigdException {

    public StaleTopologyException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
