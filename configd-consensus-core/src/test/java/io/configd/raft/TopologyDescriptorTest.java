package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyDescriptorTest {

    private static IntegrityEnvelope keyed() {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (i + 7);
        }
        return new IntegrityEnvelope(new SecretKeySpec(k, "HmacSHA256"));
    }

    /** Craft an 18-byte payload directly (bypassing the ctor) so an illegal epoch/reserved can be tested. */
    private static byte[] craftPayload(short formatVersion, int shardCount, long epoch, int reserved) {
        byte[] payload = new byte[TopologyDescriptor.PAYLOAD_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.putShort(formatVersion);
        buf.putInt(shardCount);
        buf.putLong(epoch);
        buf.putInt(reserved);
        return payload;
    }

    @Test
    void roundTripKeyed() {
        IntegrityEnvelope env = keyed();
        TopologyDescriptor d = new TopologyDescriptor(4, TopologyDescriptor.INITIAL_EPOCH);
        TopologyDescriptor back = TopologyDescriptor.fromEnvelope(env, d.toEnvelope(env));
        assertEquals(4, back.shardCount());
        assertEquals(1L, back.topologyEpoch());
    }

    @Test
    void roundTripKeyless() {
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        TopologyDescriptor d = new TopologyDescriptor(1, TopologyDescriptor.INITIAL_EPOCH);
        TopologyDescriptor back = TopologyDescriptor.fromEnvelope(env, d.toEnvelope(env));
        assertEquals(1, back.shardCount());
        assertEquals(1L, back.topologyEpoch());
    }

    @Test
    void ctorRejectsReservedIllegalEpoch() {
        assertThrows(IllegalArgumentException.class, () -> new TopologyDescriptor(1, 0L));
        assertThrows(IllegalArgumentException.class, () -> new TopologyDescriptor(0, 1L));
    }

    @Test
    void topologyEpochZeroRejected() {
        IntegrityEnvelope env = keyed();
        byte[] enveloped = env.wrap(TopologyDescriptor.TOPO_MAGIC, IntegrityEnvelope.NODE_SCOPE,
                craftPayload(TopologyDescriptor.FORMAT_VERSION, 1, 0L, 0));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TopologyDescriptor.fromEnvelope(env, enveloped));
        assertTrue(e.getMessage().contains("epoch 0"), () -> e.getMessage());
    }

    @Test
    void reservedNonZeroRejected() {
        IntegrityEnvelope env = keyed();
        byte[] enveloped = env.wrap(TopologyDescriptor.TOPO_MAGIC, IntegrityEnvelope.NODE_SCOPE,
                craftPayload(TopologyDescriptor.FORMAT_VERSION, 1, 1L, 0x0000_0001));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TopologyDescriptor.fromEnvelope(env, enveloped));
        assertTrue(e.getMessage().toLowerCase().contains("reserved"), () -> e.getMessage());
    }

    @Test
    void rolledFormatVersionRejected() {
        IntegrityEnvelope env = keyed();
        byte[] enveloped = env.wrap(TopologyDescriptor.TOPO_MAGIC, IntegrityEnvelope.NODE_SCOPE,
                craftPayload((short) 2, 1, 1L, 0));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TopologyDescriptor.fromEnvelope(env, enveloped));
        assertTrue(e.getMessage().contains("formatVersion"), () -> e.getMessage());
    }

    @Test
    void tamperDetectedUnderKey() {
        IntegrityEnvelope env = keyed();
        byte[] enveloped = new TopologyDescriptor(2, 1L).toEnvelope(env);
        enveloped[enveloped.length / 2] ^= 0x01;
        assertThrows(IntegrityException.class, () -> TopologyDescriptor.fromEnvelope(env, enveloped));
    }
}
