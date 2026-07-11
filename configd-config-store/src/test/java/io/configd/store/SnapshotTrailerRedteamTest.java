package io.configd.store;

import io.configd.common.Clock;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent red-team pass over the snapshot magic-TLV trailer in {@link ConfigStateMachine}.
 *
 * <p>Decode-boundary cases (legacy forms refused, TLV lengths, truncation, unknown-tail tolerance)
 * are covered elsewhere; this class adds two checks:
 * <ol>
 *   <li>an independent spot-check that the WRITER ({@code snapshot()}) only ever emits the canonical
 *       trailer form, never a trailer-less or bare-8-byte-epoch snapshot;</li>
 *   <li>a positional-parse confusion probe: an ENTRY VALUE whose bytes are byte-for-byte a fake TLV
 *       trailer must NOT be mistaken for the real trailer. The decoder consumes the trailer by
 *       position (after all entries), never by scanning for the magic, so a value that looks like a
 *       trailer is inert.</li>
 * </ol>
 */
class SnapshotTrailerRedteamTest {

    private static final int SNAPSHOT_TRAILER_MAGIC = 0xC0FD7A11;

    private static ConfigSigner signer() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new ConfigSigner(kp);
    }

    @Test
    void writerAlwaysEmitsCanonicalTlvTrailerNeverLegacyForms() throws Exception {
        // Spot-check the SOURCE of truth: the writer. Whatever the store holds, snapshot() must end in
        // exactly [MAGIC:4][trailerLen=8:4][signingEpoch:8]. If any path emitted a trailer-less or
        // bare-8-byte-epoch snapshot, a reader could be steered onto a rejected legacy form.
        ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore(), Clock.system(), signer());
        sm.apply(1, 1, CommandCodec.encodePut("db.host", "localhost".getBytes()));
        sm.apply(2, 1, CommandCodec.encodePut("db.port", "5432".getBytes()));

        byte[] snap = sm.snapshot();
        assertTrue(snap.length >= 16, "a snapshot must carry the 16-byte canonical trailer");

        ByteBuffer tail = ByteBuffer.wrap(snap, snap.length - 16, 16);
        assertEquals(SNAPSHOT_TRAILER_MAGIC, tail.getInt(), "trailer must lead with the canonical magic");
        assertEquals(8, tail.getInt(), "the frozen trailerLen is exactly 8 (signingEpoch only)");
        assertEquals(sm.signingEpoch(), tail.getLong(), "trailer must carry the live signingEpoch");
    }

    @Test
    void entryValueEqualToAFakeTrailerIsNotMisparsedAsTheTrailer() throws Exception {
        // Attack: craft an entry VALUE whose bytes are a complete, plausible fake TLV trailer with a
        // wildly different epoch, then snapshot + restore. If the decoder searched for the magic
        // instead of reading the trailer by position, it could latch onto the fake trailer embedded
        // in the value and restore the attacker's epoch. It must not: the real trailer (positional)
        // wins and the value round-trips verbatim.
        long fakeEpoch = 0x7FFF_FFFF_FFFF_FFFFL;
        ByteBuffer fakeTrailer = ByteBuffer.allocate(16);
        fakeTrailer.putInt(SNAPSHOT_TRAILER_MAGIC);
        fakeTrailer.putInt(8);
        fakeTrailer.putLong(fakeEpoch);
        byte[] valueLookingLikeATrailer = fakeTrailer.array();

        ConfigStateMachine src = new ConfigStateMachine(new VersionedConfigStore(), Clock.system(), signer());
        src.apply(1, 1, CommandCodec.encodePut("k", valueLookingLikeATrailer));
        long realEpoch = src.signingEpoch();
        assertNotEquals(fakeEpoch, realEpoch, "precondition: the real epoch differs from the decoy");

        byte[] snapshot = src.snapshot();

        VersionedConfigStore dstStore = new VersionedConfigStore();
        ConfigStateMachine dst = new ConfigStateMachine(dstStore, Clock.system(), signer());
        dst.restoreSnapshot(snapshot);

        assertArrayEquals(valueLookingLikeATrailer, dstStore.get("k").value(),
                "the trailer-shaped value must round-trip verbatim, not be swallowed as a trailer");
        assertEquals(realEpoch, dst.signingEpoch(),
                "the REAL positional trailer must set the epoch, never the decoy inside the value");
        assertNotEquals(fakeEpoch, dst.signingEpoch(),
                "the decoy epoch embedded in the value must never be adopted");
    }
}
