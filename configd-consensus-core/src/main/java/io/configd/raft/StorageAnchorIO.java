package io.configd.raft;

import io.configd.common.Storage;

/**
 * A {@link AnchorIO} that carries the anchor image as a single self-durable {@link Storage}
 * value. It exists so the durability crash tests - which model a power loss through a
 * {@code Storage} crash model ({@code CrashStorage}) rather than a real device - capture the
 * anchor's durability at the same fidelity as the WAL.
 *
 * <p>The 1032-byte image (identical bytes to {@link FileAnchorIO}) is mirrored in memory; an
 * in-place {@link #writeAt} mutates the mirror but is NOT durable, and {@link #sync()} publishes
 * the whole mirror with a self-durable {@code Storage.put}. So the durability point is the
 * {@code put} at {@code sync()}: a crash between a slot's {@code writeAt} and its {@code sync}
 * discards the mirror and recovery reads back the last {@code put} image - the un-synced stale
 * slot is lost and the live slot survives, exactly as an un-{@code fdatasync}'d slot on a real
 * device. (Whole-image {@code put} is atomic, so this backend does not model a torn SLOT - that
 * byte-level case is covered by a dedicated {@link FileAnchorIO} test.)
 *
 * <p>Not thread-safe (single owner thread).
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
