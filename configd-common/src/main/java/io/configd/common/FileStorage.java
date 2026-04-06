package io.configd.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * File-backed implementation of {@link Storage} with fsync durability.
 * <p>
 * Key-value pairs are stored as individual {@code .dat} files.
 * Logs are stored as {@code .wal} files using a simple framed format:
 * {@code [4-byte length][data][4-byte CRC32]} per entry.
 * <p>
 * All writes are fsynced before returning to guarantee durability.
 */
public final class FileStorage implements Storage {

    private final Path directory;

    /**
     * Creates a new FileStorage backed by the given directory.
     * The directory is created if it does not already exist.
     *
     * @param directory the directory for storage files
     * @throws UncheckedIOException if the directory cannot be created
     */
    public FileStorage(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create storage directory: " + directory, e);
        }
    }

    @Override
    public void put(String key, byte[] value) {
        Path file = directory.resolve(key + ".dat");
        Path tmp = directory.resolve(key + ".dat.tmp");
        try {
            // Write to temp file first, then atomic rename.
            // This prevents a crash between truncation and fsync from
            // corrupting the existing file — critical for Raft persistent
            // state (currentTerm, votedFor) which must survive crashes.
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(value);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                channel.force(true); // fsync data + metadata
            }
            // Atomic rename: either the old file or the new file is visible,
            // never a partial/corrupt state.
            Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            // fsync directory to ensure the rename is durable
            sync();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write key: " + key, e);
        }
    }

    @Override
    public byte[] get(String key) {
        Path file = directory.resolve(key + ".dat");
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read key: " + key, e);
        }
    }

    @Override
    public void appendToLog(String logName, byte[] data) {
        Path file = directory.resolve(logName + ".wal");
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {

            CRC32 crc = new CRC32();
            crc.update(data);
            int crcValue = (int) crc.getValue();

            // Frame: [4-byte length][data][4-byte CRC32]
            ByteBuffer frame = ByteBuffer.allocate(4 + data.length + 4);
            frame.putInt(data.length);
            frame.put(data);
            frame.putInt(crcValue);
            frame.flip();

            while (frame.hasRemaining()) {
                channel.write(frame);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to log: " + logName, e);
        }
    }

    @Override
    public List<byte[]> readLog(String logName) {
        Path file = directory.resolve(logName + ".wal");
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize == 0) {
                return Collections.emptyList();
            }

            ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) {
                    break;
                }
            }
            buffer.flip();

            List<byte[]> entries = new ArrayList<>();
            while (buffer.remaining() >= 4) {
                int length = buffer.getInt();

                // F-0011 fix: A crash during appendToLog() can leave a
                // partially written trailing entry (length header written
                // but data/CRC incomplete). Treat truncated trailing entries
                // as uncommitted and discard them instead of crashing — the
                // entry was never fsynced completely so it was never durable.
                // A negative length also indicates corruption of the length
                // field itself (partial write of the 4-byte int).
                if (length < 0 || buffer.remaining() < length + 4) {
                    // Truncated trailing entry — stop reading.
                    // All previously read entries (which had valid CRCs) are
                    // intact. The partial entry is discarded.
                    break;
                }

                byte[] data = new byte[length];
                buffer.get(data);
                int storedCrc = buffer.getInt();

                CRC32 crc = new CRC32();
                crc.update(data);
                int computedCrc = (int) crc.getValue();

                if (storedCrc != computedCrc) {
                    throw new IOException("CRC32 mismatch in log: " + logName
                            + " (stored=" + Integer.toHexString(storedCrc)
                            + ", computed=" + Integer.toHexString(computedCrc) + ")");
                }

                entries.add(data);
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read log: " + logName, e);
        }
    }

    @Override
    public void truncateLog(String logName) {
        Path file = directory.resolve(logName + ".wal");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to truncate log: " + logName, e);
        }
    }

    @Override
    public void renameLog(String fromLogName, String toLogName) {
        Path from = directory.resolve(fromLogName + ".wal");
        Path to = directory.resolve(toLogName + ".wal");
        try {
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to rename log " + fromLogName + " to " + toLogName, e);
        }
    }

    @Override
    public void sync() {
        try (FileChannel dirChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to sync directory: " + directory, e);
        }
    }
}
