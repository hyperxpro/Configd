package io.configd.raft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Production AnchorIO: real raft-anchor file, pwrite + fdatasync. Preallocated (no ENOSPC after boot);
 * later writes are in-place, cheaper fdatasync (no metadata change). Single-threaded (WAL owner thread).
 */
final class FileAnchorIO implements AnchorIO {

    static final String ANCHOR_FILE_NAME = "raft-anchor";

    private final Path dir;
    private final Path file;
    private FileChannel channel; // Lazy; kept open for in-place writes.

    FileAnchorIO(Path dir) {
        this(dir, ANCHOR_FILE_NAME);
    }

    /** Slotted anchor file with custom name; reused for node-anchor. */
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
