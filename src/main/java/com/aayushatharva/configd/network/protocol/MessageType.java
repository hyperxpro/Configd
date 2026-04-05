package com.aayushatharva.configd.network.protocol;

/**
 * Wire protocol message types for inter-node communication.
 */
public enum MessageType {
    // Replication
    REPLICATION_PULL_REQUEST((byte) 0x01),
    REPLICATION_PULL_RESPONSE((byte) 0x02),

    // Cache operations (L2 sharded cache)
    CACHE_LOOKUP_REQUEST((byte) 0x10),
    CACHE_LOOKUP_RESPONSE((byte) 0x11),
    CACHE_STORE_REQUEST((byte) 0x12),
    CACHE_STORE_RESPONSE((byte) 0x13),

    // Relay operations (proxy -> relay -> replica)
    RELAY_GET_REQUEST((byte) 0x20),
    RELAY_GET_RESPONSE((byte) 0x21),

    // Reactive prefetch
    PREFETCH_NOTIFY((byte) 0x30),

    // Gossip / Discovery
    GOSSIP_PING((byte) 0x40),
    GOSSIP_PONG((byte) 0x41),
    GOSSIP_MEMBER_LIST((byte) 0x42),

    // Health
    HEALTH_CHECK((byte) 0x50),
    HEALTH_RESPONSE((byte) 0x51);

    private final byte code;

    MessageType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static MessageType fromCode(byte code) {
        for (var type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("Unknown message type: 0x" + Integer.toHexString(code & 0xFF));
    }
}
