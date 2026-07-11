package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * The resume token's bound {@code topologyEpoch} no longer matches the server's current
 * {@code ShardMap.epoch()}: {@link ErrorCode#STALE_TOPOLOGY} (12) — the whole topology generation the
 * cursor/{@code SUBSCRIBE} belongs to is superseded (the etcd {@code ErrCompacted} model). Carrier-dependent:
 * per-watch ({@code WATCH_CANCELED}) for a watch, connection-fatal ({@code ERROR_CLOSE}) for a legacy
 * {@code SUBSCRIBE}.
 *
 * <p><b>Reaction:</b> <b>drop the cursor entirely and fully re-hydrate from scratch</b> — <b>do not</b>
 * re-send the stale cursor, and do not merely resume from an earlier {@code S} (that is the
 * {@link GapUnrecoverableException} reaction). The deployed topology is static — the shard count is fixed at
 * deploy time and the epoch never advances — so this exception never actually fires today; it exists so the
 * classifier is complete once a topology can change at runtime.
 */
public final class StaleTopologyException extends ConfigdException {

    public StaleTopologyException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
