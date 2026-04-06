package io.configd.store;

import io.configd.common.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F5 (Tier-1-METRIC-DRIFT) — verifies that {@link ConfigStateMachine}
 * fires the {@link StateMachineMetrics} callbacks at the correct points
 * so {@code configd_write_commit_*} and
 * {@code configd_snapshot_install_failed_total} get values.
 *
 * <p>Tests use a recording {@link StateMachineMetrics} (not a Mockito
 * mock) per the codebase's testing convention: state machines, registries,
 * and trackers are exercised through their real APIs.
 */
class ConfigStateMachineMetricsTest {

    private VersionedConfigStore store;
    private RecordingMetrics metrics;
    private ConfigStateMachine stateMachine;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        store = new VersionedConfigStore();
        metrics = new RecordingMetrics();
        stateMachine = new ConfigStateMachine(store, Clock.system(), null, null, metrics);
    }

    @Test
    void putApplyIncrementsWriteCommitSuccess() {
        stateMachine.apply(1, 1, CommandCodec.encodePut("k", bytes("v")));
        assertEquals(1, metrics.successCount.get(),
                "PUT apply must fire onWriteCommitSuccess exactly once");
        assertEquals(0, metrics.failureCount.get());
        assertTrue(metrics.lastDurationNanos.get() >= 0,
                "duration must be a non-negative nanoTime delta");
    }

    @Test
    void deleteApplyIncrementsWriteCommitSuccess() {
        stateMachine.apply(1, 1, CommandCodec.encodePut("k", bytes("v")));
        stateMachine.apply(2, 1, CommandCodec.encodeDelete("k"));
        assertEquals(2, metrics.successCount.get(),
                "PUT then DELETE must fire two success events");
    }

    @Test
    void noopApplyDoesNotIncrementCommitMetrics() {
        // CommandCodec.NOOP_BYTES — empty command. Apply with empty array.
        stateMachine.apply(1, 1, new byte[0]);
        assertEquals(0, metrics.successCount.get(),
                "Noop apply must NOT fire success");
        assertEquals(0, metrics.failureCount.get(),
                "Noop apply must NOT fire failure");
    }

    @Test
    void signingFailureFiresFailureCounterAndRethrows() throws Exception {
        // Construct a verify-only ConfigSigner (no private key) — its
        // sign() throws IllegalStateException, which the state machine
        // catches and converts to the fail-close path documented in
        // S3 / PA-1004.
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ConfigSigner verifyOnly = new ConfigSigner(kp.getPublic());
        ConfigStateMachine sm = new ConfigStateMachine(
                new VersionedConfigStore(), Clock.system(), null, verifyOnly, metrics);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sm.apply(1, 1, CommandCodec.encodePut("k", bytes("v"))));
        assertTrue(ex.getMessage().contains("fail-close"),
                "expected fail-close message, got: " + ex.getMessage());
        assertEquals(1, metrics.failureCount.get(),
                "signing failure must increment write_commit_failed counter");
    }

    @Test
    void restoreSnapshotSuccessIncrementsRebuild() {
        // Round-trip: take a snapshot then restore it.
        stateMachine.apply(1, 1, CommandCodec.encodePut("a", bytes("1")));
        stateMachine.apply(2, 1, CommandCodec.encodePut("b", bytes("2")));

        byte[] snap = stateMachine.snapshot();
        // Reset recording counters so we measure the restore path only.
        RecordingMetrics restoreMetrics = new RecordingMetrics();
        ConfigStateMachine target = new ConfigStateMachine(
                new VersionedConfigStore(), Clock.system(), null, null, restoreMetrics);

        target.restoreSnapshot(snap);
        assertEquals(1, restoreMetrics.snapshotRebuildCount.get(),
                "successful restoreSnapshot must fire onSnapshotRebuildSuccess");
        assertEquals(0, restoreMetrics.snapshotInstallFailedCount.get());
    }

    @Test
    void restoreSnapshotFailureIncrementsInstallFailed() {
        // Craft a malformed envelope with a negative entry count so the
        // F-0053 bound check throws IllegalArgumentException, hitting the
        // F5 onSnapshotInstallFailed branch.
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putLong(0L);    // sequence counter
        buf.putInt(-1);     // entry count — invalid
        byte[] malformed = buf.array();

        assertThrows(IllegalArgumentException.class,
                () -> stateMachine.restoreSnapshot(malformed));
        assertEquals(1, metrics.snapshotInstallFailedCount.get(),
                "malformed snapshot must increment install_failed counter");
        assertEquals(0, metrics.snapshotRebuildCount.get());
    }

    /** Real (non-mock) recording sink — same testing style as the other
     *  config-store tests use for InvariantChecker. */
    private static final class RecordingMetrics implements StateMachineMetrics {
        final AtomicInteger successCount = new AtomicInteger();
        final AtomicInteger failureCount = new AtomicInteger();
        final AtomicLong lastDurationNanos = new AtomicLong(-1);
        final AtomicInteger snapshotRebuildCount = new AtomicInteger();
        final AtomicInteger snapshotInstallFailedCount = new AtomicInteger();

        @Override public void onWriteCommitSuccess(long applyDurationNanos) {
            successCount.incrementAndGet();
            lastDurationNanos.set(applyDurationNanos);
        }
        @Override public void onWriteCommitFailure() {
            failureCount.incrementAndGet();
        }
        @Override public void onSnapshotRebuildSuccess() {
            snapshotRebuildCount.incrementAndGet();
        }
        @Override public void onSnapshotInstallFailed() {
            snapshotInstallFailedCount.incrementAndGet();
        }
    }
}
