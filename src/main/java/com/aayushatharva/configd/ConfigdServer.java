package com.aayushatharva.configd;

import com.aayushatharva.configd.node.NodeMode;
import com.aayushatharva.configd.node.NodeRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for the Configd server.
 *
 * Bootstraps one or more Configd instances (Cloudflare runs 10 per server)
 * based on the provided configuration. Each instance operates independently
 * with its own storage, transaction log, replication, and cache.
 *
 * <h2>Architecture Overview</h2>
 * <pre>
 *                    ┌─────────────┐
 *                    │   ROOT      │  Writes ingested here
 *                    │  (Replica)  │
 *                    └──────┬──────┘
 *                           │ Replication (pull-based, sequential consistency)
 *              ┌────────────┼────────────┐
 *              │            │            │
 *        ┌─────┴─────┐┌─────┴─────┐┌─────┴─────┐
 *        │INTERMEDIATE││INTERMEDIATE││INTERMEDIATE│
 *        │ (Replica)  ││ (Replica)  ││ (Replica)  │
 *        └─────┬──────┘└─────┬──────┘└─────┬──────┘
 *              │             │             │
 *    ┌─────────┼───────┐    │    ┌─────────┼───────┐
 *    │         │       │    │    │         │       │
 *  ┌─┴──┐  ┌──┴─┐  ┌──┴─┐ │  ┌─┴──┐  ┌──┴─┐  ┌──┴─┐
 *  │LEAF│  │LEAF│  │LEAF│ │  │LEAF│  │LEAF│  │LEAF│
 *  │Rly │  │Prx │  │Prx │ │  │Rly │  │Prx │  │Prx │
 *  └────┘  └────┘  └────┘ │  └────┘  └────┘  └────┘
 *                          │
 *    Data Center A         │           Data Center C
 *                    Data Center B
 * </pre>
 *
 * <h2>Three-Tier Cache (v2)</h2>
 * <pre>
 *   L1: Local per-server cache    (in-memory LRU, microsecond access)
 *   L2: Data-center sharded cache (distributed across servers, 1024 shards)
 *   L3: Full replicas             (complete dataset, fallback for cold keys)
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar configd.jar [config.json]
 *   java -jar configd.jar --role ROOT --mode REPLICA --port 7400
 * </pre>
 */
public class ConfigdServer {

    private static final Logger log = LoggerFactory.getLogger(ConfigdServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ConfigdInstance> instances = new ArrayList<>();
    private final ConfigdConfig config;

    public ConfigdServer(ConfigdConfig config) {
        this.config = config;
    }

    public void start() throws Exception {
        log.info("=== Configd Server Starting ===");
        log.info("Node ID: {}", config.getNodeId());
        log.info("Role: {}, Mode: {}", config.getRole(), config.getMode());
        log.info("Data Center: {}, Region: {}", config.getDataCenter(), config.getRegion());
        log.info("Instances: {}", config.getInstanceCount());
        log.info("Logical Shards: {}, Sharding Factor: {}",
                config.getLogicalShardCount(), config.getShardingFactor());

        for (int i = 0; i < config.getInstanceCount(); i++) {
            var instanceConfig = createInstanceConfig(i);
            var instance = new ConfigdInstance("instance-" + i, instanceConfig);
            instance.start();
            instances.add(instance);
        }

        log.info("=== Configd Server Started ({} instances) ===", instances.size());

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            shutdown();
        }));
    }

    /** Create per-instance configuration with unique ports and directories. */
    private ConfigdConfig createInstanceConfig(int instanceIndex) {
        var ic = new ConfigdConfig();
        ic.setNodeId(config.getNodeId() + "-" + instanceIndex);
        ic.setHost(config.getHost());
        ic.setInternalPort(config.getInternalPort() + instanceIndex);
        ic.setApiPort(config.getApiPort() + instanceIndex);
        ic.setGossipPort(config.getGossipPort() + instanceIndex);
        ic.setRole(config.getRole());
        ic.setMode(config.getMode());
        ic.setDataCenter(config.getDataCenter());
        ic.setRegion(config.getRegion());
        ic.setDataDir(config.getDataDir());
        ic.setTxLogDir(config.getTxLogDir());
        ic.setUpstreamNodes(config.getUpstreamNodes());
        ic.setReplicationPullIntervalMs(config.getReplicationPullIntervalMs());
        ic.setBatchWriteIntervalMs(config.getBatchWriteIntervalMs());
        ic.setReplicationBatchSize(config.getReplicationBatchSize());
        ic.setStalenessThresholdMs(config.getStalenessThresholdMs());
        ic.setLogicalShardCount(config.getLogicalShardCount());
        ic.setShardingFactor(config.getShardingFactor());
        ic.setLocalCacheMaxBytes(config.getLocalCacheMaxBytes());
        ic.setShardedCacheMaxBytes(config.getShardedCacheMaxBytes());
        ic.setEvictionSoftLimitRatio(config.getEvictionSoftLimitRatio());
        ic.setEvictionHardLimitRatio(config.getEvictionHardLimitRatio());
        ic.setMvccRetentionMs(config.getMvccRetentionMs());
        ic.setGossipIntervalMs(config.getGossipIntervalMs());
        ic.setGossipSeeds(config.getGossipSeeds());
        ic.setSlidingWindowDurationMs(config.getSlidingWindowDurationMs());
        return ic;
    }

    public void shutdown() {
        for (var instance : instances) {
            try {
                instance.close();
            } catch (IOException e) {
                log.error("Error shutting down instance {}", instance.getInstanceId(), e);
            }
        }
        log.info("=== Configd Server Stopped ===");
    }

    public List<ConfigdInstance> getInstances() {
        return instances;
    }

    // --- Main ---

    public static void main(String[] args) throws Exception {
        ConfigdConfig config;

        if (args.length > 0 && !args[0].startsWith("--")) {
            // Load from config file
            config = ConfigdConfig.load(Path.of(args[0]));
        } else {
            // Parse CLI arguments
            config = parseArgs(args);
        }

        var server = new ConfigdServer(config);
        server.start();

        // Keep running
        Thread.currentThread().join();
    }

    private static ConfigdConfig parseArgs(String[] args) {
        var config = ConfigdConfig.defaults();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--node-id" -> config.setNodeId(args[++i]);
                case "--host" -> config.setHost(args[++i]);
                case "--port" -> config.setInternalPort(Integer.parseInt(args[++i]));
                case "--api-port" -> config.setApiPort(Integer.parseInt(args[++i]));
                case "--role" -> config.setRole(NodeRole.valueOf(args[++i].toUpperCase()));
                case "--mode" -> config.setMode(NodeMode.valueOf(args[++i].toUpperCase()));
                case "--data-center" -> config.setDataCenter(args[++i]);
                case "--region" -> config.setRegion(args[++i]);
                case "--data-dir" -> config.setDataDir(args[++i]);
                case "--txlog-dir" -> config.setTxLogDir(args[++i]);
                case "--upstream" -> config.setUpstreamNodes(List.of(args[++i].split(",")));
                case "--instances" -> config.setInstanceCount(Integer.parseInt(args[++i]));
                case "--gossip-seeds" -> config.setGossipSeeds(List.of(args[++i].split(",")));
                default -> {
                    if (args[i].startsWith("--")) {
                        log.warn("Unknown argument: {}", args[i]);
                    }
                }
            }
        }
        return config;
    }
}
