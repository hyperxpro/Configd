package io.configd.client.edge;

import java.util.Objects;

/**
 * Tunables for a {@link ConfigdEdgeClient#subscribeFullStore} / {@code subscribePrefixes} call.
 *
 * @param edgeId         the advisory edge identity placed in the {@code SUBSCRIBE} frame. Over mTLS the server
 *                       overrides it with the certificate DN (§03 AU3-2), so it is diagnostic only.
 * @param acceptFiltered opt into the server-side-filtered fan-out (wire version {@code 0x03}, ADR-0045) for a
 *                       prefix subscription — a dense covered-seq cursor advanced on the HEARTBEAT and a
 *                       forward-only version chain. A full-store subscription MUST NOT set it (a root edge
 *                       wants the whole signed chain); it is forced off there.
 */
public record SubscribeOptions(String edgeId, boolean acceptFiltered) {

    public SubscribeOptions {
        Objects.requireNonNull(edgeId, "edgeId");
    }

    /** Defaults: an anonymous edge id, no server-side filtering. */
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
