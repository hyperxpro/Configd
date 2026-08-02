package io.configd.linz.runner;

import io.configd.linz.check.PorcupineChecker;
import io.configd.linz.check.Verdict;
import io.configd.linz.history.Op;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sim-produced op-history checked via same trusted Porcupine as real harness.
 * Sim (testkit AdversarialSim) emits JSON-Lines; adapter maps to linz Op model,
 * runs through real PorcupineChecker (not hand-rolled). Encoding pinned by self-test.
 * Exit: 0 LINEARIZABLE, 1 NON-LINEARIZABLE, 2 INDETERMINATE/IO.
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

    static List<Op> parse(List<String> lines) {
        List<Op> ops = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String opType = strField(line, "op_type");
            String key = strField(line, "key");
            String arg = strField(line, "arg");
            String ret = strField(line, "ret");
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
            // Write carries arg token; read carries observed ret ("" for bottom)
            String value = (type == Op.Type.READ)
                    ? (ret == null ? "" : ret)
                    : (arg == null ? "" : arg);
            ops.add(new Op(client, key, type, value, st, invoke, response));
        }
        return ops;
    }
    private static String strField(String line, String field) {
        String needle = '"' + field + "\":";
        int i = line.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int v = i + needle.length();
        while (v < line.length() && line.charAt(v) == ' ') {
            v++;
        }
        if (line.startsWith("null", v)) {
            return null;
        }
        if (line.charAt(v) != '"') {
            return null;
        }
        v++;
        StringBuilder sb = new StringBuilder();
        while (v < line.length()) {
            char c = line.charAt(v);
            if (c == '\\' && v + 1 < line.length()) {
                char n = line.charAt(v + 1);
                sb.append(switch (n) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> n;
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
