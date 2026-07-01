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
import java.util.Map;
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
     * Kept-open append channels for the batched WAL path
     * ({@link #appendToLogNoSync} / {@link #syncLog}). Holding the channel open across a
     * batch removes the per-entry open/close syscalls and lets one {@code force(true)}
     * durably commit N appended entries. Keyed by log name (the WAL is one or two names in
     * practice). Touched only from the single tick thread for the raft-log WAL, so a plain
     * HashMap is sufficient; an explicit synchronized guard is added for defensiveness since
     * {@link #truncateLog}/{@link #renameLog} may close a channel out from under a writer.
     * <p>
     * The durable single-append {@link #appendToLog} path is deliberately left untouched
     * (open+write+force+close per call) so crash/fault-injection wrappers that delegate to it
     * keep their exact, proven semantics - only callers that explicitly opt into the
     * no-sync/sync pair use these channels.
     */
    private final Map<String, FileChannel> appendChannels = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Log names whose backing file was freshly created by the batched path and therefore
     * still need a one-time directory fsync (so the file's existence, not just its bytes,
     * is durable) on the next {@link #syncLog}. Mirrors what {@link #put}'s {@link #sync()}
     * does for the rename path.
     */
    private final java.util.Set<String> pendingDirSync =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
            // corrupting the existing file - critical for Raft persistent
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

    // Batched WAL append (no-sync) + explicit per-log fsync.
    //
    // Used ONLY by callers that explicitly opt in. Holds the append channel open across the
    // batch so one force(true) durably commits N entries - eliminating the per-op fsync
    // overhead. The durable single-append appendToLog() above is left unchanged so
    // crash/fault-injection wrappers that delegate to it keep their proven semantics.
    //
    // Thread model: the raft-log WAL is touched only by the single tick thread. appendChannels
    // is a ConcurrentHashMap purely as defensive instance-state safety (the audit-log uses a
    // different log name and never touches the batched path).

    @Override
    public void appendToLogNoSync(String logName, byte[] data) {
        try {
            FileChannel channel = appendChannel(logName);
            ByteBuffer frame = frame(data);
            while (frame.hasRemaining()) {
                channel.write(frame);
            }
            // No force() here: durability is deferred to syncLog (group commit). The caller
            // MUST NOT treat these bytes as durable until the matching syncLog returns.
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append (no-sync) to log: " + logName, e);
        }
    }

    @Override
    public void syncLog(String logName) {
        FileChannel channel = appendChannels.get(logName);
        if (channel == null) {
            // Nothing buffered via the batched path (or it was just evicted by a
            // truncate/rename). A directory sync satisfies the contract vacuously - there
            // are no kept-open un-forced appends to flush.
            sync();
            return;
        }
        try {
            channel.force(true); // one fsync (data + metadata) durably commits the whole batch
            if (pendingDirSync.remove(logName)) {
                // First durability point for a freshly created WAL file: fsync the directory
                // too so the file's existence (not just its bytes) survives a crash.
                sync();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to sync log: " + logName, e);
        }
    }

    /**
     * Returns the kept-open append channel for {@code logName}, opening and caching it on
     * first use (CREATE|WRITE|APPEND, so every write lands at EOF).
     */
    private FileChannel appendChannel(String logName) throws IOException {
        FileChannel channel = appendChannels.get(logName);
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        Path file = directory.resolve(logName + ".wal");
        boolean existedBefore = Files.exists(file);
        channel = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        appendChannels.put(logName, channel);
        if (!existedBefore) {
            pendingDirSync.add(logName); // dir fsync on first syncLog (file-creation durability)
        }
        return channel;
    }

    /** Builds the framed WAL record: {@code [4-byte length][data][4-byte CRC32]}. */
    private static ByteBuffer frame(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        int crcValue = (int) crc.getValue();
        ByteBuffer frame = ByteBuffer.allocate(4 + data.length + 4);
        frame.putInt(data.length);
        frame.put(data);
        frame.putInt(crcValue);
        frame.flip();
        return frame;
    }

    /**
     * Closes and evicts any kept-open append channel for {@code logName} (idempotent). Must be
     * called before a truncate/rename replaces or removes the underlying file, so a subsequent
     * append reopens the new inode rather than writing through a stale descriptor.
     */
    private void evictAppendChannel(String logName) {
        FileChannel channel = appendChannels.remove(logName);
        pendingDirSync.remove(logName);
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to close append channel: " + logName, e);
            }
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

            ByteBuffer buffer = ByteBuffer.allocate(checkedLogReadSize(logName, fileSize));
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) {
                    break;
                }
            }
            buffer.flip();

            List<byte[]> entries = new ArrayList<>();
            while (buffer.remaining() >= 4) {
                int length = buffer.getInt();

                // A crash during appendToLog() can leave a partially written trailing entry
                // (length header written but data/CRC incomplete). Treat truncated trailing
                // entries as uncommitted and discard them - the entry was never fsynced
                // completely so it was never durable. A negative length also indicates
                // corruption of the length field itself (partial write of the 4-byte int).
                if (length < 0 || buffer.remaining() < length + 4) {
                    // Truncated trailing entry - stop reading. All previously read entries
                    // (which had valid CRCs) are intact. The partial entry is discarded.
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

    /**
     * The WAL recovery read allocates a single {@link ByteBuffer} sized by the file length.
     * A {@code long -> int} truncation on a WAL >= 2 GiB silently mis-sizes the buffer
     * (negative / wrapped) and reads garbage. With Raft-log compaction wired the WAL is
     * bounded in practice; this is the fail-loud backstop: a WAL at/beyond the JVM
     * single-array limit refuses to load with a clear, actionable error rather than silently
     * truncating committed entries.
     */
    static final long MAX_READABLE_LOG_BYTES = Integer.MAX_VALUE - 8L; // JVM max-array headroom

    static int checkedLogReadSize(String logName, long fileSize) {
        if (fileSize > MAX_READABLE_LOG_BYTES) {
            throw new IllegalStateException("WAL " + logName + " is " + fileSize
                    + " bytes, exceeding the single-read recovery limit " + MAX_READABLE_LOG_BYTES
                    + " — Raft-log compaction (RR-005) must bound WAL growth; refusing to load to"
                    + " avoid silent truncation by the int cast (fail loud, never lose committed entries)");
        }
        return (int) fileSize;
    }

    @Override
    public void truncateLog(String logName) {
        // Close any kept-open batched-append channel first: it points at the file we are about
        // to delete; a later append must reopen the fresh inode.
        evictAppendChannel(logName);
        Path file = directory.resolve(logName + ".wal");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to truncate log: " + logName, e);
        }
    }

    @Override
    public void renameLog(String fromLogName, String toLogName) {
        // The rename replaces toLogName's inode (and consumes fromLogName); evict both kept-open
        // channels so the next append reopens the correct, post-rename file.
        evictAppendChannel(fromLogName);
        evictAppendChannel(toLogName);
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
