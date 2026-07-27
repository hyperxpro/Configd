package io.configd.distribution.fanout;

public interface FanOutSessionMetrics {

    void onNotifyBatch(int n, int bytes);

    void onQueueDepth(int depth);

    void onSlowConsumerWarning();

    void onDemotion(String reason);

    void onSnapshotTransfer();

    void onHeartbeat();

    void onSessionClosed(String reason);

    default void onSubscribeMode(boolean snapshotFirst, long horizonDistance) { }

    default void onSlowTransition() { }

    default void onQuarantine() { }

    default void onUnhealthy() { }

    default void onReconnectRefused() { }

    default void onReadmission() { }

    default void onConsumerStates(int healthy, int slow, int catchup,
                                  int quarantined, int unhealthy) { }

    default void onFilteredDeltas(int n) { }

    default void onDeliveredDeltas(int n) { }

    default void onCursorAdvance() { }

    default void onFilterActive(boolean active) { }

    /** No-op sink - the default for tests and any wiring that does not export metrics. */
    FanOutSessionMetrics NOOP = new FanOutSessionMetrics() {
        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { }
        @Override public void onDemotion(String reason) { }
        @Override public void onSnapshotTransfer() { }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { }
    };
}
