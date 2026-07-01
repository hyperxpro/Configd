package io.configd.linz.history;

/**
 * One recorded client operation in a checker-neutral op-history.
 *
 * <p>Timestamps are {@link System#nanoTime()} values from the single driver JVM
 * (one monotonic clock): {@code callNs} immediately before the
 * request is sent, {@code retNs} immediately after the full response (or the
 * give-up instant for an indeterminate op).
 *
 * <p>{@code value}: for a PUT, the globally-unique write token; for a READ, the
 * observed token (or {@code ""} for an absent/deleted key); for a DELETE,
 * {@code ""} (a write of bottom).
 *
 * <p>{@code status} is the checker-neutral outcome - see {@link Status}. The
 * mapping from status to the Porcupine encoding (which ops are dropped, which
 * writes float) lives in {@link PorcupineHistoryWriter} and is pinned by the
 * checker self-test.
 */
public record Op(int client, String key, Type type, String value, Status status, long callNs, long retNs) {

    public enum Type { PUT, DELETE, READ }

    /**
     * Checker-neutral op outcome.
     *
     * <ul>
     *   <li>{@code OK} - a read that observed a definite value (the real-time backbone).
     *   <li>{@code INFO} - indeterminate: a write that was accepted (ack != commit, so it
     *       may or may not have committed) or timed out / was killed mid-flight; or a
     *       linearizable read that returned 503/timeout (no definite value).
     *   <li>{@code FAIL} - a definite non-occurrence: rejected at/before propose
     *       (NotLeader / 400 / 403 / 429). The op did not happen.
     * </ul>
     */
    public enum Status { OK, INFO, FAIL }

    public Op {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null (use \"\" for bottom)");
        }
    }
}
