package io.configd.store;

import io.configd.common.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigStateMachine}.
 */
class ConfigStateMachineTest {

    private VersionedConfigStore store;
    private ConfigStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        store = new VersionedConfigStore();
        stateMachine = new ConfigStateMachine(store);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Apply — PUT
    // -----------------------------------------------------------------------

    @Nested
    class ApplyPut {

        @Test
        void applySinglePut() {
            byte[] command = CommandCodec.encodePut("db.host", bytes("localhost"));
            stateMachine.apply(1, 1, command);

            ReadResult result = store.get("db.host");
            assertTrue(result.found());
            assertArrayEquals(bytes("localhost"), result.value());
            assertEquals(1, store.currentVersion());
        }

        @Test
        void applyMultiplePuts() {
            stateMachine.apply(1, 1, CommandCodec.encodePut("a", bytes("1")));
            stateMachine.apply(2, 1, CommandCodec.encodePut("b", bytes("2")));
            stateMachine.apply(3, 1, CommandCodec.encodePut("c", bytes("3")));

            assertEquals(3, store.currentVersion());
            assertArrayEquals(bytes("1"), store.get("a").value());
            assertArrayEquals(bytes("2"), store.get("b").value());
            assertArrayEquals(bytes("3"), store.get("c").value());
        }

