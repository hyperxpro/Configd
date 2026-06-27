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
 * {@code N=1} (byte-identical to today), {@code N} is range-checked, {@code N>1} now BOOTS (Seam G4
 * removed the temporary boot guard once the N-group wiring was proven end-to-end), and {@code N} is FIXED
 * AT DEPLOY (a later boot with a different {@code N} is rejected rather than silently mis-routing keys).
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

    // ---- resolveShardCount: N>1 now BOOTS (Seam G4 — the boot guard was removed) ------------

    @Test
    void nGreaterThanOneNowBootsAndIsFixedAtDeploy(@TempDir Path freshDir) {
        // Seam G4 flipped the switch: the temporary N>1 boot refusal is GONE (the N-group wiring is proven
        // end-to-end + the integrated sweep is green). N>1 now resolves to N AND persists the fixed-at-
        // deploy marker (so a later reshard is rejected). Cover the BOUNDARY N=2 and the ceiling N=16, each
        // on its OWN fresh data dir (so a persisted N from one iteration doesn't reshard-reject the next).
        for (int n : new int[] {2, 4, 16}) {
            Path dir = freshDir.resolve("n" + n);
            try {
                Files.createDirectories(dir);
            } catch (java.io.IOException io) {
                throw new RuntimeException(io);
            }
            System.setProperty(PROP, Integer.toString(n));
            assertEquals(n, ConfigdServer.resolveShardCount(dir), () -> "N=" + n + " must now boot");
            // fixed-at-deploy is now ACTIVE for N>1: the marker records N.
            assertTrue(Files.exists(dir.resolve(MARKER)), "N=" + n + " boot persists the fixed-at-deploy marker");
            // A reshard attempt (a DIFFERENT in-range N on the same dir) is rejected — not silent
            // mis-routing. Use n-1 (in [1,16], distinct from n) so the reshard guard fires, not the range
            // check (n+1 would hit MAX_SHARD_COUNT for n=16 and throw IllegalArgumentException instead).
            System.setProperty(PROP, Integer.toString(n - 1));
            IllegalStateException reshard = assertThrows(IllegalStateException.class,
                    () -> ConfigdServer.resolveShardCount(dir), () -> "reshard from N=" + n + " must reject");
            assertTrue(reshard.getMessage().contains("FIXED AT DEPLOY"),
                    () -> "reshard rejection should explain fixed-at-deploy: " + reshard.getMessage());
        }
    }

    @Test
    void n1StillBootsByteIdenticalAfterGuardRemoval() {
        // The guard removal must NOT touch the N=1 path: default (no property) resolves to 1 and persists
        // the N=1 marker, exactly as before G4.
        System.clearProperty(PROP);
        assertEquals(1, ConfigdServer.resolveShardCount(dataDir));
        try {
            assertEquals("1", Files.readString(dataDir.resolve(MARKER), StandardCharsets.UTF_8).trim());
        } catch (java.io.IOException io) {
            throw new RuntimeException(io);
        }
        // An out-of-range N is still a fail-fast IllegalArgumentException (the range check is unchanged).
        System.setProperty(PROP, Integer.toString(17));
        assertThrows(IllegalArgumentException.class, () -> ConfigdServer.resolveShardCount(dataDir));
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
