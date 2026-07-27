package io.configd.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Durable, crash-atomic EpochStore: 12-byte file [8B epoch][4B CRC32C]. Read anomalies (missing, wrong size,
 * CRC mismatch, negative value) demote to 0 (fail-open first-boot). Writes via temp + atomic rename to prevent
 * torn high-water.
 */
public final class FileEpochStore implements EpochStore {

    public static final String EPOCH_LOCK_FILENAME = "epoch.lock";
    private static final int EPOCH_LOCK_BYTES = 12;

    private final Path path;

    public FileEpochStore(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir");
        this.path = dataDir.resolve(EPOCH_LOCK_FILENAME);
    }

    @Override
    public long load() {
        if (!Files.exists(path)) {
            return 0L;
        }
        try {
            byte[] data = Files.readAllBytes(path);
            if (data.length != EPOCH_LOCK_BYTES) {
                return 0L;
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            long epoch = buf.getLong();
            int storedCrc = buf.getInt();
            CRC32C crc = new CRC32C();
            crc.update(data, 0, 8);
            if (storedCrc != (int) crc.getValue() || epoch < 0) {
                return 0L;
            }
            return epoch;
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public void save(long epoch) {
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be non-negative: " + epoch);
        }
        try {
            Path dir = path.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            ByteBuffer buf = ByteBuffer.allocate(EPOCH_LOCK_BYTES);
            buf.putLong(epoch);
            CRC32C crc = new CRC32C();
            crc.update(buf.array(), 0, 8);
            buf.putInt((int) crc.getValue());

            Path tmp = path.resolveSibling(EPOCH_LOCK_FILENAME + ".tmp");
            Files.write(tmp, buf.array());
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist epoch high-water to " + path, e);
        }
    }
}
