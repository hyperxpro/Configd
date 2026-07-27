package io.configd.client;

/**
 * Persistence for replay-protection high-water: highest epoch accepted in verified delta. Any delta with
 * 0 < epoch <= highestSeen is rejected as replay. Persisting durably across process restart is what stops
 * replay of captured older leader-signed deltas.
 */
public interface EpochStore {

    /**
     * Persisted high-water, or 0 if none/unreadable/corrupt (fail-open on first boot).
     */
    long load();

    /**
     * Persist epoch as new high-water. Durable implementations must write crash-atomically.
     */
    void save(long epoch);
}
