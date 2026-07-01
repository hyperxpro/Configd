package io.configd.transport;

/**
 * Wire protocol message types for the Configd data plane.
 * Each type maps to a single byte on the wire.
 */
public enum MessageType {
    APPEND_ENTRIES(0x01),
    APPEND_ENTRIES_RESPONSE(0x02),
    REQUEST_VOTE(0x03),
    REQUEST_VOTE_RESPONSE(0x04),
    PRE_VOTE(0x05),
    PRE_VOTE_RESPONSE(0x06),
    INSTALL_SNAPSHOT(0x07),
    PLUMTREE_EAGER_PUSH(0x08),
    PLUMTREE_IHAVE(0x09),
    PLUMTREE_PRUNE(0x0A),
    PLUMTREE_GRAFT(0x0B),
    HYPARVIEW_JOIN(0x0C),
    HYPARVIEW_SHUFFLE(0x0D),
    HEARTBEAT(0x0E),
    INSTALL_SNAPSHOT_RESPONSE(0x0F),
    TIMEOUT_NOW(0x10),
    /**
     * Coalesced heartbeat: carries every group's empty AppendEntries that one node drained for
     * a single peer in one tick, as ONE frame instead of one per group. Dormant at N=1
     * (a single-group drain sends a plain {@link #APPEND_ENTRIES}); emitted at N&gt;1.
     * Payload codec and demux: {@code RaftMessageCodec.{encode,decode}CoalescedHeartbeat}.
     */
    RAFT_COALESCED_HEARTBEAT(0x11);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    private static final MessageType[] BY_CODE = new MessageType[0x12];
    static {
        for (MessageType type : values()) {
            BY_CODE[type.code] = type;
        }
    }

    public static MessageType fromCode(int code) {
        if (code < 0 || code >= BY_CODE.length || BY_CODE[code] == null) {
            throw new IllegalArgumentException("Unknown message type code: " + code);
        }
        return BY_CODE[code];
    }
}
