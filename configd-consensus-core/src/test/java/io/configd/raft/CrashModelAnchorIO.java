package io.configd.raft;

/**
 * A crash-model {@link AnchorIO} test backend (a standalone sibling of {@link StorageAnchorIO}): an
 * in-place {@link #writeAt} mutates an in-memory working mirror but is NOT durable, and {@link #sync()}
 * publishes the whole mirror to a shared {@link Disk}. So the durability point is {@code sync()}: a
 * crash between a slot's {@code writeAt} and its {@code sync} discards the mirror, and a "reboot"
 * (opening a NEW {@code CrashModelAnchorIO} over the SAME {@link Disk}) reads back the last synced
 * image - the un-synced stale slot is lost and the prior slot survives, exactly as an un-{@code
 * fdatasync}'d slot on a real device.
 *
 * <p>Whole-image publish is atomic, so this backend models the crash-that-loses-the-sync case (the
 * new slot never becomes durable). The byte-level TORN-slot case (a partial write that survives but
 * fails its CRC/MAC) is covered separately with a real {@link FileAnchorIO} + on-disk corruption.
 */
final class CrashModelAnchorIO implements AnchorIO {

    /** The shared durable store: survives a "reboot" (a new IO opened over the same Disk). */
    static final class Disk {
        byte[] image; // the last synced whole-file image, or null before first create
    }

    private final Disk disk;
    private byte[] mirror; // working copy; durable only after sync()

    CrashModelAnchorIO(Disk disk) {
        this.disk = disk;
        this.mirror = (disk.image == null) ? null : disk.image.clone();
    }

    @Override
    public boolean exists() {
        return disk.image != null;
    }

    @Override
    public byte[] readImage() {
        // The DURABLE image (what a restart sees), never the un-synced mirror.
        return (disk.image == null) ? null : disk.image.clone();
    }

    @Override
    public void createPreallocated(byte[] image) {
        this.mirror = image.clone();
        this.disk.image = mirror.clone(); // header + both slots laid down durably
    }

    @Override
    public void writeAt(long offset, byte[] bytes) {
        if (mirror == null) {
            this.mirror = (disk.image != null) ? disk.image.clone() : new byte[(int) (offset + bytes.length)];
        }
        System.arraycopy(bytes, 0, mirror, (int) offset, bytes.length);
        // not durable until sync()
    }

    @Override
    public void sync() {
        if (mirror != null) {
            this.disk.image = mirror.clone();
        }
    }

    @Override
    public void close() {
        // no OS handle to release
    }
}
