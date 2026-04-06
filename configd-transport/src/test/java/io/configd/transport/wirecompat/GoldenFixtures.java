package io.configd.transport.wirecompat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hard-coded golden bytes for every wire fixture version.
 * Keyed by "{type_name}.bin", values are the exact encoded frame bytes.
 * Maintained instead of binary files so diffs are human-readable.
 */
final class GoldenFixtures {
    private GoldenFixtures() {}

    static Map<String, byte[]> forVersion(int wireVersion) {
        if (wireVersion == 1) return v1();
        throw new IllegalArgumentException("No golden fixtures for wire version " + wireVersion);
    }

    private static Map<String, byte[]> v1() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("append_entries.bin",          hex("0000001a0101010203040a0b0c0d0e0f1011deadbeef19b5b90b"));
        m.put("append_entries_response.bin",  hex("0000001a0102010203040a0b0c0d0e0f1011deadbeeff998ddea"));
        m.put("request_vote.bin",             hex("0000001a0103010203040a0b0c0d0e0f1011deadbeefa67c01b5"));
        m.put("request_vote_response.bin",    hex("0000001a0104010203040a0b0c0d0e0f1011deadbeef3c2e62d9"));
        m.put("pre_vote.bin",                 hex("0000001a0105010203040a0b0c0d0e0f1011deadbeef63cabe86"));
        m.put("pre_vote_response.bin",        hex("0000001a0106010203040a0b0c0d0e0f1011deadbeef83e7da67"));
        m.put("install_snapshot.bin",         hex("0000001a0107010203040a0b0c0d0e0f1011deadbeefdc030638"));
        m.put("plumtree_eager_push.bin",      hex("0000001a0108010203040a0b0c0d0e0f1011deadbeefb2af6a4e"));
        m.put("plumtree_ihave.bin",           hex("0000001a0109010203040a0b0c0d0e0f1011deadbeefed4bb611"));
        m.put("plumtree_prune.bin",           hex("0000001a010a010203040a0b0c0d0e0f1011deadbeef0d66d2f0"));
        m.put("plumtree_graft.bin",           hex("0000001a010b010203040a0b0c0d0e0f1011deadbeef52820eaf"));
        m.put("hyparview_join.bin",           hex("0000001a010c010203040a0b0c0d0e0f1011deadbeefc8d06dc3"));
        m.put("hyparview_shuffle.bin",        hex("0000001a010d010203040a0b0c0d0e0f1011deadbeef9734b19c"));
        m.put("heartbeat.bin",                hex("00000016010e010203040a0b0c0d0e0f10115aa34ae5"));
        m.put("install_snapshot_response.bin",hex("0000001a010f010203040a0b0c0d0e0f1011deadbeef28fd0922"));
        m.put("timeout_now.bin",              hex("0000001a0110010203040a0b0c0d0e0f1011deadbeefaa410d91"));
        return m;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
