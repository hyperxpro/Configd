package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.common.IntegrityEnvelope;
import io.configd.raft.TopologyDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShardCountConfigTest {

    private static final String PROP = "configd.raft.shardCount";
    private static final String DESCRIPTOR = "topology-descriptor.dat";

    /** A deterministic keyed integrity envelope so the tamper test exercises the MAC (tamper-evident). */
    private static IntegrityEnvelope keyedEnvelope() {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (i + 1);
        }
        return new IntegrityEnvelope(new SecretKeySpec(k, "HmacSHA256"));
    }

    @TempDir
    Path dataDir;

    private String saved;

    @BeforeEach
    void saveProp() {
        saved = System.getProperty(PROP);
        System.clearProperty(PROP);
    }

    @AfterEach
    void restoreProp() {
        if (saved == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, saved);
        }
    }

    @Test
    void defaultIsOne() {
        assertEquals(1, ConfigdServer.resolveShardCount(),
                "default shard count must be 1 (single group, byte-identical to today)");
    }

    @Test
    void explicitOneIsAccepted() {
        System.setProperty(PROP, "1");
        assertEquals(1, ConfigdServer.resolveShardCount());
    }

    @Test
    void zeroIsRejectedAsOutOfRange() {
        System.setProperty(PROP, "0");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, ConfigdServer::resolveShardCount);
        assertTrue(e.getMessage().contains("[1, 16]"),
                () -> "range error should name the bounds: " + e.getMessage());
    }

    @Test
    void aboveCeilingIsRejectedAsOutOfRange() {
        System.setProperty(PROP, "17");
        assertThrows(IllegalArgumentException.class, ConfigdServer::resolveShardCount);
    }

    @Test
    void firstBootWritesDescriptorAtInitialEpoch() {
        long epoch = ConfigdServer.enforceTopologyDescriptor(1, dataDir, keyedEnvelope());
        assertEquals(TopologyDescriptor.INITIAL_EPOCH, epoch,
                "first boot yields the v1 initial topology epoch");
        assertTrue(Files.exists(dataDir.resolve(DESCRIPTOR)), "first boot persists the topology descriptor");
    }

    @Test
    void firstBootPersistsThenIsIdempotent() {
        IntegrityEnvelope env = keyedEnvelope();
        assertEquals(1L, ConfigdServer.enforceTopologyDescriptor(3, dataDir, env));
        assertTrue(Files.exists(dataDir.resolve(DESCRIPTOR)));
        // Same N again is a read-only no-op (idempotent across restarts) and returns the same epoch.
        assertEquals(1L, ConfigdServer.enforceTopologyDescriptor(3, dataDir, env));
    }

    @Test
    void nGreaterThanOneBootsAndReshardNChangeStillRefused() {
        IntegrityEnvelope env = keyedEnvelope();
        for (int n : new int[] {2, 4, 16}) {
            Path dir = dataDir.resolve("n" + n);
            try {
                Files.createDirectories(dir);
            } catch (java.io.IOException io) {
                throw new RuntimeException(io);
            }
            assertEquals(1L, ConfigdServer.enforceTopologyDescriptor(n, dir, env),
                    () -> "N must boot and yield the initial epoch");
            assertTrue(Files.exists(dir.resolve(DESCRIPTOR)),
                    "N=" + n + " boot persists the fixed-at-deploy descriptor");
            IllegalStateException reshard = assertThrows(IllegalStateException.class,
                    () -> ConfigdServer.enforceTopologyDescriptor(n - 1, dir, env),
                    () -> "reshard from N=" + n + " must reject");
            assertTrue(reshard.getMessage().contains("FIXED AT DEPLOY"),
                    () -> "reshard rejection should explain fixed-at-deploy: " + reshard.getMessage());
        }
    }

    @Test
    void changedShardCountIsRejectedAsReshard() {
        IntegrityEnvelope env = keyedEnvelope();
        ConfigdServer.enforceTopologyDescriptor(4, dataDir, env);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigdServer.enforceTopologyDescriptor(8, dataDir, env));
        assertTrue(e.getMessage().contains("FIXED AT DEPLOY"),
                () -> "reshard rejection should explain fixed-at-deploy: " + e.getMessage());
        assertTrue(e.getMessage().contains("N=4"), () -> "should name the persisted N: " + e.getMessage());
        // The descriptor is unchanged (the rejection does not overwrite it): re-reading with the same N
        // still succeeds at the original epoch.
        assertEquals(1L, ConfigdServer.enforceTopologyDescriptor(4, dataDir, env));
    }

    @Test
    void topologyDescriptorTamperedRefusesStart() throws Exception {
        IntegrityEnvelope env = keyedEnvelope();
        ConfigdServer.enforceTopologyDescriptor(3, dataDir, env);
        Path file = dataDir.resolve(DESCRIPTOR);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(file, bytes);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConfigdServer.enforceTopologyDescriptor(3, dataDir, env));
        assertTrue(e.getMessage().toLowerCase().contains("corrupt")
                        || e.getMessage().toLowerCase().contains("tamper"),
                () -> "should flag a corrupt/tampered descriptor: " + e.getMessage());
    }

    @Test
    void descriptorPersistsWithNoOtherFiles() throws Exception {
        System.setProperty(PROP, "0");
        assertThrows(IllegalArgumentException.class, ConfigdServer::resolveShardCount);
        assertFalse(Files.exists(dataDir.resolve(DESCRIPTOR)),
                "a range-rejected boot must not persist a descriptor");
    }
}
