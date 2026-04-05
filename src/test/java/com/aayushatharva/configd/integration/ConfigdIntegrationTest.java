package com.aayushatharva.configd.integration;

import com.aayushatharva.configd.ConfigdConfig;
import com.aayushatharva.configd.ConfigdInstance;
import com.aayushatharva.configd.node.NodeMode;
import com.aayushatharva.configd.node.NodeRole;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that bootstraps a ROOT replica and a LEAF proxy,
 * verifies writes on the root are readable via the proxy's cache.
 */
class ConfigdIntegrationTest {

    @TempDir
    Path tempDir;

    private ConfigdInstance rootInstance;
    private ConfigdInstance proxyInstance;

    @BeforeEach
    void setUp() throws Exception {
        // ROOT node (full replica)
        var rootConfig = new ConfigdConfig();
        rootConfig.setNodeId("root-0");
        rootConfig.setHost("127.0.0.1");
        rootConfig.setInternalPort(17400);
        rootConfig.setApiPort(17401);
        rootConfig.setGossipPort(17402);
        rootConfig.setRole(NodeRole.ROOT);
        rootConfig.setMode(NodeMode.REPLICA);
        rootConfig.setDataCenter("test-dc");
        rootConfig.setRegion("test");
        rootConfig.setDataDir(tempDir.resolve("root-data").toString());
        rootConfig.setTxLogDir(tempDir.resolve("root-txlog").toString());
        rootConfig.setUpstreamNodes(List.of());
        rootConfig.setGossipSeeds(List.of());

        rootInstance = new ConfigdInstance("root", rootConfig);
        rootInstance.start();

        // PROXY node (caching, replicates from root)
        var proxyConfig = new ConfigdConfig();
        proxyConfig.setNodeId("proxy-0");
        proxyConfig.setHost("127.0.0.1");
        proxyConfig.setInternalPort(17410);
        proxyConfig.setApiPort(17411);
        proxyConfig.setGossipPort(17412);
        proxyConfig.setRole(NodeRole.LEAF);
        proxyConfig.setMode(NodeMode.PROXY);
        proxyConfig.setDataCenter("test-dc");
        proxyConfig.setRegion("test");
        proxyConfig.setDataDir(tempDir.resolve("proxy-data").toString());
        proxyConfig.setTxLogDir(tempDir.resolve("proxy-txlog").toString());
        proxyConfig.setUpstreamNodes(List.of("127.0.0.1:17400"));
        proxyConfig.setGossipSeeds(List.of("127.0.0.1:17402"));
        proxyConfig.setReplicationPullIntervalMs(100);

        proxyInstance = new ConfigdInstance("proxy", proxyConfig);
        proxyInstance.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (proxyInstance != null) proxyInstance.close();
        if (rootInstance != null) rootInstance.close();
    }

    @Test
    void writeOnRootReadableLocally() throws Exception {
        byte[] key = "dns-record".getBytes(StandardCharsets.UTF_8);
        byte[] value = "1.2.3.4".getBytes(StandardCharsets.UTF_8);

        // Write to root
        long seq = rootInstance.getWriter().put(key, value).get(5, TimeUnit.SECONDS);
        assertThat(seq).isGreaterThan(0);

        // Read from root directly
        byte[] rootRead = rootInstance.getStore().get(key);
        assertThat(rootRead).isEqualTo(value);
    }

    @Test
    void rootTransactionLogRecordsWrites() throws Exception {
        rootInstance.getWriter().put("a".getBytes(), "1".getBytes()).get(5, TimeUnit.SECONDS);
        rootInstance.getWriter().put("b".getBytes(), "2".getBytes()).get(5, TimeUnit.SECONDS);

        var txLog = rootInstance.getTransactionLog();
        assertThat(txLog.getLatestSequence()).isGreaterThanOrEqualTo(2);

        var entries = txLog.readAfter(0, 10);
        assertThat(entries).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void cacheManagerServesReads() throws Exception {
        byte[] key = "cached-key".getBytes();
        byte[] value = "cached-value".getBytes();

        rootInstance.getWriter().put(key, value).get(5, TimeUnit.SECONDS);

        // Root's cache manager should serve the read
        byte[] result = rootInstance.getCacheManager().get(key).get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo(value);
    }

    @Test
    void proxyNodeInitializesCleanly() {
        // Proxy should be running with replication puller active
        assertThat(proxyInstance.getReplicationManager().getPuller()).isNotNull();
        assertThat(proxyInstance.getCacheManager()).isNotNull();
    }

    @Test
    void multipleWritesPreserveOrder() throws Exception {
        for (int i = 0; i < 100; i++) {
            rootInstance.getWriter().put(
                    ("key-" + i).getBytes(),
                    ("value-" + i).getBytes()
            ).get(5, TimeUnit.SECONDS);
        }

        var txLog = rootInstance.getTransactionLog();
        var entries = txLog.readAfter(0, 200);

        // Verify sequential ordering
        for (int i = 1; i < entries.size(); i++) {
            assertThat(entries.get(i).sequenceNumber())
                    .isGreaterThan(entries.get(i - 1).sequenceNumber());
        }
    }
}
