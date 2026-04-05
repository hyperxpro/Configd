package com.aayushatharva.configd.discovery;

import com.aayushatharva.configd.node.NodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Monitors the health of peer nodes using periodic heartbeats.
 *
 * Tracks round-trip times for closest-node selection and detects
 * node failures for automatic failover.
 */
public class HealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);
    private static final long HEALTH_CHECK_INTERVAL_MS = 5000;
    private static final long NODE_TIMEOUT_MS = 15000;

    private final ConcurrentHashMap<String, NodeHealth> nodeHealthMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public record NodeHealth(
            NodeInfo node,
            long lastHeartbeatMs,
            long rttNanos,
            boolean alive,
            int consecutiveFailures
    ) {}

    public HealthMonitor() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cd-health-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::checkAll, HEALTH_CHECK_INTERVAL_MS,
                HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Health monitor started");
    }

    /** Record a successful heartbeat from a node. */
    public void recordHeartbeat(String nodeId, NodeInfo node, long rttNanos) {
        nodeHealthMap.put(nodeId, new NodeHealth(
                node, System.currentTimeMillis(), rttNanos, true, 0));
    }

    /** Record a failed health check. */
    public void recordFailure(String nodeId) {
        nodeHealthMap.computeIfPresent(nodeId, (id, health) -> {
            int failures = health.consecutiveFailures() + 1;
            boolean alive = failures < 3;
            if (!alive) {
                log.warn("Node {} marked as dead after {} consecutive failures", id, failures);
            }
            return new NodeHealth(health.node(), health.lastHeartbeatMs(),
                    health.rttNanos(), alive, failures);
        });
    }

    public boolean isAlive(String nodeId) {
        var health = nodeHealthMap.get(nodeId);
        return health != null && health.alive();
    }

    public long getRtt(String nodeId) {
        var health = nodeHealthMap.get(nodeId);
        return health != null ? health.rttNanos() : Long.MAX_VALUE;
    }

    public Map<String, NodeHealth> getAllHealth() {
        return Map.copyOf(nodeHealthMap);
    }

    private void checkAll() {
        long now = System.currentTimeMillis();
        nodeHealthMap.forEach((nodeId, health) -> {
            if (now - health.lastHeartbeatMs() > NODE_TIMEOUT_MS) {
                recordFailure(nodeId);
            }
        });
    }

    public void stop() {
        scheduler.shutdown();
    }
}
