package io.configd.linz.history;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Set;

/**
 * Thread-safe, checker-neutral op-history recorder. Concurrent clients append to
 * it from many threads; the single driver JVM gives every op a {@link System#nanoTime()}
 * timestamp from one monotonic clock.
 *
 * <p>Enforces the <b>unique-token precondition</b>: every non-failed PUT must carry
 * a globally-unique value token, so a read can pin exactly which write it observed
 * and two writes of the same bytes can never be confused. A reused token is a
 * recorder bug and throws immediately.
 */
public final class HistoryRecorder {

    private final List<Op> ops = new CopyOnWriteArrayList<>();
    private final Set<String> putTokens = ConcurrentHashMap.newKeySet();

    /** Records a PUT. {@code status} is INFO when accepted (ack != commit) or FAIL when rejected. */
    public void recordPut(int client, String key, String token, Op.Status status, long callNs, long retNs) {
        if (status != Op.Status.FAIL && !putTokens.add(token)) {
            throw new IllegalStateException(
                    "duplicate PUT token — recorder unique-value precondition violated: " + token);
        }
        ops.add(new Op(client, key, Op.Type.PUT, token, status, callNs, retNs));
    }

    /** Records a DELETE (a write of bottom). */
    public void recordDelete(int client, String key, Op.Status status, long callNs, long retNs) {
        ops.add(new Op(client, key, Op.Type.DELETE, "", status, callNs, retNs));
    }

    /**
     * Records a linearizable READ. {@code observed} is the token read (or {@code ""}
     * for 404/absent) when {@code status} is OK; for an indeterminate read
     * (503/timeout) pass status INFO - it carries no definite value and is dropped
     * from the checked history.
     */
    public void recordRead(int client, String key, String observed, Op.Status status, long callNs, long retNs) {
        ops.add(new Op(client, key, Op.Type.READ, observed == null ? "" : observed, status, callNs, retNs));
    }

    /** Appends a pre-built op (used by the self-test to feed synthetic histories). */
    public void record(Op op) {
        if (op.type() == Op.Type.PUT && op.status() != Op.Status.FAIL && !putTokens.add(op.value())) {
            throw new IllegalStateException(
                    "duplicate PUT token — recorder unique-value precondition violated: " + op.value());
        }
        ops.add(op);
    }

    /** A snapshot copy of the recorded ops, in append order. */
    public List<Op> ops() {
        return new ArrayList<>(ops);
    }

    public int size() {
        return ops.size();
    }
}
