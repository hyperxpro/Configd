package io.configd.linz.runner;

import io.configd.linz.check.PorcupineChecker;
import io.configd.linz.check.Verdict;
import io.configd.linz.history.Op;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a <b>simulation-produced</b> op-history with the same trusted Porcupine
 * checker the real-binary harness uses.
 *
 * <p>The adversarial deterministic sim (configd-testkit {@code AdversarialSim} +
 * {@code HistoryRecorder}) emits a checker-neutral JSON-Lines history (fields
 * {@code client_id, op_type, key, arg, ret, invoke_ts, response_ts, status,
 * consistency}). This adapter reads that {@code .jsonl}, maps each line to the linz
 * {@link Op} model, and runs it through the very same {@link PorcupineChecker} ->
 * {@code porcupine-check} binary that gates the real-binary runs. So the cheap,
 * fully-replayable sim history is checked by the same trusted third-party checker,
 * not a second hand-rolled one.
 *
 * <p>Mapping (identical semantics to the real harness):
 * <ul>
 *   <li>{@code op_type} PUT/DELETE -> write; READ -> read.</li>
 *   <li>{@code status} ok->OK, info->INFO, fail->FAIL (the checker-neutral kinds).</li>
 *   <li>a write's value is its {@code arg} token; a read's value is its {@code ret}
 *       (observed token, or {@code ""} for bottom/absent).</li>
 *   <li>{@code invoke_ts}/{@code response_ts} are the real-time backbone
 *       (the sim's monotonic, total {@code SimulatedClock}).</li>
 * </ul>
 * The downstream {@link io.configd.linz.history.PorcupineHistoryWriter} then applies
 * the identical drop/float/confirm-bound encoding pinned by the checker self-test.
 *
 * <p>Usage: {@code SimHistoryCheck <history.jsonl>}. Exit 0 LINEARIZABLE,
 * 1 NON-LINEARIZABLE, 2 INDETERMINATE/IO.
 */
public final class SimHistoryCheck {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: SimHistoryCheck <sim-history.jsonl>");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        List<Op> ops = parse(Files.readAllLines(in));
        System.out.println("[sim-check] parsed " + ops.size() + " ops from " + in);

        PorcupineChecker checker = PorcupineChecker.fromEnvironment();
        PorcupineChecker.Result r = checker.check(ops);
        System.out.print(r.stdout());
        if (!r.stderr().isBlank()) {
            System.out.println("  checker stderr: " + r.stderr().strip());
        }
        System.out.println("[sim-check] VERDICT -> " + r.verdict());
        System.exit(r.verdict() == Verdict.LINEARIZABLE ? 0
                : r.verdict() == Verdict.NON_LINEARIZABLE ? 1 : 2);
    }

    /** Parses the sim's checker-neutral JSON-Lines into linz {@link Op}s. */
    static List<Op> parse(List<String> lines) {
        List<Op> ops = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String opType = strField(line, "op_type");
            String key = strField(line, "key");
            String arg = strField(line, "arg");          // write token (null for reads)
            String ret = strField(line, "ret");          // observed token (null for writes / bottom)
            String status = strField(line, "status");
            int client = (int) longField(line, "client_id");
            long invoke = longField(line, "invoke_ts");
            long response = longField(line, "response_ts");

            Op.Type type = switch (opType) {
                case "PUT" -> Op.Type.PUT;
                case "DELETE" -> Op.Type.DELETE;
                case "READ" -> Op.Type.READ;
                default -> throw new IllegalArgumentException("unknown op_type: " + opType + " in " + line);
            };
            Op.Status st = switch (status) {
                case "ok" -> Op.Status.OK;
                case "info" -> Op.Status.INFO;
                case "fail" -> Op.Status.FAIL;
                default -> throw new IllegalArgumentException("unknown status: " + status + " in " + line);
            };
            // A write carries its arg token; a read carries the observed ret (bottom => "").
            String value = (type == Op.Type.READ)
                    ? (ret == null ? "" : ret)
                    : (arg == null ? "" : arg);
            ops.add(new Op(client, key, type, value, st, invoke, response));
        }
        return ops;
    }

    /** Extracts a JSON string field's value, or null for a JSON {@code null}. */
    private static String strField(String line, String field) {
        String needle = '"' + field + "\":";
        int i = line.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int v = i + needle.length();
        // skip spaces
        while (v < line.length() && line.charAt(v) == ' ') {
            v++;
        }
        if (line.startsWith("null", v)) {
            return null;
        }
        if (line.charAt(v) != '"') {
            return null; // not a string field
        }
        v++; // past opening quote
        StringBuilder sb = new StringBuilder();
        while (v < line.length()) {
            char c = line.charAt(v);
            if (c == '\\' && v + 1 < line.length()) {
                char n = line.charAt(v + 1);
                sb.append(switch (n) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> n; // \" \\ etc.
                });
                v += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
            v++;
        }
        return sb.toString();
    }

    /** Extracts a JSON numeric field's value. */
    private static long longField(String line, String field) {
        String needle = '"' + field + "\":";
        int i = line.indexOf(needle);
        if (i < 0) {
            throw new IllegalArgumentException("missing numeric field " + field + " in " + line);
        }
        int v = i + needle.length();
        while (v < line.length() && line.charAt(v) == ' ') {
            v++;
        }
        int start = v;
        if (v < line.length() && (line.charAt(v) == '-' || line.charAt(v) == '+')) {
            v++;
        }
        while (v < line.length() && Character.isDigit(line.charAt(v))) {
            v++;
        }
        return Long.parseLong(line.substring(start, v));
    }

    private SimHistoryCheck() {}
}