        @Test
        void putOverwritesExistingValue() {
            stateMachine.apply(1, 1, CommandCodec.encodePut("key", bytes("v1")));
            stateMachine.apply(2, 1, CommandCodec.encodePut("key", bytes("v2")));

            assertArrayEquals(bytes("v2"), store.get("key").value());
            assertEquals(2, store.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Apply — DELETE
    // -----------------------------------------------------------------------

    @Nested
    class ApplyDelete {

        @Test
        void deleteRemovesKey() {
            stateMachine.apply(1, 1, CommandCodec.encodePut("key", bytes("value")));
            stateMachine.apply(2, 1, CommandCodec.encodeDelete("key"));

            assertFalse(store.get("key").found());
            assertEquals(2, store.currentVersion());
        }

        @Test
        void deleteAbsentKeyIsNoOp() {
            stateMachine.apply(1, 1, CommandCodec.encodeDelete("absent"));

            assertFalse(store.get("absent").found());
            assertEquals(1, store.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Apply — BATCH
    // -----------------------------------------------------------------------

    @Nested
    class ApplyBatch {

        @Test
        void applyBatchAtomically() {
            List<ConfigMutation> mutations = List.of(
                    new ConfigMutation.Put("a", bytes("1")),
                    new ConfigMutation.Put("b", bytes("2")),
                    new ConfigMutation.Delete("c")
            );
            byte[] command = CommandCodec.encodeBatch(mutations);
            stateMachine.apply(1, 1, command);

            assertEquals(1, store.currentVersion());
            assertArrayEquals(bytes("1"), store.get("a").value());
            assertArrayEquals(bytes("2"), store.get("b").value());
            assertFalse(store.get("c").found());
        }

        @Test
        void batchVersionIsSingleBump() {
            // Apply two batches, each should get one version bump
            stateMachine.apply(1, 1, CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("x", bytes("1")),
                    new ConfigMutation.Put("y", bytes("2"))
            )));
            assertEquals(1, store.currentVersion());

            stateMachine.apply(2, 1, CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("z", bytes("3"))
            )));
            assertEquals(2, store.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Apply — NOOP
    // -----------------------------------------------------------------------

    @Nested
    class ApplyNoop {

        @Test
        void noopDoesNotChangeStore() {
            stateMachine.apply(1, 1, CommandCodec.encodePut("key", bytes("value")));
            long versionBefore = store.currentVersion();

            // Empty command = noop
            stateMachine.apply(2, 1, new byte[0]);

            assertEquals(versionBefore, store.currentVersion());
        }

        @Test
        void noopDoesNotIncrementSequenceCounter() {
            stateMachine.apply(1, 1, CommandCodec.encodePut("key", bytes("value")));
            long seqBefore = stateMachine.sequenceCounter();

            stateMachine.apply(2, 1, new byte[0]);

            assertEquals(seqBefore, stateMachine.sequenceCounter());
        }
    }

    // -----------------------------------------------------------------------
    // Sequence counter
    // -----------------------------------------------------------------------

    @Nested
    class SequenceCounter {

        @Test
        void sequenceCounterStartsAtZero() {
            assertEquals(0, stateMachine.sequenceCounter());
        }

        @Test
        void sequenceCounterIncrementsOnEachApply() {
            stateMachine.apply(1, 1, CommandCodec.encodePut("a", bytes("1")));
            assertEquals(1, stateMachine.sequenceCounter());

            stateMachine.apply(2, 1, CommandCodec.encodeDelete("a"));
            assertEquals(2, stateMachine.sequenceCounter());

            stateMachine.apply(3, 1, CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("b", bytes("2")))));
            assertEquals(3, stateMachine.sequenceCounter());
        }

        @Test
        void sequenceCounterMatchesStoreVersion() {
            stateMachine.apply(1, 1, CommandCodec.encodePut("a", bytes("1")));
            stateMachine.apply(2, 1, CommandCodec.encodePut("b", bytes("2")));

            assertEquals(stateMachine.sequenceCounter(), store.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot and restore
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotAndRestore {

        @Test
        void snapshotAndRestoreRoundTrip() {
            stateMachine.apply(1, 1, CommandCodec.encodePut("db.host", bytes("localhost")));
            stateMachine.apply(2, 1, CommandCodec.encodePut("db.port", bytes("5432")));
            stateMachine.apply(3, 1, CommandCodec.encodePut("cache.ttl", bytes("300")));

            byte[] snapshotBytes = stateMachine.snapshot();

            // Create a fresh store and state machine
            VersionedConfigStore newStore = new VersionedConfigStore();
            ConfigStateMachine newSm = new ConfigStateMachine(newStore);

            newSm.restoreSnapshot(snapshotBytes);

            // Verify all data restored
            assertArrayEquals(bytes("localhost"), newStore.get("db.host").value());
            assertArrayEquals(bytes("5432"), newStore.get("db.port").value());
            assertArrayEquals(bytes("300"), newStore.get("cache.ttl").value());

            // Sequence counter should be restored
            assertEquals(3, newSm.sequenceCounter());
        }

        @Test
        void restoreReplacesExistingState() {
            // Add some data to the original
            stateMachine.apply(1, 1, CommandCodec.encodePut("old", bytes("data")));
            byte[] snapshotBytes = stateMachine.snapshot();

            // Create another machine with different data
            VersionedConfigStore otherStore = new VersionedConfigStore();
            ConfigStateMachine otherSm = new ConfigStateMachine(otherStore);
            otherSm.apply(1, 1, CommandCodec.encodePut("different", bytes("stuff")));

            // Restore should replace entirely
            otherSm.restoreSnapshot(snapshotBytes);

            assertTrue(otherStore.get("old").found());
            // The "different" key should be gone because restore replaces state
            assertFalse(otherStore.get("different").found());
        }

        @Test
        void snapshotOfEmptyStoreRoundTrips() {
            byte[] snapshotBytes = stateMachine.snapshot();

            VersionedConfigStore newStore = new VersionedConfigStore();
            ConfigStateMachine newSm = new ConfigStateMachine(newStore);
            newSm.restoreSnapshot(snapshotBytes);

            assertEquals(0, newSm.sequenceCounter());
            assertEquals(0, newStore.currentVersion());
        }

        @Test
        void canApplyAfterRestore() {
            stateMachine.apply(1, 1, CommandCodec.encodePut("a", bytes("1")));
            byte[] snapshotBytes = stateMachine.snapshot();

            VersionedConfigStore newStore = new VersionedConfigStore();
            ConfigStateMachine newSm = new ConfigStateMachine(newStore);
            newSm.restoreSnapshot(snapshotBytes);

            // Should be able to apply new commands after restore
            newSm.apply(2, 1, CommandCodec.encodePut("b", bytes("2")));

            assertEquals(2, newStore.currentVersion());
            assertArrayEquals(bytes("1"), newStore.get("a").value());
            assertArrayEquals(bytes("2"), newStore.get("b").value());
        }
    }

    // -----------------------------------------------------------------------
    // Listener notification
    // -----------------------------------------------------------------------

    @Nested
    class ListenerNotification {

        @Test
        void listenerNotifiedOnPut() {
            List<List<ConfigMutation>> captured = new ArrayList<>();
            AtomicLong capturedVersion = new AtomicLong();

            stateMachine.addListener((mutations, version) -> {
                captured.add(mutations);
                capturedVersion.set(version);
            });

            stateMachine.apply(1, 1, CommandCodec.encodePut("key", bytes("value")));

            assertEquals(1, captured.size());
            assertEquals(1, captured.getFirst().size());
            assertInstanceOf(ConfigMutation.Put.class, captured.getFirst().getFirst());
            assertEquals("key", captured.getFirst().getFirst().key());
            assertEquals(1, capturedVersion.get());
        }

        @Test
        void listenerNotifiedOnDelete() {
            List<List<ConfigMutation>> captured = new ArrayList<>();

            stateMachine.addListener((mutations, version) -> captured.add(mutations));

            stateMachine.apply(1, 1, CommandCodec.encodeDelete("key"));

            assertEquals(1, captured.size());
            assertInstanceOf(ConfigMutation.Delete.class, captured.getFirst().getFirst());
        }

        @Test
        void listenerNotifiedOnBatch() {
            List<List<ConfigMutation>> captured = new ArrayList<>();

            stateMachine.addListener((mutations, version) -> captured.add(mutations));

            stateMachine.apply(1, 1, CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("a", bytes("1")),
                    new ConfigMutation.Delete("b")
            )));

            assertEquals(1, captured.size());
            assertEquals(2, captured.getFirst().size());
        }

        @Test
        void listenerNotNotifiedOnNoop() {
            AtomicInteger count = new AtomicInteger();
            stateMachine.addListener((mutations, version) -> count.incrementAndGet());

            stateMachine.apply(1, 1, new byte[0]);

            assertEquals(0, count.get());
        }

        @Test
        void multipleListenersNotifiedInOrder() {
            List<String> order = new ArrayList<>();

            stateMachine.addListener((mutations, version) -> order.add("first"));
            stateMachine.addListener((mutations, version) -> order.add("second"));
            stateMachine.addListener((mutations, version) -> order.add("third"));

            stateMachine.apply(1, 1, CommandCodec.encodePut("key", bytes("value")));

            assertEquals(List.of("first", "second", "third"), order);
        }

        @Test
        void removeListenerStopsNotification() {
            AtomicInteger count = new AtomicInteger();
            ConfigStateMachine.ConfigChangeListener listener =
                    (mutations, version) -> count.incrementAndGet();

            stateMachine.addListener(listener);
            stateMachine.apply(1, 1, CommandCodec.encodePut("a", bytes("1")));
            assertEquals(1, count.get());

            assertTrue(stateMachine.removeListener(listener));
            stateMachine.apply(2, 1, CommandCodec.encodePut("b", bytes("2")));
            assertEquals(1, count.get()); // not incremented
        }
    }

    // -----------------------------------------------------------------------
    // F-0013: Snapshot key length overflow for keys > 65535 bytes
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotKeyLengthOverflow {

        /**
         * Regression test for F-0013: snapshot/restoreSnapshot must handle keys
         * longer than 65535 bytes.
         * <p>
         * Before the fix, snapshot() used putShort for key length (max 65535)
         * and restoreSnapshot() used getShort. Keys longer than 65535 bytes
         * would have their length silently truncated, corrupting the snapshot
         * and making it impossible to restore.
         * <p>
         * After the fix, both use putInt/getInt (max ~2 billion bytes).
         * <p>
         * Note: The CommandCodec uses shorts for key lengths in its wire format,
         * so we cannot use apply() with a 70000-byte key. Instead, we put the
         * long key directly into the store (simulating what would happen if the
         * command codec also supported long keys, or if data arrived via snapshot
         * transfer from a node that already had such keys).
         */
        @Test
        void snapshotAndRestoreWithLongKey() {
            // Create a key that is 70000 bytes long (exceeds the old 65535 short limit)
            String longKey = "k".repeat(70_000);
            byte[] value = bytes("long-key-value");

            // Put the long key directly into the store, bypassing CommandCodec
            // (which has its own key-length limit). This is valid because the
            // snapshot format is the contract under test, not the command codec.
            store.put(longKey, value, 1);

            // Verify the data is in the store
            ReadResult result = store.get(longKey);
            assertTrue(result.found(), "Long key should be stored");
            assertArrayEquals(value, result.value());

            // Take a snapshot — before the fix, putShort would truncate the
            // key length to (70000 & 0xFFFF) = 4464, corrupting the snapshot.
            byte[] snapshotBytes = stateMachine.snapshot();
            assertNotNull(snapshotBytes);

            // Restore the snapshot into a fresh state machine
            VersionedConfigStore newStore = new VersionedConfigStore();
            ConfigStateMachine newSm = new ConfigStateMachine(newStore);

            // Before the fix: restoreSnapshot would either read the wrong number
            // of bytes (due to truncated key length) and corrupt all subsequent
            // fields, or throw a BufferUnderflowException.
            // After the fix: the 70000-byte key is correctly round-tripped.
            assertDoesNotThrow(() -> newSm.restoreSnapshot(snapshotBytes),
                    "restoreSnapshot must not throw for keys > 65535 bytes");

            // Verify the long key was correctly restored
            ReadResult restored = newStore.get(longKey);
            assertTrue(restored.found(),
                    "70000-byte key must be found after snapshot restore");
            assertArrayEquals(value, restored.value(),
                    "Value for 70000-byte key must match after snapshot restore");
        }
    }

    // -----------------------------------------------------------------------
    // F-0053: Snapshot restore must bound-check envelope fields
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotBoundsCheck {

        /**
         * Regression test for F-0053: restoreSnapshot must reject a malicious
         * or corrupted payload that claims an absurdly large entryCount,
         * rather than attempting a huge allocation (OOM).
         */
        @Test
        void restoreRejectsAbsurdEntryCount() {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(12);
            buf.putLong(0L);                 // sequence
            buf.putInt(Integer.MAX_VALUE);   // entryCount (must be rejected)
            byte[] malicious = buf.array();

            VersionedConfigStore s = new VersionedConfigStore();
            ConfigStateMachine sm = new ConfigStateMachine(s);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> sm.restoreSnapshot(malicious));
            assertTrue(ex.getMessage().contains("entryCount"),
                    "exception must mention entryCount; got: " + ex.getMessage());
        }

        @Test
        void restoreRejectsNegativeEntryCount() {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(12);
            buf.putLong(0L);
            buf.putInt(-1);
            byte[] malicious = buf.array();

            ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore());
            assertThrows(IllegalArgumentException.class,
                    () -> sm.restoreSnapshot(malicious));
        }

        @Test
        void restoreRejectsNegativeKeyLength() {
            // [seq=0][entryCount=1][keyLen=-1]...
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16);
            buf.putLong(0L);
            buf.putInt(1);
            buf.putInt(-1);
            byte[] malicious = buf.array();

            ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore());
            assertThrows(IllegalArgumentException.class,
                    () -> sm.restoreSnapshot(malicious));
        }

