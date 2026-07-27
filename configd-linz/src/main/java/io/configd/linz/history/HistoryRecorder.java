package io.configd.linz.history;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Set;

/**
 * Thread-safe op-history recorder. Enforces unique-token precondition: every non-failed PUT
 * carries a globally-unique value token so reads pin exactly which write, preventing token confusion.
 * All ops get System#nanoTime() timestamp from one monotonic clock.
 */
public final class HistoryRecorder {

    private final List<Op> ops = new CopyOnWriteArrayList<>();
    private final Set<String> putTokens = ConcurrentHashMap.newKeySet();

    /**
     * Records a PUT. Status: OK (200 committed), INFO (ack!=commit), FAIL (rejected).
     * Enforces unique token per non-failed PUT.
     */
    public void recordPut(int client, String key, String token, Op.Status status, long callNs, long retNs) {
        if (status != Op.Status.FAIL && !putTokens.add(token)) {
            throw new IllegalStateException(
                    "duplicate PUT token — recorder unique-value precondition violated: " + token);
        }
        ops.add(new Op(client, key, Op.Type.PUT, token, status, callNs, retNs));
    }

    public void recordDelete(int client, String key, Op.Status status, long callNs, long retNs) {
        ops.add(new Op(client, key, Op.Type.DELETE, "", status, callNs, retNs));
    }

    /**
     * Records a linearizable READ. Observed token ("" for 404/absent) when status OK;
     * status INFO for indeterminate (503/timeout)—dropped from checked history.
     */
    public void recordRead(int client, String key, String observed, Op.Status status, long callNs, long retNs) {
        ops.add(new Op(client, key, Op.Type.READ, observed == null ? "" : observed, status, callNs, retNs));
    }
    public void record(Op op) {
        if (op.type() == Op.Type.PUT && op.status() != Op.Status.FAIL && !putTokens.add(op.value())) {
            throw new IllegalStateException(
                    "duplicate PUT token — recorder unique-value precondition violated: " + op.value());
        }
        ops.add(op);
    }
    public List<Op> ops() {
        return new ArrayList<>(ops);
    }

    public int size() {
        return ops.size();
    }
}
