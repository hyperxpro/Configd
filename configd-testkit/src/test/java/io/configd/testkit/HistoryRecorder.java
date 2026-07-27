package io.configd.testkit;

import java.util.ArrayList;
import java.util.List;

/**
 * Records a checker-neutral operation history from an adversarial sim run, in the
 * exact invoke/ok/fail/info format the configd-linz Porcupine checker already
 * consumes from the real-binary harness. Emitting the same format lets the linz
 * checker check sim histories without re-instrumenting.
 * <p>
 * <b>Ack semantics (gates correctness).</b> A {@code propose}-accepted write is
 * {@code :info} (ack != commit) - it is promoted to "happened" only when a
 * read observes its token. A rejected propose is {@code :fail}. A read that finds a
 * value is {@code :ok} (the real-time linearizability backbone); a read that finds
 * nothing is {@code :ok} of _|_. The real-time backbone is the simulated clock, which
 * is logical but monotonic and total - exactly what Porcupine needs.
 * <p>
 * Output is one JSON object per line ({@code .jsonl}); fields mirror the harness
 * record: {@code client_id, op_type, key, arg, ret, invoke_ts, response_ts, status,
 * consistency}.
 */
final class HistoryRecorder {

    record Entry(int clientId, String opType, String key, String arg, String ret,
                 long invokeTs, long responseTs, String status, String consistency) {}

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Records a write (PUT/DELETE) as a single invoke->info entry. Per ack
     * semantics a leader-accepted write is {@code :info} (may or may not commit); a
     * rejected one is {@code :fail}.
     */
    void recordWriteInvokeAndInfo(long ts, int clientId, String opType, String key,
            String token, boolean accepted) {
        entries.add(new Entry(clientId, opType, key, token, null, ts, ts,
                accepted ? "info" : "fail", "n/a"));
    }

    /**
     * Records a linearizable read. A found value is {@code :ok} carrying the
     * observed token; a not-found is {@code :ok} of _|_ (still a real-time fact).
     */
    void recordRead(long ts, int clientId, String key, String observedTokenOrNull) {
        entries.add(new Entry(clientId, "READ", key, null,
                observedTokenOrNull, ts, ts, "ok", "linearizable"));
    }

    void finish(long endTs) {
        // No-op for now; kept as a stable lifecycle hook in case a closing marker is
        // later needed by the checker.
    }

    List<Entry> entries() {
        return entries;
    }

    String toJsonl() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append('{')
              .append("\"client_id\":").append(e.clientId()).append(',')
              .append("\"op_type\":").append(quote(e.opType())).append(',')
              .append("\"key\":").append(quote(e.key())).append(',')
              .append("\"arg\":").append(quote(e.arg())).append(',')
              .append("\"ret\":").append(quote(e.ret())).append(',')
              .append("\"invoke_ts\":").append(e.invokeTs()).append(',')
              .append("\"response_ts\":").append(e.responseTs()).append(',')
              .append("\"status\":").append(quote(e.status())).append(',')
              .append("\"consistency\":").append(quote(e.consistency()))
              .append('}').append('\n');
        }
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
