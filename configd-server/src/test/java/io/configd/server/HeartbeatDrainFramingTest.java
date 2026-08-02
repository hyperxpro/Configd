package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeartbeatDrainFramingTest {

    private static final NodeId LEADER = NodeId.of(1);

    private static AppendEntriesRequest heartbeat(long term) {
        return new AppendEntriesRequest(term, LEADER, 0L, 0L, List.of(), 0L);
    }

    @Test
    void singleGroupDrainFramesPlainAppendEntries_wireUnchanged() {
        Map<Integer, AppendEntriesRequest> one = new LinkedHashMap<>();
        one.put(0, heartbeat(5L));
        FrameCodec.Frame frame = ConfigdServer.frameHeartbeatDrain(one);

        assertEquals(MessageType.APPEND_ENTRIES, frame.messageType(),
                "the N=1 drain must emit a normal AppendEntries, not a coalesced frame");
        assertEquals(0, frame.groupId(), "the AppendEntries must carry the group's real id");
        assertEquals(5L, frame.term());
    }

    @Test
    void singleGroupDrainUsesActualGroupId() {
        Map<Integer, AppendEntriesRequest> one = new LinkedHashMap<>();
        one.put(13, heartbeat(7L));
        FrameCodec.Frame frame = ConfigdServer.frameHeartbeatDrain(one);
        assertEquals(MessageType.APPEND_ENTRIES, frame.messageType());
        assertEquals(13, frame.groupId(), "a single-group drain frames with that group's id, not 0");
    }

    @Test
    void multiGroupDrainFramesOneCoalescedFrame() {
        Map<Integer, AppendEntriesRequest> many = new LinkedHashMap<>();
        many.put(0, heartbeat(5L));
        many.put(1, heartbeat(6L));
        many.put(2, heartbeat(7L));
        FrameCodec.Frame frame = ConfigdServer.frameHeartbeatDrain(many);

        assertEquals(MessageType.RAFT_COALESCED_HEARTBEAT, frame.messageType(),
                "a multi-group drain (N>1) must collapse into one coalesced frame");
        Map<Integer, AppendEntriesRequest> decoded = RaftMessageCodec.decodeCoalescedHeartbeat(frame);
        assertEquals(many.keySet(), decoded.keySet());
    }
}
