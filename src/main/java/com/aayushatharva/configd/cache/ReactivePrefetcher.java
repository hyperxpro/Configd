package com.aayushatharva.configd.cache;

import com.aayushatharva.configd.network.protocol.Message;
import com.aayushatharva.configd.network.protocol.MessageType;
import com.aayushatharva.configd.replication.ReplicationPuller;
import com.aayushatharva.configd.txlog.TransactionLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Reactive prefetcher for Configd v2.
 *
 * All cache misses resolved by relays are published as streams to proxies
 * within the same data center. Proxies subscribe and populate local caches
 * with resolved key-values.
 *
 * This dramatically improves cache hit rates to 99.9%+ for the worst
 * performing instance and >99.999% for most instances.
 */
public class ReactivePrefetcher implements ReplicationPuller.ReplicationListener {

    private static final Logger log = LoggerFactory.getLogger(ReactivePrefetcher.class);

    private final LocalCache localCache;
    private final List<BiConsumer<byte[], byte[]>> prefetchListeners = new CopyOnWriteArrayList<>();

    public ReactivePrefetcher(LocalCache localCache) {
        this.localCache = localCache;
    }

    /**
     * Called when new entries are replicated from upstream.
     * Prefetches them into the local cache proactively.
     */
    @Override
    public void onEntriesApplied(List<TransactionLogEntry> entries) {
        int prefetched = 0;
        for (var entry : entries) {
            switch (entry.operation()) {
                case SET -> {
                    boolean stored = localCache.put(entry.key(), entry.value(), entry.sequenceNumber());
                    if (stored) {
                        prefetched++;
                        notifyListeners(entry.key(), entry.value());
                    }
                }
                case DELETE -> localCache.invalidate(entry.key());
            }
        }
        if (prefetched > 0) {
            log.debug("Prefetched {} entries into local cache", prefetched);
        }
    }

    /**
     * Handle a prefetch notification from a relay.
     * The relay broadcasts cache-miss resolutions to all proxies.
     */
    public void handlePrefetchNotify(Message message) {
        byte[] payload = message.payload();
        if (payload == null) return;

        ByteBuffer buf = ByteBuffer.wrap(payload);
        int keyLen = buf.getInt();
        byte[] key = new byte[keyLen];
        buf.get(key);
        int valueLen = buf.getInt();
        byte[] value = null;
        if (valueLen > 0) {
            value = new byte[valueLen];
            buf.get(value);
        }
        long version = buf.getLong();

        if (value != null) {
            localCache.put(key, value, version);
            notifyListeners(key, value);
        }
    }

    /**
     * Build a prefetch notification message (sent by relays to proxies).
     */
    public static Message buildPrefetchNotification(byte[] key, byte[] value, long version) {
        int valueLen = (value != null) ? value.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(4 + key.length + 4 + valueLen + 8);
        buf.putInt(key.length);
        buf.put(key);
        buf.putInt(valueLen);
        if (valueLen > 0) buf.put(value);
        buf.putLong(version);
        return new Message(MessageType.PREFETCH_NOTIFY, 0, buf.array());
    }

    public void addPrefetchListener(BiConsumer<byte[], byte[]> listener) {
        prefetchListeners.add(listener);
    }

    private void notifyListeners(byte[] key, byte[] value) {
        for (var listener : prefetchListeners) {
            listener.accept(key, value);
        }
    }
}
