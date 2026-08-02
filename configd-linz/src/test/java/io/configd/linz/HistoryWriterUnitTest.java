package io.configd.linz;

import io.configd.linz.history.HistoryRecorder;
import io.configd.linz.history.Op;
import io.configd.linz.history.PorcupineHistoryWriter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java coverage of recorder/serializer policy (regression-guarded in CI).
 * Runs in default ./mvnw test (no Go toolchain).
 */
class HistoryWriterUnitTest {

    @Test
    void failOpsAndInfoReadsAreDropped_observedWritePinned() throws Exception {
        List<Op> ops = List.of(
                new Op(0, "k", Op.Type.PUT, "tok", Op.Status.INFO, 10, 10),     // kept, observed -> pinned
                new Op(0, "k", Op.Type.READ, "tok", Op.Status.OK, 20, 30),      // kept, observes tok
                new Op(0, "k", Op.Type.READ, "", Op.Status.INFO, 40, 50),       // dropped: indeterminate read
                new Op(0, "k", Op.Type.PUT, "rejected", Op.Status.FAIL, 60, 60) // dropped: definite non-occurrence
        );
        Path tmp = Files.createTempFile("linz-unit-", ".json");
        try {
            PorcupineHistoryWriter.write(ops, tmp);
            String json = Files.readString(tmp);
            assertEquals(2, json.split("\"client\"").length - 1, json);
            assertFalse(json.contains("\"rejected\""), "FAIL op must be dropped");
            assertFalse(json.contains("\"type\":\"read\",\"value\":\"\""), "INFO read must be dropped");
            // Write pinned to observing read's ret (30), not END
            assertTrue(json.contains("\"type\":\"put\",\"value\":\"tok\",\"call\":10,\"ret\":30"), json);
            assertTrue(json.contains("\"type\":\"read\",\"value\":\"tok\",\"call\":20,\"ret\":30"), json);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void unobservedWriteFloatsToEnd() throws Exception {
        List<Op> ops = List.of(
                new Op(0, "k", Op.Type.PUT, "seen", Op.Status.INFO, 10, 10),
                new Op(0, "k", Op.Type.READ, "seen", Op.Status.OK, 20, 30),
                new Op(0, "k", Op.Type.PUT, "never", Op.Status.INFO, 40, 40)   // no read ever observes "never"
        );
        Path tmp = Files.createTempFile("linz-unit-", ".json");
        try {
            PorcupineHistoryWriter.write(ops, tmp);
            String json = Files.readString(tmp);
            // END = max-ts+1 = 41; unobserved write floats to END
            assertTrue(json.contains("\"type\":\"put\",\"value\":\"never\",\"call\":40,\"ret\":41"), json);
            assertTrue(json.contains("\"type\":\"put\",\"value\":\"seen\",\"call\":10,\"ret\":30"), json);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void uniqueTokenPreconditionEnforced() {
        HistoryRecorder rec = new HistoryRecorder();
        rec.recordPut(0, "k1", "dup", Op.Status.INFO, 1, 1);
        assertThrows(IllegalStateException.class,
                () -> rec.recordPut(1, "k2", "dup", Op.Status.INFO, 2, 2));
    }

    @Test
    void rejectedPutTokenMayRecur() {
        // a FAIL put never "happened", so its token is not consumed.
        HistoryRecorder rec = new HistoryRecorder();
        rec.recordPut(0, "k", "t", Op.Status.FAIL, 1, 1);
        rec.recordPut(0, "k", "t", Op.Status.INFO, 2, 2); // must not throw
        assertEquals(2, rec.size());
    }

    @Test
    void committedOkPutIsKeptAndConfirmBound() throws Exception {
        // A 200 is now an OK (committed) PUT. It is a kept write, encoded
        // exactly like an INFO write - confirm-bound to an observing read, else float.
        List<Op> ops = List.of(
                new Op(0, "k", Op.Type.PUT, "committed", Op.Status.OK, 10, 10),
                new Op(0, "k", Op.Type.READ, "committed", Op.Status.OK, 20, 30) // observes it -> pin to 30
        );
        Path tmp = Files.createTempFile("linz-unit-", ".json");
        try {
            PorcupineHistoryWriter.write(ops, tmp);
            String json = Files.readString(tmp);
            assertEquals(2, json.split("\"client\"").length - 1, json);
            assertTrue(json.contains("\"type\":\"put\",\"value\":\"committed\",\"call\":10,\"ret\":30"), json);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
