package io.configd.client;

import io.configd.distribution.wire.WatchCursor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;

/**
 * A durable, crash-atomic {@link CursorStore}: one file per key under a data directory, holding the resume
 * {@link WatchCursor} so a client can re-{@code SUBSCRIBE} at its last-applied position across a restart.
 * The record is the frozen cursor wire shape plus a CRC:
 * {@code [8B epoch][4B count]( 4B gid, 8B S )*[4B CRC32C]}. A missing / wrong-size / CRC-mismatched file reads
 * as absent (fail-open — the client re-hydrates from scratch); writes go through a temp file + atomic rename.
 */
public final class FileCursorStore implements CursorStore {

    private final Path dir;

    public FileCursorStore(Path dataDir) {
        this.dir = Objects.requireNonNull(dataDir, "dataDir");
    }

    @Override
    public void save(String key, WatchCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        List<WatchCursor.Component> components = cursor.components();
        ByteBuffer body = ByteBuffer.allocate(8 + 4 + components.size() * 12);
        body.putLong(cursor.topologyEpoch());
        body.putInt(components.size());
        for (WatchCursor.Component c : components) {
            body.putInt(c.gid());
            body.putLong(c.s());
        }
        CRC32C crc = new CRC32C();
        crc.update(body.array(), 0, body.position());
        ByteBuffer out = ByteBuffer.allocate(body.position() + 4);
        out.put(body.array(), 0, body.position());
        out.putInt((int) crc.getValue());
        try {
            Files.createDirectories(dir);
            Path target = fileFor(key);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, out.array());
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist cursor for key " + key, e);
        }
    }

    @Override
    public Optional<WatchCursor> load(String key) {
        Path target = fileFor(key);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            byte[] data = Files.readAllBytes(target);
            if (data.length < 16) { // epoch(8)+count(4)+crc(4)
                return Optional.empty();
            }
            int crcOffset = data.length - 4;
            CRC32C crc = new CRC32C();
            crc.update(data, 0, crcOffset);
            int stored = ByteBuffer.wrap(data, crcOffset, 4).getInt();
            if (stored != (int) crc.getValue()) {
                return Optional.empty(); // corruption — re-hydrate from scratch
            }
            ByteBuffer buf = ByteBuffer.wrap(data, 0, crcOffset);
            long epoch = buf.getLong();
            int count = buf.getInt();
            if (count < 0 || buf.remaining() != count * 12) {
                return Optional.empty();
            }
            List<WatchCursor.Component> components = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int gid = buf.getInt();
                long s = buf.getLong();
                components.add(new WatchCursor.Component(gid, s));
            }
            return Optional.of(new WatchCursor(epoch, components));
        } catch (IOException | RuntimeException e) {
            // Any read/parse failure (including an out-of-order-gid or negative-S record failing the
            // WatchCursor invariant) is treated as absent — the client re-hydrates rather than trusting
            // a corrupt cursor.
            return Optional.empty();
        }
    }

    /** A safe per-key filename: base64url of the UTF-8 key (no path separators, stable across runs). */
    private Path fileFor(String key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return dir.resolve("cursor-" + encoded + ".dat");
    }
}
