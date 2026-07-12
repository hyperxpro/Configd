package io.configd.raft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The production {@link AnchorIO}: a real {@code raft-anchor} file, written with
 * fixed-offset {@code pwrite} + {@code fdatasync} (the frozen write protocol). The file lives
 * in the SAME directory as the group's WAL - required so the anchor and the WAL it references
 * share one device / failure domain.
 *
 * <p>{@link #createPreallocated} lays the whole 1032-byte image down at once and
 * {@code force(true)}s it (data) plus a directory fsync (the file's existence), so every later
 * {@link #writeAt} is an in-place overwrite of already-allocated blocks - no metadata change, so
 * {@link #sync()} is the cheaper {@code force(false)}/{@code fdatasync}, and ENOSPC is impossible
 * after boot on ext4/xfs.
 *
 * <p>Not thread-safe; the anchor is touched only by the group's single owner thread (the same
 * thread that owns the WAL), so no synchronization is used.
 */
final class FileAnchorIO implements AnchorIO {

    static final String ANCHOR_FILE_NAME = "raft-anchor";

    private final Path dir;
    private final Path file;
    /** Open lazily on first create/write; kept open across the process lifetime for in-place writes. */
    private FileChannel channel;

    /** The per-shard {@code raft-anchor} in {@code dir} (the group's WAL directory). */
    FileAnchorIO(Path dir) {
        this(dir, ANCHOR_FILE_NAME);
    }

    /**
     * A slotted anchor file named {@code fileName} in {@code dir}. Same fixed-offset {@code pwrite} +
     * {@code fdatasync} transport as the per-shard anchor; the node-anchor reuses it with a distinct
     * name ({@code node-anchor}) in {@code dataDir}, so the proven dual-slot mechanics live in one place.
     */
    FileAnchorIO(Path dir, String fileName) {
        this.dir = dir;
        this.file = dir.resolve(fileName);
    }

    @Override
    public boolean exists() {
        return Files.exists(file);
    }

    @Override
    public byte[] readImage() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read anchor file: " + file, e);
        }
    }

    @Override
    public void createPreallocated(byte[] image) {
        try {
            // CREATE_NEW so a stray pre-existing file is a loud failure, not a silent reuse:
            // the presence gate in AnchorFile has already decided this is a fresh bootstrap.
            try (FileChannel c = FileChannel.open(file,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(image);
                while (buf.hasRemaining()) {
                    c.write(buf);
                }
                c.force(true); // data + metadata: the whole preallocated image is durable
            }
            // Directory fsync so the file's existence (not just its bytes) survives a crash.
            fsyncDir();
            openChannel();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create anchor file: " + file, e);
        }
    }

    @Override
    public void writeAt(long offset, byte[] bytes) {
        try {
            FileChannel c = openChannel();
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                c.write(buf, offset + (bytes.length - buf.remaining()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write anchor slot at " + offset + ": " + file, e);
        }
    }

    @Override
    public void sync() {
        try {
            // force(false): the slots are preallocated and overwritten in place, so no file
            // metadata changed - a data-only sync is sufficient and skips the inode writeback.
            openChannel().force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fdatasync anchor file: " + file, e);
        }
    }

    @Override
    public void close() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to close anchor file: " + file, e);
            } finally {
                channel = null;
            }
        }
    }

    private FileChannel openChannel() throws IOException {
        if (channel == null || !channel.isOpen()) {
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        return channel;
    }

    private void fsyncDir() throws IOException {
        try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
            dirChannel.force(true);
        }
    }
}
