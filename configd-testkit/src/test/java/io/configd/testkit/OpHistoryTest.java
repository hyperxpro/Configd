package io.configd.testkit;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * section 4.4 / design section 6: the adversarial sim emits a checker-neutral invoke/ok/fail/info
 * op-history in the exact format the configd-linz Porcupine checker already
 * consumes from the real-binary harness, so the B5 linz round can check sim
 * histories without re-instrumenting. This test runs one sim with a
 * {@link HistoryRecorder}, validates the format, and writes a sample
 * {@code history-<seed>.jsonl} for B5 to consume.
 */
class OpHistoryTest {

    private static final int NODES = 5;
    private static final int TICKS = 1_500;

    @Test
    void emitsCheckerNeutralOpHistory() throws Exception {
        long seed = 4242L;
        HistoryRecorder recorder = new HistoryRecorder();
        AdversarialSim sim = new AdversarialSim(
                seed, NODES, TICKS, AdversarialSchedule.defaultIntensity(), recorder);
        sim.run();

        assertFalse(recorder.entries().isEmpty(), "history must record ops");

        boolean sawWrite = false;
        boolean sawRead = false;
        for (HistoryRecorder.Entry e : recorder.entries()) {
            // Status must be one of the checker-neutral kinds.
            assertTrue(e.status().equals("ok") || e.status().equals("info")
                            || e.status().equals("fail"),
                    "status must be ok/info/fail, got: " + e.status());
            // Real-time backbone present and ordered (invoke <= response).
            assertTrue(e.responseTs() >= e.invokeTs(), "response_ts >= invoke_ts");
            if (e.opType().equals("PUT") || e.opType().equals("DELETE")) {
                // Ack semantics: an accepted write is :info (ack != commit).
                assertTrue(e.status().equals("info") || e.status().equals("fail"),
                        "writes are info (accepted) or fail (rejected), got " + e.status());
                sawWrite = true;
            } else if (e.opType().equals("READ")) {
                assertEquals("ok", e.status(), "reads are :ok (the real-time backbone)");
                assertEquals("linearizable", e.consistency());
                sawRead = true;
            }
        }
        assertTrue(sawWrite, "history must contain write ops");
        assertTrue(sawRead, "history must contain read ops");

        // JSON Lines: one well-formed object per line.
        String jsonl = recorder.toJsonl();
        String[] lines = jsonl.split("\n");
        assertEquals(recorder.entries().size(), lines.length, "one JSON line per op");
        for (String line : lines) {
            assertTrue(line.startsWith("{") && line.endsWith("}"), "each line a JSON object");
            assertTrue(line.contains("\"invoke_ts\":") && line.contains("\"op_type\":"),
                    "line carries the required fields");
        }

        // Write a sample for B5 (linz) to consume. Target a stable repo path under
        // the module's target dir; also drop a copy into the session captures.
        Path out = Path.of("target", "sim-histories");
        Files.createDirectories(out);
        Files.writeString(out.resolve("history-" + seed + ".jsonl"), jsonl);

        Path capture = Path.of("..", "docs", "session-2", "captures",
                "sim-history-sample.jsonl");
        if (Files.isDirectory(capture.getParent())) {
            // Keep the committed sample small and stable: first 25 ops.
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < Math.min(25, lines.length); i++) {
                head.append(lines[i]).append('\n');
            }
            Files.writeString(capture, head.toString());
        }
    }
}
