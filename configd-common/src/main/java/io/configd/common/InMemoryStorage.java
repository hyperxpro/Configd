package io.configd.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link Storage} for testing.
 * <p>
 * NOT durable — all data is lost on process restart. This is the
 * correct implementation for deterministic simulation testing
 * (ADR-0007) where persistence is simulated, not real.
 */
public final class InMemoryStorage implements Storage {

    private final Map<String, byte[]> kvStore = new ConcurrentHashMap<>();
    private final Map<String, List<byte[]>> logs = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] value) {
        kvStore.put(key, value.clone());
    }

    @Override
    public byte[] get(String key) {
        byte[] val = kvStore.get(key);
        return val != null ? val.clone() : null;
    }

    @Override
    public void appendToLog(String logName, byte[] data) {
        logs.computeIfAbsent(logName, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(data.clone());
    }

    @Override
    public List<byte[]> readLog(String logName) {
        List<byte[]> log = logs.get(logName);
        if (log == null) {
            return Collections.emptyList();
        }
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    @Override
    public void truncateLog(String logName) {
        logs.remove(logName);
    }

    @Override
    public void renameLog(String fromLogName, String toLogName) {
        List<byte[]> log = logs.remove(fromLogName);
        if (log != null) {
            logs.put(toLogName, log);
        }
    }

    @Override
    public void sync() {
        // No-op for in-memory storage
    }
}
