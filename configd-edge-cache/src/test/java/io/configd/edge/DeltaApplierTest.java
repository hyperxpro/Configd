package io.configd.edge;

import io.configd.common.Clock;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSigner;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DeltaApplier} - gap detection and delta sequencing.
 */
class DeltaApplierTest {

    /**
     * Simple test clock with explicit time control.
     */
    static class TestClock implements Clock {
        long timeMs;

        TestClock(long initial) {
            this.timeMs = initial;
        }

        @Override
        public long currentTimeMillis() {
            return timeMs;
        }

        @Override
        public long nanoTime() {
            return timeMs * 1_000_000L;
        }

        void advance(long ms) {
            timeMs += ms;
        }
    }

    private TestClock clock;
    private EdgeConfigClient client;
    private DeltaApplier applier;

    @BeforeEach
    void setUp() {
        clock = new TestClock(10_000);
        client = new EdgeConfigClient(clock);
        client.loadSnapshot(buildSnapshot(0));
        applier = new DeltaApplier(client);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ConfigSnapshot buildSnapshot(long version, String... keyValues) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i < keyValues.length; i += 2) {
            data = data.put(keyValues[i],
                    new VersionedValue(bytes(keyValues[i + 1]), version, version));
        }
        return new ConfigSnapshot(data, version, version);
    }

    // -----------------------------------------------------------------------
    // Successful application
    // -----------------------------------------------------------------------

    @Nested
    class SuccessfulApplication {

        @Test
        void applyMatchingDelta() {
            ConfigDelta delta = new ConfigDelta(0, 1, List.of(
                    new ConfigMutation.Put("key", bytes("value"))
            ));

            DeltaApplier.ApplyResult result = applier.offer(delta, clock.currentTimeMillis());

            assertEquals(DeltaApplier.ApplyResult.APPLIED, result);
            assertEquals(1, client.currentVersion());
            assertEquals(1, applier.lastAppliedVersion());
            assertFalse(applier.pendingGap());
        }

        @Test
        void applySequentialDeltas() {
            for (int i = 1; i <= 5; i++) {
                ConfigDelta delta = new ConfigDelta(i - 1, i, List.of(
                        new ConfigMutation.Put("key-" + i, bytes("val-" + i))
                ));
                assertEquals(DeltaApplier.ApplyResult.APPLIED, applier.offer(delta, clock.currentTimeMillis()));
            }

            assertEquals(5, client.currentVersion());
            assertEquals(5, applier.lastAppliedVersion());
            assertFalse(applier.pendingGap());
        }
    }

    // -----------------------------------------------------------------------
    // Gap detection
    // -----------------------------------------------------------------------

    @Nested
    class GapDetection {

        @Test
        void gapDetectedWhenFromVersionMismatches() {
            // Current version is 0, delta starts from 5
            ConfigDelta delta = new ConfigDelta(5, 6, List.of(
                    new ConfigMutation.Put("key", bytes("value"))
            ));

            DeltaApplier.ApplyResult result = applier.offer(delta, clock.currentTimeMillis());

            assertEquals(DeltaApplier.ApplyResult.GAP_DETECTED, result);
            assertTrue(applier.pendingGap());
            assertEquals(0, client.currentVersion()); // Store unchanged
        }

        @Test
        void gapDetectedOnForwardJump() {
            // Apply one delta successfully
            applier.offer(new ConfigDelta(0, 1, List.of(
                    new ConfigMutation.Put("a", bytes("1"))
            )), clock.currentTimeMillis());

            // Skip version 2, jump to 3
            ConfigDelta delta = new ConfigDelta(2, 3, List.of(
                    new ConfigMutation.Put("b", bytes("2"))
            ));

            assertEquals(DeltaApplier.ApplyResult.GAP_DETECTED, applier.offer(delta, clock.currentTimeMillis()));
            assertTrue(applier.pendingGap());
            assertEquals(1, client.currentVersion());
        }

        @Test
        void resetGapAfterFullSync() {
            // Trigger gap
            applier.offer(new ConfigDelta(5, 6, List.of(
                    new ConfigMutation.Put("key", bytes("value"))
            )), clock.currentTimeMillis());
            assertTrue(applier.pendingGap());

            // Load full snapshot to recover
            client.loadSnapshot(buildSnapshot(10, "key", "value"));
            applier.resetGap();

            assertFalse(applier.pendingGap());
            assertEquals(10, applier.lastAppliedVersion());
        }

        @Test
        void afterGapResetCanApplyDeltasNormally() {
            // Trigger gap
            applier.offer(new ConfigDelta(5, 6, List.of(
                    new ConfigMutation.Put("key", bytes("value"))
            )), clock.currentTimeMillis());

            // Recover with full sync
            client.loadSnapshot(buildSnapshot(10, "key", "v10"));
            applier.resetGap();

            // Now apply delta from version 10
            ConfigDelta delta = new ConfigDelta(10, 11, List.of(
                    new ConfigMutation.Put("key", bytes("v11"))
            ));

            assertEquals(DeltaApplier.ApplyResult.APPLIED, applier.offer(delta, clock.currentTimeMillis()));
            assertEquals(11, client.currentVersion());
            assertFalse(applier.pendingGap());
        }
    }

    // -----------------------------------------------------------------------
    // Stale delta detection
    // -----------------------------------------------------------------------

    @Nested
    class StaleDeltaDetection {

        @Test
        void staleDeltaWhenToVersionBehindCurrent() {
            // Load snapshot at version 5
            client.loadSnapshot(buildSnapshot(5, "key", "value"));
            applier = new DeltaApplier(client);

            // Delta targeting version 3 (behind current)
            ConfigDelta delta = new ConfigDelta(2, 3, List.of(
                    new ConfigMutation.Put("old", bytes("data"))
            ));

            assertEquals(DeltaApplier.ApplyResult.STALE_DELTA, applier.offer(delta, clock.currentTimeMillis()));
            assertEquals(5, client.currentVersion()); // Unchanged
            assertFalse(applier.pendingGap());
        }

        @Test
        void staleDeltaWhenToVersionEqualsCurrent() {
            client.loadSnapshot(buildSnapshot(5, "key", "value"));
            applier = new DeltaApplier(client);

            // Delta targeting version 5 (equal to current)
            ConfigDelta delta = new ConfigDelta(4, 5, List.of(
                    new ConfigMutation.Put("dup", bytes("data"))
            ));

            assertEquals(DeltaApplier.ApplyResult.STALE_DELTA, applier.offer(delta, clock.currentTimeMillis()));
        }
    }

    // -----------------------------------------------------------------------
    // Initial state
    // -----------------------------------------------------------------------

    @Nested
    class InitialState {

        @Test
        void initialLastAppliedVersionMatchesClient() {
            assertEquals(0, applier.lastAppliedVersion());
        }

        @Test
        void noPendingGapInitially() {
            assertFalse(applier.pendingGap());
        }

        @Test
        void initialLastAppliedVersionFromNonZeroClient() {
            client.loadSnapshot(buildSnapshot(42, "key", "value"));
            DeltaApplier newApplier = new DeltaApplier(client);
            assertEquals(42, newApplier.lastAppliedVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Null safety
    // -----------------------------------------------------------------------

    @Nested
    class NullSafety {

        @Test
        void nullClientThrows() {
            assertThrows(NullPointerException.class, () -> new DeltaApplier(null));
        }

        @Test
        void nullDeltaThrows() {
            assertThrows(NullPointerException.class, () -> applier.offer(null, clock.currentTimeMillis()));
        }
    }

    // -----------------------------------------------------------------------
    // Signature verification
    // -----------------------------------------------------------------------

    @Nested
    class SignatureVerification {

        private KeyPair keyPair;
        private ConfigSigner leaderSigner;

        @BeforeEach
        void setUpKeys() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            keyPair = gen.generateKeyPair();
            leaderSigner = new ConfigSigner(keyPair);
        }

        /**
         * Helper: builds a leader-signed delta at a real epoch ({@code > 0}), signing the same
         * {@link ConfigDelta#signingPayload()} the edge verifier reconstructs - canonical
         * mutations, the version position ({@code fromVersion}, {@code toVersion}), the epoch,
         * and the nonce (ADR-0044). Production never emits a signed epoch-0 delta.
         */
        private ConfigDelta signedDelta(long fromV, long toV, List<ConfigMutation> mutations,
                                        long epoch) throws Exception {
            byte[] nonce = new byte[]{1, 2, 3, 4, 5, 6, 7, (byte) epoch};
            ConfigDelta unsigned = new ConfigDelta(fromV, toV, mutations, null, epoch, nonce);
            byte[] sig = leaderSigner.sign(unsigned.signingPayload());
            return new ConfigDelta(fromV, toV, mutations, sig, epoch, nonce);
        }

        @Test
        void deltaWithValidSignatureIsApplied() throws Exception {
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            DeltaApplier verifyingApplier = new DeltaApplier(client, verifier);

            ConfigDelta signedDelta = signedDelta(0, 1, List.of(
                    new ConfigMutation.Put("key", bytes("value"))), 1L);

            DeltaApplier.ApplyResult result = verifyingApplier.offer(signedDelta, clock.currentTimeMillis());

            assertEquals(DeltaApplier.ApplyResult.APPLIED, result);
            assertEquals(1, client.currentVersion());
        }

        @Test
        void deltaWithInvalidSignatureIsRejected() throws Exception {
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            DeltaApplier verifyingApplier = new DeltaApplier(client, verifier);

            // A well-formed signed (epoch > 0) delta whose signature bytes are corrupt.
            byte[] badSignature = new byte[64]; // all zeros - invalid
            ConfigDelta delta = new ConfigDelta(0, 1, List.of(
                    new ConfigMutation.Put("key", bytes("value"))), badSignature, 1L,
                    new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

            DeltaApplier.ApplyResult result = verifyingApplier.offer(delta, clock.currentTimeMillis());

            assertEquals(DeltaApplier.ApplyResult.SIGNATURE_INVALID, result);
            assertEquals(0, client.currentVersion()); // not applied
        }

        @Test
        void signedDeltaWithEpochZeroIsRejected() throws Exception {
            // Defense-in-depth (ADR-0044): the leader always signs with epoch > 0, so a
            // signature carried on an epoch-0 delta is not a shape production emits. The edge
            // rejects it rather than fall back to the legacy batch-only verification form.
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            DeltaApplier verifyingApplier = new DeltaApplier(client, verifier);

            List<ConfigMutation> mutations = List.of(new ConfigMutation.Put("key", bytes("value")));
            // Sign the legacy (batch-only) payload and carry it on an epoch-0 delta.
            byte[] legacySig = leaderSigner.sign(CommandCodec.encodeBatch(mutations));
            ConfigDelta signedEpoch0 = new ConfigDelta(0, 1, mutations, legacySig);

            DeltaApplier.ApplyResult result =
                    verifyingApplier.offer(signedEpoch0, clock.currentTimeMillis());

            assertEquals(DeltaApplier.ApplyResult.SIGNATURE_INVALID, result);
            assertEquals(0, client.currentVersion());
        }

        @Test
        void rewrittenVersionPositionFailsVerification() throws Exception {
            // Track 0 red-team regression: the version position is inside the signature, so a
            // relay that rewrites fromVersion/toVersion to splice a delta out of the chain
            // breaks verification (the property the anti-suppression claim used to lean on TLS
            // for; ADR-0044).
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            DeltaApplier verifyingApplier = new DeltaApplier(client, verifier);

            List<ConfigMutation> mutations = List.of(new ConfigMutation.Put("key", bytes("value")));
            ConfigDelta signed = signedDelta(0, 1, mutations, 1L);
            // Rewrite the wire position (5 -> 6) while keeping the same signature/epoch/nonce.
            ConfigDelta tampered = new ConfigDelta(5, 6, mutations,
                    signed.signature(), signed.epoch(), signed.nonce());

            DeltaApplier.ApplyResult result =
                    verifyingApplier.offer(tampered, clock.currentTimeMillis());

            assertEquals(DeltaApplier.ApplyResult.SIGNATURE_INVALID, result);
        }

        @Test
        void unsignedDeltaIsRejectedWhenVerifierConfigured() {
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            DeltaApplier verifyingApplier = new DeltaApplier(client, verifier);

            ConfigDelta delta = new ConfigDelta(0, 1, List.of(
                    new ConfigMutation.Put("key", bytes("value"))
            )); // no signature

            DeltaApplier.ApplyResult result = verifyingApplier.offer(delta, clock.currentTimeMillis());

            assertEquals(DeltaApplier.ApplyResult.UNSIGNED_REJECTED, result);
            assertEquals(0, client.currentVersion());
        }

        @Test
        void deltaWithSignatureFromWrongKeyIsRejected() throws Exception {
            // Generate a different key pair
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair otherKeyPair = gen.generateKeyPair();
            ConfigSigner wrongSigner = new ConfigSigner(otherKeyPair);

            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            DeltaApplier verifyingApplier = new DeltaApplier(client, verifier);

            List<ConfigMutation> mutations = List.of(new ConfigMutation.Put("key", bytes("value")));
            byte[] nonce = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            // Sign the correct (position + epoch + nonce) payload but with the wrong key.
            ConfigDelta unsigned = new ConfigDelta(0, 1, mutations, null, 1L, nonce);
            byte[] sig = wrongSigner.sign(unsigned.signingPayload());
            ConfigDelta delta = new ConfigDelta(0, 1, mutations, sig, 1L, nonce);

            DeltaApplier.ApplyResult result = verifyingApplier.offer(delta, clock.currentTimeMillis());

            assertEquals(DeltaApplier.ApplyResult.SIGNATURE_INVALID, result);
            assertEquals(0, client.currentVersion());
        }

        @Test
        void noVerifierAllowsUnsignedDeltas() {
            // The default applier (no verifier) should work as before
            ConfigDelta delta = new ConfigDelta(0, 1, List.of(
                    new ConfigMutation.Put("key", bytes("value"))
            ));

            DeltaApplier.ApplyResult result = applier.offer(delta, clock.currentTimeMillis());

            assertEquals(DeltaApplier.ApplyResult.APPLIED, result);
            assertEquals(1, client.currentVersion());
        }

        /**
         * Regression test for FIND-0004: a single-mutation delta signed by
         * ConfigStateMachine must verify at the edge via DeltaApplier.
         * Before the fix, single PUTs were signed as [0x01][...] on the leader
         * but verified as [0x03][1][0x01][...] on the edge, causing mismatch.
         */
        @Test
        void find0004_singleMutationSignedByLeaderVerifiesAtEdge() throws Exception {
            // Simulate the leader: ConfigStateMachine signs in canonical batch form
            io.configd.store.ConfigStateMachine leaderSm = new io.configd.store.ConfigStateMachine(
                    new io.configd.store.VersionedConfigStore(), clock, leaderSigner);
            byte[] putCommand = CommandCodec.encodePut("db.host", bytes("localhost"));
            leaderSm.apply(1, 1, putCommand);
            byte[] leaderSig = leaderSm.lastSignature();
            assertNotNull(leaderSig);

            // Construct the delta as the distribution service would: propagate the
            // leader's epoch + nonce so the edge reconstructs the identical signing payload.
            ConfigDelta delta = new ConfigDelta(0, 1, List.of(
                    new ConfigMutation.Put("db.host", bytes("localhost"))
            ), leaderSig, leaderSm.lastEpoch(), leaderSm.lastNonce());

            // Edge verifier with only the public key
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            DeltaApplier verifyingApplier = new DeltaApplier(client, verifier);

            DeltaApplier.ApplyResult result = verifyingApplier.offer(delta, clock.currentTimeMillis());
            assertEquals(DeltaApplier.ApplyResult.APPLIED, result,
                    "Single-mutation delta signed by leader must verify at edge");
        }

        @Test
        void sequentialSignedDeltasApply() throws Exception {
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            DeltaApplier verifyingApplier = new DeltaApplier(client, verifier);

            for (int i = 1; i <= 3; i++) {
                ConfigDelta signedDelta = signedDelta(i - 1, i, List.of(
                        new ConfigMutation.Put("key-" + i, bytes("val-" + i))), i);

                assertEquals(DeltaApplier.ApplyResult.APPLIED,
                        verifyingApplier.offer(signedDelta, clock.currentTimeMillis()));
            }

            assertEquals(3, client.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Epoch persistence across process restart
    // -----------------------------------------------------------------------

    /**
     * Before the epoch sidecar, the highest-seen-epoch counter lived only in memory,
     * so a process restart let an attacker re-deliver an older leader-signed delta
     * with a smaller epoch and have it accepted as fresh. The sidecar closes this gap.
     */
    @Nested
    class EpochPersistence {

        @TempDir
        Path snapshotDir;

        private KeyPair keyPair;
        private ConfigSigner leaderSigner;
        private ConfigSigner verifier;

        @BeforeEach
        void setUpKeys() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            keyPair = gen.generateKeyPair();
            leaderSigner = new ConfigSigner(keyPair);
            verifier = new ConfigSigner(keyPair.getPublic());
        }

        /** Builds a delta signed under the given epoch (and an empty nonce). */
        private ConfigDelta signedDelta(long fromV, long toV, long epoch) throws Exception {
            ConfigDelta unsigned = new ConfigDelta(fromV, toV, List.of(
                    new ConfigMutation.Put("key-" + epoch, bytes("v-" + epoch))
            ));
            // Construct a delta carrying the epoch and an empty nonce so
            // signingPayload() includes them in the canonical bytes.
            ConfigDelta withEpoch = new ConfigDelta(fromV, toV,
                    unsigned.mutations(), null, epoch, new byte[0]);
            byte[] sig = leaderSigner.sign(withEpoch.signingPayload());
            return new ConfigDelta(fromV, toV, unsigned.mutations(), sig, epoch, new byte[0]);
        }

        @Test
        void persistsEpochAfterApply() throws Exception {
            DeltaApplier persistingApplier =
                    new DeltaApplier(client, verifier, snapshotDir);
            assertEquals(0L, persistingApplier.highestSeenEpoch());

            DeltaApplier.ApplyResult r = persistingApplier.offer(signedDelta(0, 1, 100L), clock.currentTimeMillis());
            assertEquals(DeltaApplier.ApplyResult.APPLIED, r);
            assertEquals(100L, persistingApplier.highestSeenEpoch());

            // Sidecar exists, is exactly 12 bytes, and CRC32C-validates.
            Path lock = snapshotDir.resolve("epoch.lock");
            assertTrue(Files.exists(lock), "epoch.lock must be persisted after apply");
            byte[] data = Files.readAllBytes(lock);
            assertEquals(12, data.length, "epoch.lock must be 8B epoch + 4B CRC");
            ByteBuffer buf = ByteBuffer.wrap(data);
            assertEquals(100L, buf.getLong(), "persisted epoch must match");
            int storedCrc = buf.getInt();
            CRC32C crc = new CRC32C();
            crc.update(data, 0, 8);
            assertEquals((int) crc.getValue(), storedCrc, "CRC32C must verify");
        }

        @Test
        void epochReplayRejectedAcrossRestart() throws Exception {
            // Write epoch 100 and persist it.
            DeltaApplier first = new DeltaApplier(client, verifier, snapshotDir);
            assertEquals(DeltaApplier.ApplyResult.APPLIED,
                    first.offer(signedDelta(0, 1, 100L), clock.currentTimeMillis()));
            assertEquals(100L, first.highestSeenEpoch());

            // Simulate process restart: brand-new EdgeConfigClient and brand-new DeltaApplier
            // reading the same snapshot dir. Without the sidecar the new applier would start
            // with highestSeenEpoch=0 and accept a replay at epoch=42.
            EdgeConfigClient client2 = new EdgeConfigClient(clock);
            client2.loadSnapshot(buildSnapshot(0));
            DeltaApplier restarted = new DeltaApplier(client2, verifier, snapshotDir);
            assertEquals(100L, restarted.highestSeenEpoch(),
                    "post-restart epoch must be loaded from sidecar");

            // The replay attempt: an attacker re-signs an older delta at
            // epoch 42 and offers it. It must be rejected.
            DeltaApplier.ApplyResult r = restarted.offer(signedDelta(0, 1, 42L), clock.currentTimeMillis());
            assertEquals(DeltaApplier.ApplyResult.REPLAY_REJECTED, r,
                    "stale-epoch delta must be rejected after restart");
            assertEquals(0L, client2.currentVersion(),
                    "store must be unchanged on replay rejection");
        }

        @Test
        void corruptSidecarTreatedAsAbsent() throws Exception {
            // Manually write a sidecar with a bad CRC.
            Path lock = snapshotDir.resolve("epoch.lock");
            ByteBuffer buf = ByteBuffer.allocate(12);
            buf.putLong(999L);
            buf.putInt(0xDEADBEEF); // wrong CRC
            Files.write(lock, buf.array());

            DeltaApplier applier2 = new DeltaApplier(client, verifier, snapshotDir);
            assertEquals(0L, applier2.highestSeenEpoch(),
                    "corrupt sidecar must be ignored (epoch resets to 0)");

            // The next applied delta overwrites the sidecar with valid bytes.
            assertEquals(DeltaApplier.ApplyResult.APPLIED,
                    applier2.offer(signedDelta(0, 1, 7L), clock.currentTimeMillis()));
            assertEquals(7L, applier2.highestSeenEpoch());

            byte[] data = Files.readAllBytes(lock);
            assertEquals(12, data.length);
            ByteBuffer rb = ByteBuffer.wrap(data);
            assertEquals(7L, rb.getLong());
            int storedCrc = rb.getInt();
            CRC32C crc = new CRC32C();
            crc.update(data, 0, 8);
            assertEquals((int) crc.getValue(), storedCrc);
        }

        @Test
        void nullSnapshotDirSkipsPersistence() throws Exception {
            // Defensive: the legacy two-arg constructor must not touch disk.
            DeltaApplier inMem = new DeltaApplier(client, verifier, null);
            assertEquals(DeltaApplier.ApplyResult.APPLIED,
                    inMem.offer(signedDelta(0, 1, 50L), clock.currentTimeMillis()));
            assertEquals(50L, inMem.highestSeenEpoch());
            // No file in snapshotDir was created (no persistence configured).
            assertFalse(Files.exists(snapshotDir.resolve("epoch.lock")));
        }
    }
}
