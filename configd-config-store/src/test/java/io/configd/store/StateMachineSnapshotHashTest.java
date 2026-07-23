package io.configd.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins {@link ConfigStateMachine#stateMachineHashHex()} to the contract the restore-conformance check
 * ({@code ops/scripts/restore-conformance-check.sh}) relies on: the exposed digest equals a SHA-256 over
 * the snapshot <em>payload region</em> - the bytes {@link ConfigStateMachine#snapshot()} emits after its
 * 12-byte {@code [8B sequence][4B entry count]} header - which is exactly what the check computes over the
 * snapshot file ({@code tail -c +13 | sha256sum}). If a snapshot-format change breaks that equivalence,
 * this test fails rather than letting the digest silently drift out of lockstep.
 */
class StateMachineSnapshotHashTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256HexOfPayload(byte[] snapshot) {
        // Mirror the shell check: hash everything after the 12-byte header.
        byte[] payload = Arrays.copyOfRange(snapshot, 12, snapshot.length);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void hashEqualsSha256OverSnapshotPayloadRegion() {
        VersionedConfigStore store = new VersionedConfigStore();
        ConfigStateMachine sm = new ConfigStateMachine(store);
        sm.apply(1, 1, CommandCodec.encodePut("db.host", bytes("localhost")));
        sm.apply(2, 1, CommandCodec.encodePut("db.port", bytes("5432")));
        sm.apply(3, 1, CommandCodec.encodePut("feature.flag", bytes("on")));

        String live = sm.stateMachineHashHex();
        String overFile = sha256HexOfPayload(sm.snapshot());

        assertEquals(overFile, live,
                "stateMachineHashHex must equal sha256(snapshot()[12:]) - the restore-check contract");
    }

    @Test
    void hashEqualsSnapshotPayloadWithSigner() throws Exception {
        // With a signer configured, snapshot() carries a non-zero signingEpoch in the trailer;
        // the digest must still fold that same epoch in (payload region includes the trailer).
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        VersionedConfigStore store = new VersionedConfigStore();
        ConfigStateMachine sm = new ConfigStateMachine(store,
                io.configd.common.Clock.system(), new ConfigSigner(kp));
        sm.apply(1, 1, CommandCodec.encodePut("k1", bytes("v1")));
        sm.apply(2, 1, CommandCodec.encodePut("k2", bytes("v2")));

        assertEquals(sha256HexOfPayload(sm.snapshot()), sm.stateMachineHashHex());
    }

    @Test
    void hashIsStableWhenStateUnchangedAndChangesOnMutation() {
        VersionedConfigStore store = new VersionedConfigStore();
        ConfigStateMachine sm = new ConfigStateMachine(store);
        sm.apply(1, 1, CommandCodec.encodePut("a", bytes("1")));

        String before = sm.stateMachineHashHex();
        assertEquals(before, sm.stateMachineHashHex(), "idle re-read must return the same digest");

        sm.apply(2, 1, CommandCodec.encodePut("b", bytes("2")));
        assertNotEquals(before, sm.stateMachineHashHex(), "a mutation must change the digest");
    }

    @Test
    void identicalLogicalStateHashesIdentically() {
        // The snapshot format is deterministic for equal logical contents (HamtMap.forEach order),
        // so two independently-built state machines with the same keys hash to the same digest -
        // the property a restore relies on (restored node matches the snapshot it was built from).
        VersionedConfigStore s1 = new VersionedConfigStore();
        ConfigStateMachine m1 = new ConfigStateMachine(s1);
        m1.apply(1, 1, CommandCodec.encodePut("x", bytes("1")));
        m1.apply(2, 1, CommandCodec.encodePut("y", bytes("2")));

        VersionedConfigStore s2 = new VersionedConfigStore();
        ConfigStateMachine m2 = new ConfigStateMachine(s2);
        m2.apply(1, 1, CommandCodec.encodePut("x", bytes("1")));
        m2.apply(2, 1, CommandCodec.encodePut("y", bytes("2")));

        assertEquals(m1.stateMachineHashHex(), m2.stateMachineHashHex());
    }
}
