package com.aayushatharva.configd;

import com.aayushatharva.configd.node.NodeMode;
import com.aayushatharva.configd.node.NodeRole;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for a Configd node.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigdConfig {

    // --- Identity ---
    private String nodeId = "qs-node-1";
    private String host = "127.0.0.1";
    private int internalPort = 7400;
    private int apiPort = 7401;
    private NodeRole role = NodeRole.LEAF;
    private NodeMode mode = NodeMode.PROXY;
    private String dataCenter = "dc1";
    private String region = "us-east";

    // --- Storage ---
    private String dataDir = "/tmp/configd/data";
    private String txLogDir = "/tmp/configd/txlog";

    // --- Replication ---
    private List<String> upstreamNodes = List.of();
    private long replicationPullIntervalMs = 1000;
    private long batchWriteIntervalMs = 500;
    private int replicationBatchSize = 1000;
    private long stalenessThresholdMs = 30_000;

    // --- Sharding (v2) ---
    private int logicalShardCount = 1024;
    private int shardingFactor = 1;

    // --- Cache ---
    private long localCacheMaxBytes = 256 * 1024 * 1024L;  // 256 MB
    private long shardedCacheMaxBytes = 1024 * 1024 * 1024L; // 1 GB
    private double evictionSoftLimitRatio = 0.8;
    private double evictionHardLimitRatio = 0.95;

    // --- MVCC ---
    private long mvccRetentionMs = 2 * 60 * 60 * 1000L; // 2 hours

    // --- Gossip ---
    private int gossipPort = 7402;
    private long gossipIntervalMs = 1000;
    private List<String> gossipSeeds = List.of();

    // --- Sliding Window ---
    private long slidingWindowDurationMs = 60_000;

    // --- Instances ---
    private int instanceCount = 1;

    public static ConfigdConfig load(Path configFile) throws IOException {
        if (Files.exists(configFile)) {
            return new ObjectMapper().readValue(configFile.toFile(), ConfigdConfig.class);
        }
        return new ConfigdConfig();
    }

    public static ConfigdConfig defaults() {
        return new ConfigdConfig();
    }

    // --- Getters and Setters ---

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getInternalPort() { return internalPort; }
    public void setInternalPort(int internalPort) { this.internalPort = internalPort; }

    public int getApiPort() { return apiPort; }
    public void setApiPort(int apiPort) { this.apiPort = apiPort; }

    public NodeRole getRole() { return role; }
    public void setRole(NodeRole role) { this.role = role; }

    public NodeMode getMode() { return mode; }
    public void setMode(NodeMode mode) { this.mode = mode; }

    public String getDataCenter() { return dataCenter; }
    public void setDataCenter(String dataCenter) { this.dataCenter = dataCenter; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public String getTxLogDir() { return txLogDir; }
    public void setTxLogDir(String txLogDir) { this.txLogDir = txLogDir; }

    public List<String> getUpstreamNodes() { return upstreamNodes; }
    public void setUpstreamNodes(List<String> upstreamNodes) { this.upstreamNodes = upstreamNodes; }

    public long getReplicationPullIntervalMs() { return replicationPullIntervalMs; }
    public void setReplicationPullIntervalMs(long v) { this.replicationPullIntervalMs = v; }

    public long getBatchWriteIntervalMs() { return batchWriteIntervalMs; }
    public void setBatchWriteIntervalMs(long v) { this.batchWriteIntervalMs = v; }

    public int getReplicationBatchSize() { return replicationBatchSize; }
    public void setReplicationBatchSize(int v) { this.replicationBatchSize = v; }

    public long getStalenessThresholdMs() { return stalenessThresholdMs; }
    public void setStalenessThresholdMs(long v) { this.stalenessThresholdMs = v; }

    public int getLogicalShardCount() { return logicalShardCount; }
    public void setLogicalShardCount(int v) { this.logicalShardCount = v; }

    public int getShardingFactor() { return shardingFactor; }
    public void setShardingFactor(int v) { this.shardingFactor = v; }

    public long getLocalCacheMaxBytes() { return localCacheMaxBytes; }
    public void setLocalCacheMaxBytes(long v) { this.localCacheMaxBytes = v; }

    public long getShardedCacheMaxBytes() { return shardedCacheMaxBytes; }
    public void setShardedCacheMaxBytes(long v) { this.shardedCacheMaxBytes = v; }

    public double getEvictionSoftLimitRatio() { return evictionSoftLimitRatio; }
    public void setEvictionSoftLimitRatio(double v) { this.evictionSoftLimitRatio = v; }

    public double getEvictionHardLimitRatio() { return evictionHardLimitRatio; }
    public void setEvictionHardLimitRatio(double v) { this.evictionHardLimitRatio = v; }

    public long getMvccRetentionMs() { return mvccRetentionMs; }
    public void setMvccRetentionMs(long v) { this.mvccRetentionMs = v; }

    public int getGossipPort() { return gossipPort; }
    public void setGossipPort(int v) { this.gossipPort = v; }

    public long getGossipIntervalMs() { return gossipIntervalMs; }
    public void setGossipIntervalMs(long v) { this.gossipIntervalMs = v; }

    public List<String> getGossipSeeds() { return gossipSeeds; }
    public void setGossipSeeds(List<String> v) { this.gossipSeeds = v; }

    public long getSlidingWindowDurationMs() { return slidingWindowDurationMs; }
    public void setSlidingWindowDurationMs(long v) { this.slidingWindowDurationMs = v; }

    public int getInstanceCount() { return instanceCount; }
    public void setInstanceCount(int v) { this.instanceCount = v; }
}