        @Test
        void restoreRejectsAbsurdKeyLength() {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16);
            buf.putLong(0L);
            buf.putInt(1);
            buf.putInt(Integer.MAX_VALUE);
            byte[] malicious = buf.array();

            ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore());
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> sm.restoreSnapshot(malicious));
            assertTrue(ex.getMessage().contains("keyLen"),
                    "exception must mention keyLen; got: " + ex.getMessage());
        }

        @Test
        void restoreRejectsTruncatedKeyBytes() {
            // Claims keyLen=100 but buffer has no key bytes after header.
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16);
            buf.putLong(0L);
            buf.putInt(1);
            buf.putInt(100);
            byte[] malicious = buf.array();

            ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore());
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> sm.restoreSnapshot(malicious));
            assertTrue(ex.getMessage().contains("truncated"),
                    "exception must mention truncated; got: " + ex.getMessage());
        }

        @Test
        void restoreRejectsAbsurdValueLength() {
            // [seq=0][entryCount=1][keyLen=1]["A"][valueLen=MAX]
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(21);
            buf.putLong(0L);
            buf.putInt(1);
            buf.putInt(1);
            buf.put((byte) 'A');
            buf.putInt(Integer.MAX_VALUE);
            byte[] malicious = buf.array();

            ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore());
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> sm.restoreSnapshot(malicious));
            assertTrue(ex.getMessage().contains("valueLen"),
                    "exception must mention valueLen; got: " + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Constructor validation
    // -----------------------------------------------------------------------

    @Nested
    class ConstructorValidation {

        @Test
        void nullStoreThrows() {
            assertThrows(NullPointerException.class,
                    () -> new ConfigStateMachine(null));
        }

        @Test
        void storeAccessor() {
            assertSame(store, stateMachine.store());
        }

        @Test
        void initializesSequenceFromStoreVersion() {
            store.put("key", bytes("value"), 42);
            ConfigStateMachine sm = new ConfigStateMachine(store);
            assertEquals(42, sm.sequenceCounter());
        }
    }

    // -----------------------------------------------------------------------
    // Signing integration
    // -----------------------------------------------------------------------

    @Nested
    class SigningIntegration {

        private KeyPair keyPair;
        private ConfigSigner signer;

        @BeforeEach
        void setUpSigner() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            keyPair = gen.generateKeyPair();
            signer = new ConfigSigner(keyPair);
        }

        @Test
        void lastSignatureNullWithoutSigner() {
            // Default state machine (no signer) should always return null
            assertNull(stateMachine.lastSignature());
            stateMachine.apply(1, 1, CommandCodec.encodePut("key", bytes("value")));
            assertNull(stateMachine.lastSignature());
        }

        @Test
        void lastSignatureNonNullAfterPutWithSigner() {
            ConfigStateMachine sm = new ConfigStateMachine(
                    new VersionedConfigStore(), Clock.system(), signer);

            sm.apply(1, 1, CommandCodec.encodePut("db.host", bytes("localhost")));

            byte[] sig = sm.lastSignature();
            assertNotNull(sig, "lastSignature() should be non-null after PUT with signer");
            assertTrue(sig.length > 0, "signature should have non-zero length");
        }

        @Test
        void lastSignatureNonNullAfterDeleteWithSigner() {
            ConfigStateMachine sm = new ConfigStateMachine(
                    new VersionedConfigStore(), Clock.system(), signer);

            sm.apply(1, 1, CommandCodec.encodePut("key", bytes("value")));
            sm.apply(2, 1, CommandCodec.encodeDelete("key"));

            byte[] sig = sm.lastSignature();
            assertNotNull(sig, "lastSignature() should be non-null after DELETE with signer");
        }

        @Test
        void lastSignatureNonNullAfterBatchWithSigner() {
            ConfigStateMachine sm = new ConfigStateMachine(
                    new VersionedConfigStore(), Clock.system(), signer);

            sm.apply(1, 1, CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("a", bytes("1")),
                    new ConfigMutation.Put("b", bytes("2"))
            )));

            byte[] sig = sm.lastSignature();
            assertNotNull(sig, "lastSignature() should be non-null after BATCH with signer");
        }

        @Test
        void signatureVerifiesWithPublicKey() throws Exception {
            ConfigStateMachine sm = new ConfigStateMachine(
                    new VersionedConfigStore(), Clock.system(), signer);

            byte[] command = CommandCodec.encodePut("db.host", bytes("localhost"));
            sm.apply(1, 1, command);

            byte[] sig = sm.lastSignature();
            assertNotNull(sig);

            // Verify using a verify-only signer with just the public key.
            // F-0052: signature is over canonical batch form bound with the
            // epoch and nonce — the verifier must reconstruct the same
            // payload. This mirrors ConfigDelta.signingPayload(), which is
            // what DeltaApplier.buildVerificationPayload uses.
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            byte[] canonical = CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("db.host", bytes("localhost"))));
            byte[] payload = buildPayload(canonical, sm.lastEpoch(), sm.lastNonce());
            assertTrue(verifier.verify(payload, sig),
                    "Signature should verify against canonical batch-encoded form bound with epoch+nonce");

            // The raw command bytes should NOT verify — this was the FIND-0004 bug.
            assertFalse(verifier.verify(command, sig),
                    "Signature must NOT verify against raw PUT-encoded bytes");
        }

        private byte[] buildPayload(byte[] canonical, long epoch, byte[] nonce) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(canonical.length + Long.BYTES + nonce.length);
            buf.put(canonical);
            buf.putLong(epoch);
            buf.put(nonce);
            return buf.array();
        }

        @Test
        void signatureVerifiesForBatchCommand() throws Exception {
            ConfigStateMachine sm = new ConfigStateMachine(
                    new VersionedConfigStore(), Clock.system(), signer);

            byte[] command = CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("a", bytes("1")),
                    new ConfigMutation.Delete("b")));
            sm.apply(1, 1, command);

            byte[] sig = sm.lastSignature();
            assertNotNull(sig);

            // Canonical form of a batch bound with epoch+nonce should verify (F-0052).
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            byte[] canonical = CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("a", bytes("1")),
                    new ConfigMutation.Delete("b")));
            byte[] payload = buildPayload(canonical, sm.lastEpoch(), sm.lastNonce());
            assertTrue(verifier.verify(payload, sig),
                    "Batch signature should verify against re-encoded batch bound with epoch+nonce");
        }

        @Test
        void singleMutationBatchSignatureMatchesSinglePutCanonical() throws Exception {
            // FIND-0004: a batch with one mutation must produce a signature that
            // verifies against the same canonical mutations as a standalone PUT.
            // F-0052: signatures are now also bound to epoch+nonce, so each
            // state-machine instance produces a different signature envelope —
            // but the mutation encoding is still byte-identical.
            ConfigStateMachine smBatch = new ConfigStateMachine(
                    new VersionedConfigStore(), Clock.system(), signer);
            ConfigStateMachine smPut = new ConfigStateMachine(
                    new VersionedConfigStore(), Clock.system(), signer);

            byte[] batchCmd = CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("key", bytes("val"))));
            byte[] putCmd = CommandCodec.encodePut("key", bytes("val"));

            smBatch.apply(1, 1, batchCmd);
            smPut.apply(1, 1, putCmd);

            byte[] batchSig = smBatch.lastSignature();
            byte[] putSig = smPut.lastSignature();
            assertNotNull(batchSig);
            assertNotNull(putSig);

            // Both should verify against canonical form bound with each
            // state machine's own (epoch, nonce).
            ConfigSigner verifier = new ConfigSigner(keyPair.getPublic());
            byte[] canonical = CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("key", bytes("val"))));
            assertTrue(verifier.verify(
                    buildPayload(canonical, smBatch.lastEpoch(), smBatch.lastNonce()),
                    batchSig));
            assertTrue(verifier.verify(
                    buildPayload(canonical, smPut.lastEpoch(), smPut.lastNonce()),
                    putSig));
        }

        @Test
        void signatureChangesPerApply() {
            ConfigStateMachine sm = new ConfigStateMachine(
                    new VersionedConfigStore(), Clock.system(), signer);

            sm.apply(1, 1, CommandCodec.encodePut("a", bytes("1")));
            byte[] sig1 = sm.lastSignature();

            sm.apply(2, 1, CommandCodec.encodePut("b", bytes("2")));
            byte[] sig2 = sm.lastSignature();

            assertNotNull(sig1);
            assertNotNull(sig2);
            // Different commands should produce different signatures
            assertFalse(java.util.Arrays.equals(sig1, sig2),
                    "Different commands should produce different signatures");
        }

        @Test
        void noopDoesNotUpdateLastSignature() {
            ConfigStateMachine sm = new ConfigStateMachine(
                    new VersionedConfigStore(), Clock.system(), signer);

            // Before any apply, lastSignature is null
            assertNull(sm.lastSignature());

            // Noop should not change lastSignature
            sm.apply(1, 1, new byte[0]);
            assertNull(sm.lastSignature());

            // After a real apply, signature should exist
            sm.apply(2, 1, CommandCodec.encodePut("key", bytes("value")));
            byte[] sig = sm.lastSignature();
            assertNotNull(sig);

            // Another noop should not clear the signature
            sm.apply(3, 1, new byte[0]);
            assertArrayEquals(sig, sm.lastSignature());
        }
    }

    // -----------------------------------------------------------------------
    // SEC-018 (iter-2): sign-then-mutate-then-fanout ordering — a sign
    // failure must leave the store untouched and listeners unfired.
    // -----------------------------------------------------------------------

    /**
     * Closes the gap that motivated SEC-018: previously, the apply loop
     * mutated the store first, then called {@code signCommand} which
     * silently swallowed any {@link java.security.GeneralSecurityException}.
     * That allowed a partially-applied write to be committed to the store
     * while no signature was produced — so the unsigned delta was either
     * broadcast and rejected at the edge (gap loop) or was lost entirely
     * while the leader believed the mutation had been published. Reordering
     * sign → mutate → fanout, with a propagating exception on sign failure,
     * makes apply atomic from the outside: either everything happens
     * (store mutation + lastSignature populated + listeners notified) or
     * nothing does (store unchanged + IllegalStateException propagates +
     * no listener notification).
     */
    @Nested
    class SignFailurePreservesStore {

        /**
         * Verify-only ConfigSigner: constructed with the public key only,
         * so any sign() call throws IllegalStateException with the message
         * documented on ConfigSigner. This is the production failure mode
         * an operator hits if they accidentally configure the verify-only
         * key on a leader.
         */
        private ConfigSigner verifyOnlySigner() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = gen.generateKeyPair();
            return new ConfigSigner(kp.getPublic());
        }

        @Test
        void signFailureLeavesStoreUnmutated() throws Exception {
            VersionedConfigStore localStore = new VersionedConfigStore();
            ConfigSigner badSigner = verifyOnlySigner();
            ConfigStateMachine sm = new ConfigStateMachine(
                    localStore, Clock.system(), badSigner);

            // Listener fanout MUST also be skipped on sign failure.
            List<List<ConfigMutation>> fanoutLog = new ArrayList<>();
            sm.addListener((mutations, version) -> fanoutLog.add(mutations));

            byte[] command = CommandCodec.encodePut("k", bytes("v"));

            // (a) The IllegalStateException must propagate — silent failure
            //     was the historical bug.
            assertThrows(IllegalStateException.class,
                    () -> sm.apply(1, 1, command),
                    "sign failure must propagate so Raft can panic / retry");

            // (b) Store must NOT contain the key — the previous order
            //     (mutate then sign) would have left it partially applied.
            assertFalse(localStore.get("k").found(),
                    "store must be unmutated after sign failure (SEC-018)");
            assertEquals(0L, localStore.currentVersion(),
                    "version must not advance on aborted apply");

            // (c) Sequence counter must NOT have advanced — a second apply
            //     attempt with seq=1 would otherwise hit a gap.
            assertEquals(0L, sm.sequenceCounter(),
                    "sequence counter must not advance on aborted apply");

            // (d) lastSignature MUST remain null (no half-state).
            assertNull(sm.lastSignature(),
                    "lastSignature must remain null on aborted apply");
            assertEquals(0L, sm.lastEpoch());
            assertNull(sm.lastNonce());

            // (e) Listeners MUST NOT have been called — fanout-after-mutate
            //     would otherwise leak the unsigned mutation downstream.
            assertTrue(fanoutLog.isEmpty(),
                    "listeners must not be notified for an aborted apply (SEC-018)");
        }

        @Test
        void signFailureOnDeleteLeavesStoreUnmutated() throws Exception {
            VersionedConfigStore localStore = new VersionedConfigStore();
            // Pre-seed with a key so DELETE has something to remove.
            localStore.put("k", bytes("preexisting"), 1L);

            ConfigSigner badSigner = verifyOnlySigner();
            ConfigStateMachine sm = new ConfigStateMachine(
                    localStore, Clock.system(), badSigner);

            byte[] command = CommandCodec.encodeDelete("k");
            assertThrows(IllegalStateException.class,
                    () -> sm.apply(2, 1, command));

            // The pre-existing value MUST still be present.
            ReadResult after = localStore.get("k");
            assertTrue(after.found(), "DELETE must not be applied on sign failure");
            assertArrayEquals(bytes("preexisting"), after.value());
        }

        @Test
        void signFailureOnBatchLeavesStoreUnmutated() throws Exception {
            VersionedConfigStore localStore = new VersionedConfigStore();
            ConfigSigner badSigner = verifyOnlySigner();
            ConfigStateMachine sm = new ConfigStateMachine(
                    localStore, Clock.system(), badSigner);

            byte[] command = CommandCodec.encodeBatch(List.of(
                    new ConfigMutation.Put("a", bytes("1")),
                    new ConfigMutation.Put("b", bytes("2")),
                    new ConfigMutation.Delete("c")));

            assertThrows(IllegalStateException.class,
                    () -> sm.apply(3, 1, command));

            // None of the batch's mutations may be visible — atomicity
            // is the whole point of the batch encoding.
            assertFalse(localStore.get("a").found());
            assertFalse(localStore.get("b").found());
            assertEquals(0L, localStore.currentVersion());
        }

        @Test
        void successfulSignStillProducesNotificationAndMutation() throws Exception {
            // Sanity: the reorder must not regress the happy path.
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = gen.generateKeyPair();
            ConfigSigner okSigner = new ConfigSigner(kp);

            VersionedConfigStore localStore = new VersionedConfigStore();
            ConfigStateMachine sm = new ConfigStateMachine(
                    localStore, Clock.system(), okSigner);
            AtomicLong notifiedVersion = new AtomicLong(-1);
            sm.addListener((muts, v) -> notifiedVersion.set(v));

            sm.apply(1, 1, CommandCodec.encodePut("ok", bytes("v")));
            assertTrue(localStore.get("ok").found());
            assertEquals(1L, localStore.currentVersion());
            assertEquals(1L, sm.sequenceCounter());
            assertNotNull(sm.lastSignature());
            assertEquals(1L, notifiedVersion.get(),
                    "listener must fire on successful apply (happy path unchanged)");
        }
    }

    // -----------------------------------------------------------------------
    // R-002 (iter-2): TLV snapshot trailer — extensible, backward-compatible.
    // The snapshot trailer must accept three forms (legacy empty, iter-1 raw
    // 8-byte epoch, canonical TLV) and ignore unknown trailing fields inside
    // a TLV payload so v1 readers can load v2 snapshots that add a field.
    // -----------------------------------------------------------------------
    @Nested
    class SnapshotTrailerCompatibility {

        private static final int SNAPSHOT_TRAILER_MAGIC = 0xC0FD7A11;

        @Test
        void rawEpochTrailerStillLoads() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = gen.generateKeyPair();
            ConfigSigner signer = new ConfigSigner(kp);

            VersionedConfigStore srcStore = new VersionedConfigStore();
            ConfigStateMachine src = new ConfigStateMachine(srcStore, Clock.system(), signer);
            src.apply(1, 1, CommandCodec.encodePut("k", bytes("v")));

            byte[] full = src.snapshot();
            byte[] entriesOnly = java.util.Arrays.copyOf(full, full.length - 16);
            ByteBuffer raw = ByteBuffer.allocate(entriesOnly.length + Long.BYTES);
            raw.put(entriesOnly);
            raw.putLong(42L);
            byte[] iter1Snapshot = raw.array();

            VersionedConfigStore dstStore = new VersionedConfigStore();
            ConfigStateMachine dst = new ConfigStateMachine(dstStore, Clock.system(), signer);
            dst.restoreSnapshot(iter1Snapshot);

            assertTrue(dstStore.get("k").found(),
                    "iter-1 raw-epoch trailer must still load entries");
            assertEquals(42L, dst.signingEpoch(),
                    "iter-1 raw-epoch trailer must still restore signingEpoch");
        }

        @Test
        void tlvTrailerWithUnknownTrailingFieldStillLoads() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = gen.generateKeyPair();
            ConfigSigner signer = new ConfigSigner(kp);

            VersionedConfigStore srcStore = new VersionedConfigStore();
            ConfigStateMachine src = new ConfigStateMachine(srcStore, Clock.system(), signer);
            src.apply(1, 1, CommandCodec.encodePut("k", bytes("v")));

            byte[] canonical = src.snapshot();
            byte[] entriesOnly = java.util.Arrays.copyOf(canonical, canonical.length - 16);

            int trailerLen = 8 + 8;
            ByteBuffer v2 = ByteBuffer.allocate(entriesOnly.length + 4 + 4 + trailerLen);
            v2.put(entriesOnly);
            v2.putInt(SNAPSHOT_TRAILER_MAGIC);
            v2.putInt(trailerLen);
            v2.putLong(99L);
            v2.putLong(0xDEADBEEFL);

            VersionedConfigStore dstStore = new VersionedConfigStore();
            ConfigStateMachine dst = new ConfigStateMachine(dstStore, Clock.system(), signer);
            dst.restoreSnapshot(v2.array());

            assertTrue(dstStore.get("k").found(),
                    "TLV trailer with unknown trailing field must still load entries");
            assertEquals(99L, dst.signingEpoch(),
                    "TLV trailer must restore the known signingEpoch field");
        }

        @Test
        void malformedTrailerIsRejected() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = gen.generateKeyPair();
            ConfigSigner signer = new ConfigSigner(kp);

            VersionedConfigStore srcStore = new VersionedConfigStore();
            ConfigStateMachine src = new ConfigStateMachine(srcStore, Clock.system(), signer);
            src.apply(1, 1, CommandCodec.encodePut("k", bytes("v")));

            byte[] canonical = src.snapshot();
            byte[] entriesOnly = java.util.Arrays.copyOf(canonical, canonical.length - 16);

            byte[] junk = new byte[entriesOnly.length + 5];
            System.arraycopy(entriesOnly, 0, junk, 0, entriesOnly.length);
            junk[entriesOnly.length] = (byte) 0xAB;

            VersionedConfigStore dstStore = new VersionedConfigStore();
            ConfigStateMachine dst = new ConfigStateMachine(dstStore, Clock.system(), signer);
            assertThrows(IllegalArgumentException.class,
                    () -> dst.restoreSnapshot(junk),
                    "malformed trailer must throw rather than silently accept");
        }

        // -------------------------------------------------------------------
        // RR-092: decodeTrailer BOUNDARY survivors (9 surviving boundary
        // mutants, test-forensics §1.3 #9). decodeTrailer dispatches on the
        // bytes AFTER the entries:
        //   remaining == 0                         -> legacy (accept, no epoch)
        //   remaining >= 8 && first4 == MAGIC      -> TLV form
        //     trailerLen < 0 || trailerLen > MAX   -> reject
        //     remaining < trailerLen               -> reject (truncated)
        //     trailerLen >= Long.BYTES             -> read epoch (+ skip tail)
        //     else                                 -> skip trailerLen bytes
        //   remaining == Long.BYTES (8)            -> raw-epoch form
        //   else                                   -> malformed (reject)
        // The off-by-one mutants on those comparisons accept a corrupt trailer
        // or reject a valid one. These tests pin each boundary. The base is an
        // EMPTY store so the trailer is the only region exercised.
        // -------------------------------------------------------------------

        private static final int MAX_SNAPSHOT_TRAILER_LEN = 65_536;

        /** Header for an EMPTY store: [sequence=0][entryCount=0], no trailer. */
        private static byte[] emptyEntries() {
            ByteBuffer b = ByteBuffer.allocate(8 + 4);
            b.putLong(0L);
            b.putInt(0);
            return b.array();
        }

        /** entriesOnly + raw trailer bytes. */
        private static byte[] withTrailer(byte[] trailer) {
            byte[] head = emptyEntries();
            ByteBuffer b = ByteBuffer.allocate(head.length + trailer.length);
            b.put(head);
            b.put(trailer);
            return b.array();
        }

        private static ConfigStateMachine freshMachine() {
            return new ConfigStateMachine(new VersionedConfigStore());
        }

        @Test
        void emptyTrailerIsAcceptedAsLegacy() {
            // remaining == 0 boundary: a snapshot with no trailer loads (legacy).
            ConfigStateMachine dst = freshMachine();
            assertDoesNotThrow(() -> dst.restoreSnapshot(emptyEntries()));
            assertEquals(0L, dst.signingEpoch(), "legacy snapshot carries no epoch");
        }

        @Test
        void sevenTrailerBytesIsRejected() {
            // remaining == 7: below the `>= 8` magic gate AND != 8 (raw epoch) ->
            // the final malformed throw. Pins the `>= 8` lower boundary.
            ConfigStateMachine dst = freshMachine();
            assertThrows(IllegalArgumentException.class,
                    () -> dst.restoreSnapshot(withTrailer(new byte[7])),
                    "7 trailer bytes (not 0, not 8, not a TLV) must be rejected");
        }

        @Test
        void rawEightByteEpochAtBoundaryLoads() {
            // remaining == Long.BYTES (8) with no magic -> raw-epoch form.
            ByteBuffer t = ByteBuffer.allocate(8);
            t.putLong(123L);
            ConfigStateMachine dst = freshMachine();
            assertDoesNotThrow(() -> dst.restoreSnapshot(withTrailer(t.array())));
            assertEquals(123L, dst.signingEpoch(), "raw 8-byte trailer must restore the epoch");
        }

        @Test
        void tlvTrailerLengthAtMaxIsAccepted() {
            // trailerLen == MAX_SNAPSHOT_TRAILER_LEN: the high boundary is INCLUSIVE
            // (`> MAX` rejects, `== MAX` accepts). Build a payload of exactly MAX
            // bytes: 8-byte epoch + (MAX-8) unknown tail.
            int trailerLen = MAX_SNAPSHOT_TRAILER_LEN;
            ByteBuffer t = ByteBuffer.allocate(4 + 4 + trailerLen);
            t.putInt(SNAPSHOT_TRAILER_MAGIC);
            t.putInt(trailerLen);
            t.putLong(77L);
            // remaining (MAX-8) unknown-tail bytes are already zero.
            ConfigStateMachine dst = freshMachine();
            assertDoesNotThrow(() -> dst.restoreSnapshot(withTrailer(t.array())),
                    "trailerLen == MAX must be accepted (the > MAX guard is exclusive)");
            assertEquals(77L, dst.signingEpoch());
        }

        @Test
        void tlvTrailerLengthAboveMaxIsRejected() {
            // trailerLen == MAX+1: just over the high boundary -> reject. We do NOT
            // need to supply the bytes; the length check fires first.
            int trailerLen = MAX_SNAPSHOT_TRAILER_LEN + 1;
            ByteBuffer t = ByteBuffer.allocate(4 + 4 + 8);
            t.putInt(SNAPSHOT_TRAILER_MAGIC);
            t.putInt(trailerLen);
            t.putLong(0L);
            ConfigStateMachine dst = freshMachine();
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> dst.restoreSnapshot(withTrailer(t.array())),
                    "trailerLen == MAX+1 must be rejected");
            assertTrue(ex.getMessage().contains("out of range"),
                    "the length-range guard must fire; got: " + ex.getMessage());
        }

        @Test
        void tlvNegativeTrailerLengthIsRejected() {
            // trailerLen < 0 boundary.
            ByteBuffer t = ByteBuffer.allocate(4 + 4 + 8);
            t.putInt(SNAPSHOT_TRAILER_MAGIC);
            t.putInt(-1);
            t.putLong(0L);
            ConfigStateMachine dst = freshMachine();
            assertThrows(IllegalArgumentException.class,
                    () -> dst.restoreSnapshot(withTrailer(t.array())),
                    "a negative trailerLen must be rejected");
        }

        @Test
        void tlvTruncatedTrailerIsRejected() {
            // remaining < trailerLen by exactly one: claims 8 payload bytes but
            // supplies only 7. Pins the truncation boundary.
            ByteBuffer t = ByteBuffer.allocate(4 + 4 + 7);
            t.putInt(SNAPSHOT_TRAILER_MAGIC);
            t.putInt(8);          // claims 8 payload bytes
            t.put(new byte[7]);   // but only 7 present
            ConfigStateMachine dst = freshMachine();
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> dst.restoreSnapshot(withTrailer(t.array())),
                    "a trailer claiming more bytes than present must be rejected");
            assertTrue(ex.getMessage().contains("truncated"),
                    "the truncation guard must fire; got: " + ex.getMessage());
        }

        @Test
        void tlvTrailerLengthBelowEpochSizeSkipsWithoutReadingEpoch() {
            // trailerLen == Long.BYTES - 1 (7): below the `>= Long.BYTES` gate, so
            // the epoch is NOT read — the 7 bytes are skipped and the epoch stays 0.
            ByteBuffer t = ByteBuffer.allocate(4 + 4 + 7);
            t.putInt(SNAPSHOT_TRAILER_MAGIC);
            t.putInt(7);
            t.put(new byte[7]);
            ConfigStateMachine dst = freshMachine();
            assertDoesNotThrow(() -> dst.restoreSnapshot(withTrailer(t.array())),
                    "a sub-epoch-length TLV payload must load (fields skipped)");
            assertEquals(0L, dst.signingEpoch(),
                    "trailerLen < Long.BYTES must NOT read an epoch — it stays 0");
        }

        @Test
        void tlvTrailerOfExactlyEightBytesIsParsedAsTlvNotRawEpoch() {
            // remaining == 8 with the MAGIC prefix: a minimal TLV (magic + len=0,
            // no payload). The `remaining >= 8` magic gate is INCLUSIVE, so this is
            // a TLV (trailerLen 0, epoch left unchanged), NOT the raw-epoch form.
            // With `>= 8` -> `> 8` the magic gate would skip an exactly-8-byte
            // trailer and the bytes would be (mis)read as a raw 8-byte epoch.
            ByteBuffer t = ByteBuffer.allocate(8);
            t.putInt(SNAPSHOT_TRAILER_MAGIC);
            t.putInt(0); // trailerLen 0
            ConfigStateMachine dst = freshMachine();
            assertDoesNotThrow(() -> dst.restoreSnapshot(withTrailer(t.array())),
                    "an 8-byte magic+len=0 TLV trailer must load as a (payload-less) TLV");
            assertEquals(0L, dst.signingEpoch(),
                    "a zero-length TLV payload carries no epoch — it must NOT be read as a raw epoch "
                            + "(which would interpret the magic+len bytes as the epoch value)");
        }

        @Test
        void tlvTrailerExactlyEpochSizeReadsEpochWithNoTail() {
            // trailerLen == Long.BYTES (8): the `>= Long.BYTES` gate is inclusive
            // and unknownTail == 0 (the `unknownTail > 0` skip boundary).
            ByteBuffer t = ByteBuffer.allocate(4 + 4 + 8);
            t.putInt(SNAPSHOT_TRAILER_MAGIC);
            t.putInt(8);
            t.putLong(55L);
            ConfigStateMachine dst = freshMachine();
            assertDoesNotThrow(() -> dst.restoreSnapshot(withTrailer(t.array())));
            assertEquals(55L, dst.signingEpoch(),
                    "trailerLen == Long.BYTES must read exactly the epoch (no tail)");
        }
    }
}
