package io.configd.raft;

import io.configd.common.Storage;

/**
 * AnchorIO variant: anchor image as self-durable Storage value (for crash-model durability tests).
 * In-memory mirror mutated by writeAt() but NOT durable; sync() publishes via atomic put().
 * Crash between writeAt() and sync() discards mirror; recovery reads last put() (un-synced slot lost, live slot survives).
 * Single-threaded (owner thread only).
 */
final class StorageAnchorIO implements AnchorIO {

    /** The self-durable key under which the whole dual-slot image is stored. */
    static final String ANCHOR_KEY = "raft-anchor";

    private final Storage storage;
    /** In-memory working image; the durable copy is whatever was last {@link #sync()}'d. */
    private byte[] mirror;

    StorageAnchorIO(Storage storage) {
        this.storage = storage;
        this.mirror = storage.get(ANCHOR_KEY);
    }

    @Override
    public boolean exists() {
        return storage.get(ANCHOR_KEY) != null;
    }

    @Override
    public byte[] readImage() {
        // Read the DURABLE image (what a restart would see), not the un-synced mirror.
        return storage.get(ANCHOR_KEY);
    }

    @Override
    public void createPreallocated(byte[] image) {
        this.mirror = image.clone();
        storage.put(ANCHOR_KEY, mirror.clone()); // self-durable: header + both slots laid down
    }

    @Override
    public void writeAt(long offset, byte[] bytes) {
        if (mirror == null) {
            // Should not happen: a write always follows create/open with a valid image.
            byte[] durable = storage.get(ANCHOR_KEY);
            this.mirror = (durable != null) ? durable : new byte[(int) (offset + bytes.length)];
        }
        System.arraycopy(bytes, 0, mirror, (int) offset, bytes.length);
        // Not durable yet - sync() publishes it.
    }

    @Override
    public void sync() {
        if (mirror != null) {
            storage.put(ANCHOR_KEY, mirror.clone());
        }
    }

    @Override
    public void close() {
        // No OS handle to release.
    }
}
