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
 * A durable, crash-atomic {@link EpochStore} — the client mirror of the reference {@code DeltaApplier}'s
 * {@code epoch.lock} sidecar. The file is exactly {@value #EPOCH_LOCK_BYTES} bytes:
 * {@code [8B big-endian epoch][4B big-endian CRC32C(epoch)]}. On read, any anomaly — missing, wrong size, CRC
 * mismatch, or a negative value — is treated as "no record" and demoted to {@code 0} (fail-open first-boot);
 * the next successful {@link #save(long)} rewrites a valid record. Writes go through a temp file + atomic
 * rename so a crash mid-write cannot leave a torn high-water that would let an older leader-signed delta
 * replay past the restart.
 */
public final class FileEpochStore implements EpochStore {

    /** Sidecar filename inside the client data directory. */
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
                return 0L; // torn / legacy / unexpected size — treat as absent
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            long epoch = buf.getLong();
            int storedCrc = buf.getInt();
            CRC32C crc = new CRC32C();
            crc.update(data, 0, 8);
            if (storedCrc != (int) crc.getValue() || epoch < 0) {
                return 0L; // corruption or a negative value — fail-open
            }
            return epoch;
        } catch (IOException e) {
            return 0L; // unreadable — treat as absent; the next save rewrites it
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
