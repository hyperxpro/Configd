package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Multi-Raft Phase 1 — C4a: tests for the deploy-time shard-count selection + the fixed-at-deploy
 * reshard guard ({@link ConfigdServer#resolveShardCount} / {@link ConfigdServer#enforceFixedShardCount}).
 *
 * <p>These drive the REAL helpers the production boot path calls, so they discriminate: the default is
 * {@code N=1} (byte-identical to today), {@code N} is range-checked, {@code N>1} is refused while the
 * N-group wiring is dormant (silent-corruption guard, charter §2), and {@code N} is FIXED AT DEPLOY (a
 * later boot with a different {@code N} is rejected rather than silently mis-routing committed keys).
 */
class ShardCountConfigTest {

    private static final String PROP = "configd.raft.shardCount";
    private static final String MARKER = "raft-shard-count.meta";

    @TempDir
    Path dataDir;

    private String saved;

    @BeforeEach
    void saveProp() {
        saved = System.getProperty(PROP);
        System.clearProperty(PROP);
    }

    @AfterEach
    void restoreProp() {
        if (saved == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, saved);
        }
    }

    // ---- resolveShardCount: default + range -------------------------------------------------

    @Test
    void defaultIsOneAndWritesMarker() {
        int n = ConfigdServer.resolveShardCount(dataDir);
        assertEquals(1, n, "default shard count must be 1 (single group, byte-identical to today)");
        assertTrue(Files.exists(dataDir.resolve(MARKER)), "first boot records the deploy-time N");
    }

    @Test
    void explicitOneIsAccepted() {
        System.setProperty(PROP, "1");
        assertEquals(1, ConfigdServer.resolveShardCount(dataDir));
    }

    @Test
    void zeroIsRejectedAsOutOfRange() {
        System.setProperty(PROP, "0");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> ConfigdServer.resolveShardCount(dataDir));
        assertTrue(e.getMessage().contains("[1, 16]"), () -> "range error should name the bounds: " + e.getMessage());
        assertFalse(Files.exists(dataDir.resolve(MARKER)), "a rejected boot must not persist a marker");
    }

    @Test
    void aboveCeilingIsRejectedAsOutOfRange() {
        System.setProperty(PROP, "17");
        assertThrows(IllegalArgumentException.class, () -> ConfigdServer.resolveShardCount(dataDir));
        assertFalse(Files.exists(dataDir.resolve(MARKER)), "a rejected boot must not persist a marker");
    }

    // ---- resolveShardCount: N>1 temporary guard ---------------------------------------------

    @Test
    void nGreaterThanOneIsRefusedWhileWiringDormant() {
        // Cover the FULL forbidden band, especially the BOUNDARY value N=2 (a `> 1` → `> 2` mutation of
        // the guard would route N=2 to an unregistered group while leaving an N=4-only test green —
        // red-team MEDIUM) and the in-range ceiling N=16 (refused by the N>1 guard, not the range check).
        for (int n : new int[] {2, 4, 16}) {
            System.setProperty(PROP, Integer.toString(n));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ConfigdServer.resolveShardCount(dataDir), () -> "N=" + n + " must be refused");
            assertTrue(e.getMessage().contains("N>1"),
                    () -> "guard should explain N>1 is not wired (N=" + n + "): " + e.getMessage());
            // CRITICAL: a refused N>1 boot must NOT poison the data dir with an N>1 marker — a later N=1
            // boot would then be rejected as a "reshard". The guard runs BEFORE the marker is persisted.
            assertFalse(Files.exists(dataDir.resolve(MARKER)),
                    "refused N=" + n + " boot must not persist a marker (else it poisons a later N=1 boot)");
        }
        // Prove it: a subsequent default (N=1) boot on the same data dir succeeds.
        System.clearProperty(PROP);
        assertEquals(1, ConfigdServer.resolveShardCount(dataDir));
    }

    // ---- enforceFixedShardCount: fixed-at-deploy reshard guard ------------------------------

    @Test
    void firstBootPersistsThenIsIdempotent() throws Exception {
        ConfigdServer.enforceFixedShardCount(3, dataDir);
        assertEquals("3", Files.readString(dataDir.resolve(MARKER), StandardCharsets.UTF_8).trim());
        // Same N again is a no-op (idempotent across restarts).
        ConfigdServer.enforceFixedShardCount(3, dataDir);
        assertEquals("3", Files.readString(dataDir.resolve(MARKER), StandardCharsets.UTF_8).trim());
    }

    @Test
    void changedShardCountIsRejectedAsReshard() throws Exception {
        ConfigdServer.enforceFixedShardCount(4, dataDir);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigdServer.enforceFixedShardCount(8, dataDir));
        assertTrue(e.getMessage().contains("FIXED AT DEPLOY"),
                () -> "reshard rejection should explain fixed-at-deploy: " + e.getMessage());
        assertTrue(e.getMessage().contains("N=4"), () -> "should name the persisted N: " + e.getMessage());
        // The marker is unchanged (the rejection does not overwrite it).
        assertEquals("4", Files.readString(dataDir.resolve(MARKER), StandardCharsets.UTF_8).trim());
    }

    @Test
    void corruptMarkerIsRejected() throws Exception {
        Files.writeString(dataDir.resolve(MARKER), "not-a-number", StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigdServer.enforceFixedShardCount(1, dataDir));
        assertTrue(e.getMessage().toLowerCase().contains("corrupt"),
                () -> "should flag a corrupt marker: " + e.getMessage());
    }
}
