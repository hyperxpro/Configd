package com.aayushatharva.configd.txlog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for TransactionLogEntry serialization, deserialization, and CRC validation.
 */
class TransactionLogEntryTest {

    @Test
    void serializeAndDeserializeSetEntry() {
        var entry = new TransactionLogEntry(
                42, Operation.SET, System.currentTimeMillis(),
                "key1".getBytes(StandardCharsets.UTF_8),
                "value1".getBytes(StandardCharsets.UTF_8)
        );

        byte[] serialized = entry.serialize();
        var deserialized = TransactionLogEntry.deserialize(serialized);

        assertThat(deserialized.sequenceNumber()).isEqualTo(42);
        assertThat(deserialized.operation()).isEqualTo(Operation.SET);
        assertThat(deserialized.key()).isEqualTo("key1".getBytes());
        assertThat(deserialized.value()).isEqualTo("value1".getBytes());
    }

    @Test
    void serializeAndDeserializeDeleteEntry() {
        var entry = new TransactionLogEntry(
                99, Operation.DELETE, System.currentTimeMillis(),
                "key2".getBytes(StandardCharsets.UTF_8),
                null
        );

        byte[] serialized = entry.serialize();
        var deserialized = TransactionLogEntry.deserialize(serialized);

        assertThat(deserialized.sequenceNumber()).isEqualTo(99);
        assertThat(deserialized.operation()).isEqualTo(Operation.DELETE);
        assertThat(deserialized.key()).isEqualTo("key2".getBytes());
        assertThat(deserialized.value()).isNull();
    }

    @Test
    void crcDetectsCorruption() {
        var entry = new TransactionLogEntry(
                1, Operation.SET, System.currentTimeMillis(),
                "key".getBytes(), "value".getBytes()
        );

        byte[] serialized = entry.serialize();

        // Corrupt the CRC itself (last 4 bytes) so the data is parseable but checksum fails
        serialized[serialized.length - 1] ^= 0xFF;

        assertThatThrownBy(() -> TransactionLogEntry.deserialize(serialized))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRC mismatch");
    }

    @Test
    void serializedSizeIsAccurate() {
        var entry = new TransactionLogEntry(
                1, Operation.SET, System.currentTimeMillis(),
                "abc".getBytes(), "xyz".getBytes()
        );

        assertThat(entry.serialize().length).isEqualTo(entry.serializedSize());
    }
}
