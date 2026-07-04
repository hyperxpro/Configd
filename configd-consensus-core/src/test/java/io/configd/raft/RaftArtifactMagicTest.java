package io.configd.raft;

import io.configd.common.WalContainer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the frozen-v1 magic registry: every value is distinct and non-zero (reserved-value
 * discipline - a zero-filled/torn leading word is never a valid artifact, and no two artifacts
 * may share a magic), and the WAL container magic stays in lockstep with its authoritative
 * definition in {@code configd-common}.
 */
class RaftArtifactMagicTest {

    /** The full registry keyed by name, so a collision reports which pair clashed. */
    private static Map<String, Integer> registry() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("STATE_MAGIC", RaftArtifactMagic.STATE_MAGIC);
        m.put("SNAP_MAGIC", RaftArtifactMagic.SNAP_MAGIC);
        m.put("WALE_MAGIC", RaftArtifactMagic.WALE_MAGIC);
        m.put("WAL_FILE_MAGIC", RaftArtifactMagic.WAL_FILE_MAGIC);
        m.put("AUDIT_MAGIC", RaftArtifactMagic.AUDIT_MAGIC);
        m.put("ANCHOR_MAGIC", RaftArtifactMagic.ANCHOR_MAGIC);
        m.put("NODE_ANCHOR_MAGIC", RaftArtifactMagic.NODE_ANCHOR_MAGIC);
        m.put("KEYRING_MAGIC", RaftArtifactMagic.KEYRING_MAGIC);
        m.put("TOPO_MAGIC", RaftArtifactMagic.TOPO_MAGIC);
        return m;
    }

    @Test
    void everyMagicIsNonZero() {
        registry().forEach((name, value) ->
                assertNotEquals(0, value.intValue(), name + " must be non-zero (magic 0 is reserved-illegal)"));
    }

    @Test
    void allMagicsAreDistinct() {
        Map<String, Integer> reg = registry();
        // Compare every unordered pair so a clash names both members.
        var entries = reg.entrySet().stream().toList();
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                assertNotEquals(entries.get(i).getValue(), entries.get(j).getValue(),
                        entries.get(i).getKey() + " and " + entries.get(j).getKey() + " collide");
            }
        }
        // Sanity: distinct values means the set size equals the entry count.
        assertEquals(reg.size(), reg.values().stream().distinct().count(),
                "registry contains a duplicate magic value");
    }

    @Test
    void walFileMagicMirrorsTheAuthoritativeContainerDefinition() {
        // WalContainer (configd-common) is the authoritative definition; the registry mirrors it
        // because configd-common cannot depend on this module. This pins the two against drift.
        assertEquals(WalContainer.WAL_FILE_MAGIC, RaftArtifactMagic.WAL_FILE_MAGIC,
                "the registry's WAL_FILE_MAGIC must equal WalContainer.WAL_FILE_MAGIC");
    }

    @Test
    void magicsMatchTheirAsciiSigils() {
        // The values are ASCII sigils for hexdump grep-ability; pin the frozen bytes.
        assertEquals(0x5246_5354, RaftArtifactMagic.STATE_MAGIC, "RFST");
        assertEquals(0x5253_4E50, RaftArtifactMagic.SNAP_MAGIC, "RSNP");
        assertEquals(0x5257_414C, RaftArtifactMagic.WALE_MAGIC, "RWAL");
        assertEquals(0x5257_4C46, RaftArtifactMagic.WAL_FILE_MAGIC, "RWLF");
        assertEquals(0x5241_5544, RaftArtifactMagic.AUDIT_MAGIC, "RAUD");
        assertEquals(0x5241_4E43, RaftArtifactMagic.ANCHOR_MAGIC, "RANC");
        assertEquals(0x524E_414E, RaftArtifactMagic.NODE_ANCHOR_MAGIC, "RNAN");
        assertEquals(0x524B_5952, RaftArtifactMagic.KEYRING_MAGIC, "RKYR");
        assertEquals(0x5254_4F50, RaftArtifactMagic.TOPO_MAGIC, "RTOP");
    }
}
