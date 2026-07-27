package io.configd.client.edge;

import java.util.Objects;

/** Subscription tunables. acceptFiltered opt-in for prefix subscriptions only; forced off for full-store. */
public record SubscribeOptions(String edgeId, boolean acceptFiltered) {

    public SubscribeOptions {
        Objects.requireNonNull(edgeId, "edgeId");
    }

    public static SubscribeOptions defaults() {
        return new SubscribeOptions("configd-client", false);
    }

    public SubscribeOptions withEdgeId(String edgeId) {
        return new SubscribeOptions(edgeId, acceptFiltered);
    }

    public SubscribeOptions withAcceptFiltered(boolean acceptFiltered) {
        return new SubscribeOptions(edgeId, acceptFiltered);
    }
}
