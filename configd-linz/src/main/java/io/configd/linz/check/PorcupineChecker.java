package io.configd.linz.check;

import io.configd.linz.history.Op;
import io.configd.linz.history.PorcupineHistoryWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bridge to the trusted third-party checker: shells out to the {@code porcupine-check}
 * Go binary over a serialized history and maps its exit code to a {@link Verdict}.
 *
 * <p>This is the only checker the harness trusts; the harness never decides
 * linearizability itself. The Go binary's
 * contract: exit 0 = LINEARIZABLE, 1 = NON-LINEARIZABLE, anything else =
 * INDETERMINATE.
 */
public final class PorcupineChecker {

    private final Path binary;

    public PorcupineChecker(Path binary) {
        this.binary = binary;
    }

    /**
     * Resolves the checker binary from (in order): the {@code PORCUPINE_BIN} env
     * var, then the module-local {@code bin/porcupine-check}. Throws if neither
     * exists - the harness must not silently "pass" without a real checker.
     */
    public static PorcupineChecker fromEnvironment() {
        String env = System.getenv("PORCUPINE_BIN");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env);
            if (Files.isExecutable(p)) {
                return new PorcupineChecker(p);
            }
        }
        Path local = Path.of("configd-linz", "bin", "porcupine-check");
        if (Files.isExecutable(local)) {
            return new PorcupineChecker(local.toAbsolutePath());
        }
        Path local2 = Path.of("bin", "porcupine-check");
        if (Files.isExecutable(local2)) {
            return new PorcupineChecker(local2.toAbsolutePath());
        }
        throw new IllegalStateException(
                "porcupine-check binary not found — set PORCUPINE_BIN or run "
                        + "configd-linz/scripts/build-porcupine.sh (ADR-0032: a real checker is mandatory)");
    }

    /** The checker's verdict plus its stdout/stderr, for pasting as evidence. */
    public record Result(Verdict verdict, int exitCode, String stdout, String stderr) {}

    /** Serializes {@code ops} to a temp history file and checks it. */
    public Result check(List<Op> ops) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("linz-history-", ".json");
        try {
            PorcupineHistoryWriter.write(ops, tmp);
            return checkFile(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Checks an already-serialized history JSON file. */
    public Result checkFile(Path historyJson) throws IOException, InterruptedException {
        Process proc = new ProcessBuilder(binary.toString(), historyJson.toString())
                .redirectErrorStream(false)
                .start();
        byte[] out = proc.getInputStream().readAllBytes();
        byte[] err = proc.getErrorStream().readAllBytes();
        if (!proc.waitFor(5, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            return new Result(Verdict.INDETERMINATE, -1,
                    new String(out, StandardCharsets.UTF_8),
                    "porcupine-check wall-clock timeout (harness-side)");
        }
        int code = proc.exitValue();
        Verdict v = switch (code) {
            case 0 -> Verdict.LINEARIZABLE;
            case 1 -> Verdict.NON_LINEARIZABLE;
            default -> Verdict.INDETERMINATE;
        };
        return new Result(v, code,
                new String(out, StandardCharsets.UTF_8),
                new String(err, StandardCharsets.UTF_8));
    }

    public Path binary() {
        return binary;
    }
}
