package io.configd.store;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the additive snapshot-install listener on {@link ConfigStateMachine}. A snapshot
 * install wholesale-replaces the store with no per-mutation {@link ConfigStateMachine.ConfigChangeListener}
 * notification, so this hook is how the config-policy loader learns of {@code _acl/} changes delivered via
 * InstallSnapshot (follower catch-up / runtime restore). The hook must fire on a SUCCESSFUL restore, NOT
 * fire on a failed restore, and be empty-default (no listener means unchanged behavior - covered by the
 * existing {@code SnapshotAndRestore} suite).
 */
class ConfigStateMachineSnapshotListenerTest {

    private static byte[] validSnapshot() {
        // A snapshot of an empty store is a well-formed snapshot payload.
        return new ConfigStateMachine(new VersionedConfigStore()).snapshot();
    }

    @Test
    void snapshotListenerFiresAfterSuccessfulRestore() {
        ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore());
        AtomicInteger fires = new AtomicInteger();
        sm.addSnapshotListener(fires::incrementAndGet);

        sm.restoreSnapshot(validSnapshot());

        assertEquals(1, fires.get(), "snapshot listener must fire once after a successful restore");
    }

    @Test
    void multipleSnapshotListenersFireInRegistrationOrder() {
        ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore());
        List<String> order = new ArrayList<>();
        sm.addSnapshotListener(() -> order.add("a"));
        sm.addSnapshotListener(() -> order.add("b"));

        sm.restoreSnapshot(validSnapshot());

        assertEquals(List.of("a", "b"), order);
    }

    @Test
    void snapshotListenerDoesNotFireWhenRestoreFails() {
        ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore());
        AtomicInteger fires = new AtomicInteger();
        sm.addSnapshotListener(fires::incrementAndGet);

        // A truncated payload (4 bytes - too short for the 8-byte sequence header) fails before any
        // store replacement; the listener must NOT fire (notify only on success).
        assertThrows(RuntimeException.class, () -> sm.restoreSnapshot(new byte[]{0, 0, 0, 0}));

        assertEquals(0, fires.get(), "snapshot listener must not fire on a failed restore");
    }

    @Test
    void addSnapshotListenerRejectsNull() {
        ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore());
        assertThrows(NullPointerException.class, () -> sm.addSnapshotListener(null));
    }
}
