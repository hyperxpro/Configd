package io.configd.distribution.fanout;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared slow-consumer test probe: a {@link FanOutSessionMetrics} that counts the
 * slow-consumer policy series plus a {@link SlowConsumerGovernor.TransitionEvent}
 * recorder, so every test asserts both halves of the contract, that each transition
 * produces both a metric and a structured log event, against the same run.
 */
final class RecordingPolicyProbe implements FanOutSessionMetrics {

    int slowTransitions;
    int quarantines;
    int unhealthy;
    int reconnectsRefused;
    int readmissions;

    int lastHealthy;
    int lastSlow;
    int lastCatchup;
    int lastQuarantined;
    int lastUnhealthy;

    final List<SlowConsumerGovernor.TransitionEvent> transitions = new ArrayList<>();

    /** The governor transition listener (pass to the governor constructor). */
    void onTransition(SlowConsumerGovernor.TransitionEvent event) {
        transitions.add(event);
    }

    SlowConsumerGovernor.TransitionEvent lastTransition() {
        if (transitions.isEmpty()) {
            throw new AssertionError("no transition was recorded");
        }
        return transitions.get(transitions.size() - 1);
    }

    // Fan-out series: unused by the governor, but required by the interface.

    @Override public void onNotifyBatch(int n, int bytes) { }
    @Override public void onQueueDepth(int depth) { }
    @Override public void onSlowConsumerWarning() { }
    @Override public void onDemotion(String reason) { }
    @Override public void onSnapshotTransfer() { }
    @Override public void onHeartbeat() { }
    @Override public void onSessionClosed(String reason) { }

    @Override public void onSlowTransition() {
        slowTransitions++;
    }

    @Override public void onQuarantine() {
        quarantines++;
    }

    @Override public void onUnhealthy() {
        unhealthy++;
    }

    @Override public void onReconnectRefused() {
        reconnectsRefused++;
    }

    @Override public void onReadmission() {
        readmissions++;
    }

    @Override public void onConsumerStates(int healthy, int slow, int catchup,
                                           int quarantined, int unhealthy) {
        this.lastHealthy = healthy;
        this.lastSlow = slow;
        this.lastCatchup = catchup;
        this.lastQuarantined = quarantined;
        this.lastUnhealthy = unhealthy;
    }
}
