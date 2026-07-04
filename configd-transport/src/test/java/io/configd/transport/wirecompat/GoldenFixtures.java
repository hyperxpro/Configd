package io.configd.transport.wirecompat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hard-coded golden bytes for every wire fixture version.
 * Keyed by "{type_name}.bin", values are the exact encoded frame bytes.
 * Maintained instead of binary files so diffs are human-readable.
 *
 * <p>v2 (Multi-Raft Phase 1, Seam F): the {@code WIRE_VERSION 0x01→0x02} bump — version byte
 * {@code 01→02}, an 8-byte reserved epoch field (all zero) after the term (D1), and the new
 * {@code raft_coalesced_heartbeat.bin} fixture (D2). v1 is a clean cutover (no external deployments;
 * the decoder rejects v1), so its golden bytes are superseded rather than retained.
 */
final class GoldenFixtures {
    private GoldenFixtures() {}

    static Map<String, byte[]> forVersion(int wireVersion) {
        if (wireVersion == 2) return v2();
        throw new IllegalArgumentException("No golden fixtures for wire version " + wireVersion);
    }

    private static Map<String, byte[]> v2() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("append_entries.bin",          hex("000000220201010203040a0b0c0d0e0f10110000000000000000deadbeef62fdbcaf"));
        m.put("append_entries_response.bin",  hex("000000220202010203040a0b0c0d0e0f10110000000000000000deadbeef10a0e008"));
        m.put("request_vote.bin",             hex("000000220203010203040a0b0c0d0e0f10110000000000000000deadbeef3e942b95"));
        m.put("request_vote_response.bin",    hex("000000220204010203040a0b0c0d0e0f10110000000000000000deadbeeff41a5946"));
        m.put("pre_vote.bin",                 hex("000000220205010203040a0b0c0d0e0f10110000000000000000deadbeefda2e92db"));
        m.put("pre_vote_response.bin",        hex("000000220206010203040a0b0c0d0e0f10110000000000000000deadbeefa873ce7c"));
        m.put("install_snapshot.bin",         hex("000000220207010203040a0b0c0d0e0f10110000000000000000deadbeef864705e1"));
        m.put("plumtree_eager_push.bin",      hex("000000220208010203040a0b0c0d0e0f10110000000000000000deadbeef38835d2b"));
        m.put("plumtree_ihave.bin",           hex("000000220209010203040a0b0c0d0e0f10110000000000000000deadbeef16b796b6"));
        m.put("plumtree_prune.bin",           hex("00000022020a010203040a0b0c0d0e0f10110000000000000000deadbeef64eaca11"));
        m.put("plumtree_graft.bin",           hex("00000022020b010203040a0b0c0d0e0f10110000000000000000deadbeef4ade018c"));
        m.put("hyparview_join.bin",           hex("00000022020c010203040a0b0c0d0e0f10110000000000000000deadbeef8050735f"));
        m.put("hyparview_shuffle.bin",        hex("00000022020d010203040a0b0c0d0e0f10110000000000000000deadbeefae64b8c2"));
        m.put("heartbeat.bin",                hex("0000001e020e010203040a0b0c0d0e0f10110000000000000000c7774a8c"));
        m.put("install_snapshot_response.bin",hex("00000022020f010203040a0b0c0d0e0f10110000000000000000deadbeeff20d2ff8"));
        m.put("timeout_now.bin",              hex("000000220210010203040a0b0c0d0e0f10110000000000000000deadbeefa45d2300"));
        m.put("raft_coalesced_heartbeat.bin", hex("000000220211010203040a0b0c0d0e0f10110000000000000000deadbeef8a69e89d"));
        // Gate 3c anchor-witness frame types (additive, same wire version 0x02 - the frame envelope for
        // the new type codes 0x12/0x13; the 29-byte witness body is exercised by RaftWitnessCodecTest).
        m.put("raft_witness.bin",             hex("000000220212010203040a0b0c0d0e0f10110000000000000000deadbeeff834b43a"));
        m.put("raft_witness_reply.bin",       hex("000000220213010203040a0b0c0d0e0f10110000000000000000deadbeefd6007fa7"));
        return m;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
