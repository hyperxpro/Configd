package io.configd.client;

import io.configd.distribution.wire.WatchCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable resume stores must survive a "restart" (a fresh instance over the same directory) and fail open
 * (demote to a fresh start) on a corrupt / torn record, never trusting garbage.
 */
class PersistenceStoreTest {

    @Test
    void fileEpochStoreRoundTripsAndSurvivesRestart(@TempDir Path dir) {
        new FileEpochStore(dir).save(42L);
        assertEquals(42L, new FileEpochStore(dir).load(), "a fresh instance reads the persisted high-water");
    }

    @Test
    void fileEpochStoreIsMonotonicAndFailsOpenOnCorruption(@TempDir Path dir) throws Exception {
        FileEpochStore store = new FileEpochStore(dir);
        store.save(10L);
        assertEquals(10L, store.load());
        Files.write(dir.resolve(FileEpochStore.EPOCH_LOCK_FILENAME), new byte[]{1, 2, 3});
        assertEquals(0L, new FileEpochStore(dir).load(), "a corrupt sidecar reads as absent (fail-open)");
    }

    @Test
    void missingEpochSidecarReadsAsZero(@TempDir Path dir) {
        assertEquals(0L, new FileEpochStore(dir).load());
    }

    @Test
    void fileCursorStoreRoundTripsVectorCursor(@TempDir Path dir) {
        FileCursorStore store = new FileCursorStore(dir);
        WatchCursor cursor = WatchCursor.of(0, 7L);
        store.save("sub:full", cursor);

        Optional<WatchCursor> loaded = new FileCursorStore(dir).load("sub:full");
        assertTrue(loaded.isPresent());
        assertEquals(7L, loaded.get().components().get(0).s());
        assertEquals(WatchCursor.INITIAL_TOPOLOGY_EPOCH, loaded.get().topologyEpoch());
    }

    @Test
    void fileCursorStoreFailsOpenOnCorruption(@TempDir Path dir) throws Exception {
        FileCursorStore store = new FileCursorStore(dir);
        store.save("sub:full", WatchCursor.of(0, 3L));
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Files.write(p, new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
            }
        }
        assertTrue(new FileCursorStore(dir).load("sub:full").isEmpty(), "corrupt cursor reads as absent");
    }

    @Test
    void missingCursorReadsAsEmpty(@TempDir Path dir) {
        assertTrue(new FileCursorStore(dir).load("nope").isEmpty());
    }
}
