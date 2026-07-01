package io.configd.store;

import io.configd.common.Clock;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backup/restore round-trip test with a state-equality assertion. Captures a snapshot (the
 * backup), restores it into a fresh, empty state machine (a fresh cluster's bootstrap), and
 * proves the restored state equals the original key-for-key - including an overwrite and a
 * delete - and that the restored applied version matches. The snapshot bytes are
 * {@link ConfigStateMachine#snapshot()}; restore is
 * {@link ConfigStateMachine#restoreSnapshot(byte[])} (the InstallSnapshot / disaster-recovery path).
 */
class BackupRestoreRoundTripTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void snapshotRestoredIntoFreshClusterIsStateEqual() {
        // ---- original cluster: apply a mix of put / overwrite / delete -------------------
        VersionedConfigStore origin = new VersionedConfigStore();
        ConfigStateMachine source = new ConfigStateMachine(origin, Clock.system());
        long idx = 0;
        source.apply(++idx, 1, CommandCodec.encodePut("svc/a", b("alpha")));
        source.apply(++idx, 1, CommandCodec.encodePut("svc/b", b("beta-0")));
        source.apply(++idx, 1, CommandCodec.encodePut("svc/b", b("beta-1")));   // overwrite
        source.apply(++idx, 1, CommandCodec.encodePut("svc/c", b("gamma")));
        source.apply(++idx, 1, CommandCodec.encodeDelete("svc/c"));             // delete
        long sourceVersion = origin.currentVersion();

        // ---- back up: the snapshot IS the backup artifact -------------------------------
        byte[] backup = source.snapshot();
        assertTrue(backup.length >= 12, "snapshot must carry the header + entries");

        // ---- restore into a FRESH, empty state machine (fresh-cluster bootstrap) --------
        VersionedConfigStore restoredStore = new VersionedConfigStore();
        ConfigStateMachine restored = new ConfigStateMachine(restoredStore, Clock.system());
        restored.restoreSnapshot(backup);

        // ---- state equality: key-for-key, plus the delete and the applied version -------
        assertArrayEquals(b("alpha"), restoredStore.get("svc/a").value(), "svc/a survives restore");
        assertArrayEquals(b("beta-1"), restoredStore.get("svc/b").value(), "overwrite wins after restore");
        assertFalse(restoredStore.get("svc/c").found(), "deleted key must NOT resurrect on restore");
        assertEquals(sourceVersion, restoredStore.currentVersion(),
                "restored applied version must equal the snapshot's version");

        // ---- and the restored cluster keeps serving writes from the restored version ----
        long next = restored.apply(sourceVersion + 1, 2, CommandCodec.encodePut("svc/d", b("delta")));
        assertEquals(sourceVersion + 1, next, "restored SM continues the version sequence");
        assertArrayEquals(b("delta"), restoredStore.get("svc/d").value());
    }
}
