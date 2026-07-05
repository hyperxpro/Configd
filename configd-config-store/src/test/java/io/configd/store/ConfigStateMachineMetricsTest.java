package io.configd.store;

import io.configd.common.Clock;
import io.configd.raft.StateMachine;
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
 * Verifies that {@link ConfigStateMachine} fires the {@link StateMachineMetrics}
 * callbacks at the correct points so {@code configd_write_commit_*} and
 * {@code configd_snapshot_install_failed_total} get values.
 *
 * <p>Tests use a recording {@link StateMachineMetrics} (not a Mockito mock) per
 * the codebase's testing convention: state machines, registries, and trackers are
 * exercised through their real APIs.
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
        // CommandCodec.NOOP_BYTES - empty command. Apply with empty array.
        stateMachine.apply(1, 1, new byte[0]);
        assertEquals(0, metrics.successCount.get(),
                "Noop apply must NOT fire success");
        assertEquals(0, metrics.failureCount.get(),
                "Noop apply must NOT fire failure");
    }

    @Test
    void signingFailureFiresFailureCounterAndRethrows() throws Exception {
        // Construct a verify-only ConfigSigner (no private key) - its
        // sign() throws IllegalStateException, which the state machine
        // catches and converts to the fail-close path.
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

    // -----------------------------------------------------------------------
    // WH-01: a malformed committed command is skipped deterministically as
    // NON_MUTATING (never crash-loops the apply loop) and rings the alarm.
    // -----------------------------------------------------------------------

    @Test
    void malformedCommittedCommandIsSkippedNotThrown() {
        // Unknown top-level type byte: frames cleanly (1 byte <= cmdLen bound) but has no grammar.
        byte[] poison = new byte[]{0x7F};

        long result = assertDoesNotThrow(() -> stateMachine.apply(1, 1, poison),
                "a malformed committed command must NOT throw out of apply (that is the crash-loop)");

        assertEquals(StateMachine.NON_MUTATING, result,
                "malformed command must be treated as non-mutating so applyCommitted advances lastApplied");
        assertEquals(1, metrics.malformedCount.get(),
                "malformed committed command must ring the onMalformedCommittedCommand alarm");
        assertEquals(0, metrics.successCount.get(), "no write should have committed");
        assertEquals(0, metrics.failureCount.get(),
                "the malformed skip is not a write-commit failure");
    }

    @Test
    void malformedSkipIsIdempotentAcrossReplay() {
        // Re-applying the SAME poison entry (as happens on every tick / WAL replay before the fix)
        // must keep returning NON_MUTATING without throwing - the property that breaks the crash-loop.
        byte[] poison = new byte[]{CommandCodec.TYPE_PUT, 0x00}; // truncated key length
        for (int i = 1; i <= 3; i++) {
            long r = assertDoesNotThrow(() -> stateMachine.apply(1, 1, poison));
            assertEquals(StateMachine.NON_MUTATING, r);
        }
        assertEquals(3, metrics.malformedCount.get());
    }

    @Test
    void applyLoopSurvivesPoisonPillBetweenValidWrites() {
        // A valid write, then a poison pill, then another valid write: the poison entry is skipped
        // and the sequence counter advances exactly for the two valid writes (no wedge, no gap).
        long s1 = stateMachine.apply(1, 1, CommandCodec.encodePut("a", bytes("1")));
        long poison = stateMachine.apply(2, 1, new byte[]{0x03, 0x00, 0x00, 0x00, 0x01}); // BATCH count=1, no mutation
        long s2 = stateMachine.apply(3, 1, CommandCodec.encodePut("b", bytes("2")));

        assertEquals(StateMachine.NON_MUTATING, poison, "poison entry is non-mutating");
        assertEquals(1L, s1, "first valid write commits at seq 1");
        assertEquals(2L, s2, "second valid write commits at seq 2 - the skipped entry consumed no seq");
        assertEquals(2, metrics.successCount.get(), "exactly the two valid writes committed");
        assertEquals(1, metrics.malformedCount.get());
        // And the store reflects both valid writes.
        assertTrue(stateMachine.store().get("a").found());
        assertTrue(stateMachine.store().get("b").found());
    }

    @Test
    void blankKeyPoisonPillIsSkippedNotThrown() {
        // Regression for the SECOND crash-loop path: a blank key surviving decode would throw a plain
        // IllegalArgumentException from new ConfigMutation.Put deep inside applySwitch. decode now
        // rejects blank keys, converting this into a deterministic skip.
        byte[] keyBytes = "   ".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + keyBytes.length + 4);
        buf.put(CommandCodec.TYPE_PUT);
        buf.putShort((short) keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(0);

        long result = assertDoesNotThrow(() -> stateMachine.apply(1, 1, buf.array()));
        assertEquals(StateMachine.NON_MUTATING, result);
        assertEquals(1, metrics.malformedCount.get());
    }

    @Test
    void restoreSnapshotFailureIncrementsInstallFailed() {
        // Craft a malformed envelope with a negative entry count so the
        // bounds check throws IllegalArgumentException, hitting the
        // onSnapshotInstallFailed branch.
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putLong(0L);    // sequence counter
        buf.putInt(-1);     // entry count - invalid
        byte[] malformed = buf.array();

        assertThrows(IllegalArgumentException.class,
                () -> stateMachine.restoreSnapshot(malformed));
        assertEquals(1, metrics.snapshotInstallFailedCount.get(),
                "malformed snapshot must increment install_failed counter");
        assertEquals(0, metrics.snapshotRebuildCount.get());
    }

    /** Real (non-mock) recording sink - same testing style as the other
     *  config-store tests use for InvariantChecker. */
    private static final class RecordingMetrics implements StateMachineMetrics {
        final AtomicInteger successCount = new AtomicInteger();
        final AtomicInteger failureCount = new AtomicInteger();
        final AtomicLong lastDurationNanos = new AtomicLong(-1);
        final AtomicInteger snapshotRebuildCount = new AtomicInteger();
        final AtomicInteger snapshotInstallFailedCount = new AtomicInteger();
        final AtomicInteger malformedCount = new AtomicInteger();

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
        @Override public void onMalformedCommittedCommand() {
            malformedCount.incrementAndGet();
        }
    }
}
