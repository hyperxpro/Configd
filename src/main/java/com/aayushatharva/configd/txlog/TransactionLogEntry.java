package com.aayushatharva.configd.txlog;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * A single entry in the transaction log.
 *
 * Binary format:
 * <pre>
 *   [8 bytes] sequence number (big-endian long)
 *   [1 byte]  operation code (1=SET, 2=DELETE)
 *   [8 bytes] timestamp (epoch millis)
 *   [4 bytes] key length
 *   [N bytes] key
 *   [4 bytes] value length (0 for DELETE)
 *   [M bytes] value (absent for DELETE)
 *   [4 bytes] CRC32 checksum of all preceding bytes
 * </pre>
 */
public record TransactionLogEntry(
        long sequenceNumber,
        Operation operation,
        long timestamp,
        byte[] key,
        byte[] value
) {

    /** Serialize to wire format. */
    public byte[] serialize() {
        int valueLen = (value != null) ? value.length : 0;
        int totalLen = 8 + 1 + 8 + 4 + key.length + 4 + valueLen + 4;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.putLong(sequenceNumber);
        buf.put(operation.code());
        buf.putLong(timestamp);
        buf.putInt(key.length);
        buf.put(key);
        buf.putInt(valueLen);
        if (valueLen > 0) {
            buf.put(value);
        }

        // CRC32 of everything before the checksum
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, totalLen - 4);
        buf.putInt((int) crc.getValue());

        return buf.array();
    }

    /** Deserialize from wire format. */
    public static TransactionLogEntry deserialize(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);

        long seq = buf.getLong();
        Operation op = Operation.fromCode(buf.get());
        long ts = buf.getLong();

        int keyLen = buf.getInt();
        byte[] key = new byte[keyLen];
        buf.get(key);

        int valueLen = buf.getInt();
        byte[] value = null;
        if (valueLen > 0) {
            value = new byte[valueLen];
            buf.get(value);
        }

        int storedCrc = buf.getInt();

        // Verify CRC
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 4);
        int computedCrc = (int) crc.getValue();
        if (storedCrc != computedCrc) {
            throw new IllegalStateException(
                    "CRC mismatch for entry " + seq + ": stored=" + storedCrc + " computed=" + computedCrc);
        }

        return new TransactionLogEntry(seq, op, ts, key, value);
    }

    /** Byte size of the serialized entry. */
    public int serializedSize() {
        int valueLen = (value != null) ? value.length : 0;
        return 8 + 1 + 8 + 4 + key.length + 4 + valueLen + 4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransactionLogEntry that)) return false;
        return sequenceNumber == that.sequenceNumber
                && timestamp == that.timestamp
                && operation == that.operation
                && Arrays.equals(key, that.key)
                && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(sequenceNumber);
        result = 31 * result + operation.hashCode();
        result = 31 * result + Arrays.hashCode(key);
        return result;
    }
}
