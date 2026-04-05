package com.aayushatharva.configd.discovery;

import com.aayushatharva.configd.network.protocol.Message;
import com.aayushatharva.configd.network.protocol.MessageType;
import com.aayushatharva.configd.node.NodeInfo;
import com.aayushatharva.configd.node.NodeMode;
import com.aayushatharva.configd.node.NodeRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Network Oracle — gossip-based discovery protocol.
 *
 * Implements Configd's self-organized, self-healing discovery system:
 * - Exchanges node status and meta-information in near real-time
 * - Measures round-trip time to identify closest nodes
 * - Enables dynamic topology without hardcoded IP addresses
 *
 * Uses UDP for gossip messages (lightweight, connectionless).
 * Each node periodically selects random peers and exchanges member lists.
 */
public class GossipProtocol implements NodeDiscovery, Closeable {

    private static final Logger log = LoggerFactory.getLogger(GossipProtocol.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_GOSSIP_PEERS = 3;

    private final NodeInfo self;
    private final int gossipPort;
    private final long gossipIntervalMs;
    private final List<InetSocketAddress> seeds;

    private final ConcurrentHashMap<String, MemberEntry> members = new ConcurrentHashMap<>();
    private final HealthMonitor healthMonitor;
    private final ScheduledExecutorService scheduler;
    private EventLoopGroup group;
    private Channel channel;

    public record MemberEntry(NodeInfo node, long lastSeen, long rttNanos) {}

    public GossipProtocol(NodeInfo self, int gossipPort, long gossipIntervalMs,
                          List<String> seedAddresses) {
        this.self = self;
        this.gossipPort = gossipPort;
        this.gossipIntervalMs = gossipIntervalMs;
        this.seeds = seedAddresses.stream()
                .map(addr -> {
                    String[] parts = addr.split(":");
                    return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
                })
                .collect(Collectors.toList());
        this.healthMonitor = new HealthMonitor();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cd-gossip");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() throws InterruptedException {
        group = new NioEventLoopGroup(1);

        var bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, true)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                        handleGossipMessage(packet);
                    }
                });

        channel = bootstrap.bind(gossipPort).sync().channel();

        // Register self
        register(self);

        // Start periodic gossip
        scheduler.scheduleAtFixedRate(this::gossipRound, gossipIntervalMs,
                gossipIntervalMs, TimeUnit.MILLISECONDS);
        healthMonitor.start();

        log.info("Gossip protocol started on port {} with {} seed(s)", gossipPort, seeds.size());
    }

    private void gossipRound() {
        try {
            // Select random peers to gossip with
            var peers = selectGossipPeers();

            // Send our member list to each peer
            byte[] memberListBytes = serializeMemberList();

            for (var peer : peers) {
                sendGossip(peer, memberListBytes);
            }

            // Also gossip with seeds (ensures convergence)
            for (var seed : seeds) {
                sendGossip(seed, memberListBytes);
            }

            // Expire old members
            expireMembers();
        } catch (Exception e) {
            log.debug("Gossip round failed", e);
        }
    }

    private void handleGossipMessage(DatagramPacket packet) {
        try {
            ByteBuf content = packet.content();
            byte[] data = new byte[content.readableBytes()];
            content.readBytes(data);

            // Parse member list
            var receivedMembers = deserializeMemberList(data);
            long receiveTime = System.nanoTime();

            // Merge into our member list
            for (var entry : receivedMembers) {
                var existing = members.get(entry.node().nodeId());
                if (existing == null || entry.lastSeen() > existing.lastSeen()) {
                    members.put(entry.node().nodeId(), new MemberEntry(
                            entry.node(), System.currentTimeMillis(), entry.rttNanos()));
                    healthMonitor.recordHeartbeat(entry.node().nodeId(), entry.node(), entry.rttNanos());
                }
            }

            // Send our list back (pull-push gossip)
            byte[] response = serializeMemberList();
            channel.writeAndFlush(new DatagramPacket(
                    Unpooled.wrappedBuffer(response),
                    packet.sender()
            ));
        } catch (Exception e) {
            log.debug("Failed to process gossip message", e);
        }
    }

    private List<InetSocketAddress> selectGossipPeers() {
        var allPeers = members.values().stream()
                .filter(m -> !m.node().nodeId().equals(self.nodeId()))
                .map(m -> new InetSocketAddress(m.node().host(), gossipPort))
                .collect(Collectors.toList());

        Collections.shuffle(allPeers);
        return allPeers.subList(0, Math.min(MAX_GOSSIP_PEERS, allPeers.size()));
    }

    private void sendGossip(InetSocketAddress target, byte[] data) {
        channel.writeAndFlush(new DatagramPacket(
                Unpooled.wrappedBuffer(data), target));
    }

    private byte[] serializeMemberList() {
        try {
            var list = members.values().stream()
                    .map(m -> Map.of(
                            "nodeId", m.node().nodeId(),
                            "host", m.node().host(),
                            "internalPort", String.valueOf(m.node().internalPort()),
                            "apiPort", String.valueOf(m.node().apiPort()),
                            "role", m.node().role().name(),
                            "mode", m.node().mode().name(),
                            "dataCenter", m.node().dataCenter(),
                            "region", m.node().region(),
                            "lastSeen", String.valueOf(m.lastSeen()),
                            "rtt", String.valueOf(m.rttNanos())
                    ))
                    .collect(Collectors.toList());
            return MAPPER.writeValueAsBytes(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize member list", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<MemberEntry> deserializeMemberList(byte[] data) {
        try {
            var list = MAPPER.readValue(data, List.class);
            var result = new ArrayList<MemberEntry>();
            for (var item : list) {
                var map = (Map<String, String>) item;
                var node = new NodeInfo(
                        map.get("nodeId"),
                        map.get("host"),
                        Integer.parseInt(map.get("internalPort")),
                        Integer.parseInt(map.get("apiPort")),
                        NodeRole.valueOf(map.get("role")),
                        NodeMode.valueOf(map.get("mode")),
                        map.get("dataCenter"),
                        map.get("region")
                );
                result.add(new MemberEntry(node,
                        Long.parseLong(map.get("lastSeen")),
                        Long.parseLong(map.get("rtt"))));
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to deserialize member list", e);
            return List.of();
        }
    }

    private void expireMembers() {
        long cutoff = System.currentTimeMillis() - (gossipIntervalMs * 30);
        members.entrySet().removeIf(e ->
                !e.getKey().equals(self.nodeId()) && e.getValue().lastSeen() < cutoff);
    }

    // --- NodeDiscovery interface ---

    @Override
    public void register(NodeInfo node) {
        members.put(node.nodeId(), new MemberEntry(node, System.currentTimeMillis(), 0));
    }

    @Override
    public void deregister(String nodeId) {
        members.remove(nodeId);
    }

    @Override
    public List<NodeInfo> getKnownNodes() {
        return members.values().stream()
                .map(MemberEntry::node)
                .collect(Collectors.toList());
    }

    @Override
    public List<NodeInfo> getNodesInDataCenter(String dataCenter) {
        return members.values().stream()
                .filter(m -> m.node().dataCenter().equals(dataCenter))
                .map(MemberEntry::node)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<NodeInfo> findClosestNode(String dataCenter) {
        return members.values().stream()
                .filter(m -> m.node().dataCenter().equals(dataCenter))
                .filter(m -> !m.node().nodeId().equals(self.nodeId()))
                .min(Comparator.comparingLong(MemberEntry::rttNanos))
                .map(MemberEntry::node);
    }

    @Override
    public boolean isAlive(String nodeId) {
        return healthMonitor.isAlive(nodeId);
    }

    public HealthMonitor getHealthMonitor() {
        return healthMonitor;
    }

    @Override
    public void close() {
        scheduler.shutdown();
        healthMonitor.stop();
        if (channel != null) channel.close();
        if (group != null) group.shutdownGracefully();
        log.info("Gossip protocol stopped");
    }
}
