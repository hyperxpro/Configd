package com.aayushatharva.configd.replication;

/**
 * Tracks the replication state for a downstream connection.
 */
public class ReplicationState {

    private volatile long lastAcknowledgedSequence;
    private volatile long lastPullTimestamp;
    private volatile boolean healthy = true;

    public ReplicationState(long initialSequence) {
        this.lastAcknowledgedSequence = initialSequence;
        this.lastPullTimestamp = System.currentTimeMillis();
    }

    public long getLastAcknowledgedSequence() {
        return lastAcknowledgedSequence;
    }

    public void setLastAcknowledgedSequence(long seq) {
        this.lastAcknowledgedSequence = seq;
        this.lastPullTimestamp = System.currentTimeMillis();
    }

    public long getLastPullTimestamp() {
        return lastPullTimestamp;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public long lagMs() {
        return System.currentTimeMillis() - lastPullTimestamp;
    }
}
