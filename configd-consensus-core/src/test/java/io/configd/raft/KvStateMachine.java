package io.configd.raft;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal but <em>real</em> key-value {@link StateMachine} for durability
 * tests: unlike the accumulator stubs used elsewhere, its {@link #snapshot()} /
 * {@link #restoreSnapshot(byte[])} faithfully round-trip the full applied state,
 * so a snapshot taken at index N and restored on a fresh instance reconstructs
 * exactly the committed key/value contents through index N.
 * <p>
 * Commands are {@code PUT key=value} encoded as UTF-8 {@code "key\0value"}.
 * Empty commands (no-op election entries) are non-mutating. The applied-mutation
 * sequence increments on every mutating apply, mirroring the contract used by
 * {@code ConfigStateMachine} (RR-004 / ADR-0033) so the commit-outcome seam
 * behaves identically under test.
 */
final class KvStateMachine implements StateMachine {

    private final LinkedHashMap<String, String> state = new LinkedHashMap<>();
    private long appliedSeq;

    /** Encodes a {@code PUT key=value} command. */
    static byte[] put(String key, String value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + k.length + v.length);
        buf.putInt(k.length);
        buf.put(k);
        buf.put(v);
        return buf.array();
    }

    @Override
    public long apply(long index, long term, byte[] command) {
        if (command == null || command.length == 0) {
            return StateMachine.NON_MUTATING; // no-op entry
        }
        ByteBuffer buf = ByteBuffer.wrap(command);
        int klen = buf.getInt();
        byte[] k = new byte[klen];
        buf.get(k);
        byte[] v = new byte[buf.remaining()];
        buf.get(v);
        state.put(new String(k, StandardCharsets.UTF_8), new String(v, StandardCharsets.UTF_8));
        return ++appliedSeq;
    }

    @Override
    public byte[] snapshot() {
        var out = new java.io.ByteArrayOutputStream();
        ByteBuffer hdr = ByteBuffer.allocate(8 + 4);
        hdr.putLong(appliedSeq);
        hdr.putInt(state.size());
        out.writeBytes(hdr.array());
        for (var e : state.entrySet()) {
            byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] v = e.getValue().getBytes(StandardCharsets.UTF_8);
            ByteBuffer rec = ByteBuffer.allocate(4 + k.length + 4 + v.length);
            rec.putInt(k.length);
            rec.put(k);
            rec.putInt(v.length);
            rec.put(v);
            out.writeBytes(rec.array());
        }
        return out.toByteArray();
    }

    @Override
    public void restoreSnapshot(byte[] snapshot) {
        state.clear();
        ByteBuffer buf = ByteBuffer.wrap(snapshot);
        this.appliedSeq = buf.getLong();
        int n = buf.getInt();
        for (int i = 0; i < n; i++) {
            int klen = buf.getInt();
            byte[] k = new byte[klen];
            buf.get(k);
            int vlen = buf.getInt();
            byte[] v = new byte[vlen];
            buf.get(v);
            state.put(new String(k, StandardCharsets.UTF_8), new String(v, StandardCharsets.UTF_8));
        }
    }

    /** An immutable snapshot of the current applied key/value state. */
    Map<String, String> snapshotState() {
        return new LinkedHashMap<>(state);
    }
}
