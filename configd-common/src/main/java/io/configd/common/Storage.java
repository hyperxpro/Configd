package io.configd.common;

/**
 * Abstraction for durable key-value storage.
 * <p>
 * Implementations must guarantee that after {@link #put} returns, the
 * data survives process restarts (i.e., it is fsynced or equivalent).
 * <p>
 * Two implementations are expected:
 * <ul>
 *   <li><b>Production:</b> Memory-mapped file or RocksDB-backed storage</li>
 *   <li><b>Simulation:</b> In-memory map for deterministic testing</li>
 * </ul>
 * <p>
 * This interface is intentionally minimal. Raft only needs to persist
 * three things: currentTerm, votedFor, and the log entries. The WAL
 * (Write-Ahead Log) for log entries is a separate concern built on
 * top of this interface.
 */
public interface Storage {

    /**
     * Stores a byte array value for the given key. Must be durable
     * (survive process restart) before returning.
     *
     * @param key   the key (non-null)
     * @param value the value (non-null, may be empty)
     */
    void put(String key, byte[] value);

    /**
     * Retrieves the value for the given key.
     *
     * @param key the key (non-null)
     * @return the stored value, or null if the key does not exist
     */
    byte[] get(String key);

    /**
     * Appends data to a named log. Used for WAL entries.
     * Must be durable before returning.
     *
     * @param logName the log name (e.g., "raft-wal")
     * @param data    the data to append
     */
    void appendToLog(String logName, byte[] data);

    /**
     * Reads all log entries for the named log in append order.
     *
     * @param logName the log name
     * @return all entries in order, or empty list if the log does not exist
     */
    java.util.List<byte[]> readLog(String logName);

    /**
     * Truncates a log, removing all entries. Used after snapshot compaction.
     *
     * @param logName the log name
     */
    void truncateLog(String logName);

    /**
     * Renames a log from one name to another. Used for atomic WAL rewrites:
     * write to a temp log, then rename over the original.
     * If a log with the target name already exists, it is replaced.
     *
     * @param fromLogName the source log name
     * @param toLogName   the target log name
     */
    void renameLog(String fromLogName, String toLogName);

    /**
     * Forces all pending writes to durable storage.
     */
    void sync();

    // ========================================================================
    // Factory methods for standard implementations
    // ========================================================================

    /**
     * In-memory implementation for testing. NOT durable — data lost on restart.
     * Suitable for deterministic simulation (ADR-0007).
     */
    static Storage inMemory() {
        return new InMemoryStorage();
    }

    /**
     * File-backed implementation with fsync durability.
     * Creates the directory if it does not exist.
     *
     * @param directory the directory for storage files
     * @return a new durable file-backed storage
     */
    static Storage file(java.nio.file.Path directory) {
        return new FileStorage(directory);
    }
}
