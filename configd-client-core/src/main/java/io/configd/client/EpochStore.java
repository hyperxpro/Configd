package io.configd.client;

/**
 * Persistence for the monotonic replay-protection high-water mark — the highest signing {@code epoch} the
 * client has accepted in a verified delta. Any later delta with {@code 0 < epoch <= highestSeen} is a replay
 * and is rejected (see the reference {@code DeltaApplier}). Persisting it durably is what stops a captured
 * older leader-signed delta from being replayed <b>across a process restart</b>: without it, a restarted client
 * starts at {@code 0} and would re-accept an already-superseded epoch.
 *
 * <p>The lifetime coupling mirrors the cursor: a <b>persistent</b> client (a data directory configured)
 * persists both the resume {@link CursorStore} and this high-water durably and crash-atomically; an
 * <b>ephemeral</b> client keeps both in memory (moot — it re-hydrates from scratch on restart). Ship
 * {@link InMemoryEpochStore} for tests and ephemeral clients, {@link FileEpochStore} for durable ones.
 */
public interface EpochStore {

    /** The persisted high-water, or {@code 0} if none / unreadable / corrupt (fail-open first-boot). */
    long load();

    /** Persists {@code epoch} as the new high-water. A durable impl writes crash-atomically. */
    void save(long epoch);
}
