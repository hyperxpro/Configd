package io.configd.linz;

import io.configd.linz.check.PorcupineChecker;
import io.configd.linz.check.Verdict;
import io.configd.linz.history.HistoryRecorder;
import io.configd.linz.history.Op;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * GATE (i): the checker self-test suite (design §11.3). The hand-written glue
 * (recorder + status→encoding mapping) is the project's named failure mode, so
 * it is NOT trusted until every synthetic history below produces its pinned
 * verdict through the <b>real</b> {@link io.configd.linz.history.PorcupineHistoryWriter}
 * → real Porcupine pipe — including every flip (info↔fail, info↔ok).
 *
 * <p>Skipped unless {@code PORCUPINE_BIN} is set, so the default {@code ./mvnw test}
 * stays green/fast without a Go toolchain; the gate run sets it explicitly.
 */
@EnabledIfEnvironmentVariable(named = "PORCUPINE_BIN", matches = ".+")
class CheckerSelfTest {

    private final PorcupineChecker checker = PorcupineChecker.fromEnvironment();

    // ---- tiny builders (synthetic histories) -------------------------------
    private static Op put(int c, String k, String tok, Op.Status s, long t) {
        return new Op(c, k, Op.Type.PUT, tok, s, t, t);
    }

    private static Op read(int c, String k, String obs, Op.Status s, long call, long ret) {
        return new Op(c, k, Op.Type.READ, obs, s, call, ret);
    }

    private Verdict verdict(String label, List<Op> ops) throws Exception {
        PorcupineChecker.Result r = checker.check(ops);
        System.out.println("[self-test] " + label + " -> exit=" + r.exitCode());
        System.out.print(r.stdout());
        if (!r.stderr().isBlank()) {
            System.out.println("  stderr: " + r.stderr().strip());
        }
        return r.verdict();
    }

    // 1. Sequential sane -> GREEN
    @Test
    void test1_sequentialSane() throws Exception {
        List<Op> h = List.of(
                put(0, "k", "v1", Op.Status.INFO, 1),
                read(0, "k", "v1", Op.Status.OK, 2, 3),
                put(0, "k", "v2", Op.Status.INFO, 4),
                read(0, "k", "v2", Op.Status.OK, 5, 6));
        assertEquals(Verdict.LINEARIZABLE, verdict("1 sequential-sane", h));
    }

    // 2. Stale-read anomaly (GET v1 after v2 confirmed, no overlap) -> RED
    @Test
    void test2_staleReadAnomaly() throws Exception {
        List<Op> h = List.of(
                put(0, "k", "v1", Op.Status.INFO, 1),
                read(0, "k", "v1", Op.Status.OK, 2, 3),
                put(0, "k", "v2", Op.Status.INFO, 4),
                read(1, "k", "v2", Op.Status.OK, 5, 6),    // confirm v2
                read(2, "k", "v1", Op.Status.OK, 7, 8));   // stale: v1 after v2 confirmed
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("2 stale-read-anomaly", h));
    }

    // 3. THE important one: timed-out write -> info -> GREEN; SAME op -> fail -> RED.
    @Test
    void test3_timeoutInfoNeverFail() throws Exception {
        // info: the timed-out write may have committed; a later read observes it.
        List<Op> asInfo = List.of(
                put(0, "k", "T", Op.Status.INFO, 1),
                read(1, "k", "T", Op.Status.OK, 2, 3));
        assertEquals(Verdict.LINEARIZABLE, verdict("3a timeout-as-INFO", asInfo));

        // the SAME write flipped to fail (the classic homegrown-checker bug):
        // the write is dropped, so the read of T has no writer -> false RED.
        List<Op> asFail = List.of(
                put(0, "k", "T", Op.Status.FAIL, 1),
                read(1, "k", "T", Op.Status.OK, 2, 3));
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("3b timeout-flipped-to-FAIL", asFail));
    }

    // 4. Lin-read 503 -> info (dropped) -> GREEN; flipped to a fabricated :ok value -> RED.
    @Test
    void test4_linRead503Info() throws Exception {
        List<Op> dropped = List.of(
                put(0, "k", "T", Op.Status.INFO, 1),
                read(1, "k", "T", Op.Status.OK, 2, 3),
                read(2, "k", "", Op.Status.INFO, 4, 5));   // 503: indeterminate, dropped
        assertEquals(Verdict.LINEARIZABLE, verdict("4a linread-503-INFO", dropped));

        List<Op> fabricated = List.of(
                put(0, "k", "T", Op.Status.INFO, 1),
                read(1, "k", "T", Op.Status.OK, 2, 3),
                read(2, "k", "FABRICATED", Op.Status.OK, 4, 5));  // pretend 503 returned a value never written
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("4b linread-503-flipped-to-OK", fabricated));
    }

    // 5. A stale default-GET must NOT be a linearizable observation: as INFO -> GREEN;
    //    the same bytes recorded as a linearizable OK read lagging the committed write -> RED.
    @Test
    void test5_staleGetNotLinearizableRead() throws Exception {
        List<Op> staleAsInfo = List.of(
                put(0, "k", "v1", Op.Status.INFO, 1),
                read(0, "k", "v1", Op.Status.OK, 2, 3),
                put(0, "k", "v2", Op.Status.INFO, 4),
                read(1, "k", "v2", Op.Status.OK, 5, 6),
                read(2, "k", "v1", Op.Status.INFO, 7, 8));  // stale default-GET: recorded INFO, dropped
        assertEquals(Verdict.LINEARIZABLE, verdict("5a stale-GET-as-INFO", staleAsInfo));

        List<Op> staleAsLinRead = List.of(
                put(0, "k", "v1", Op.Status.INFO, 1),
                read(0, "k", "v1", Op.Status.OK, 2, 3),
                put(0, "k", "v2", Op.Status.INFO, 4),
                read(1, "k", "v2", Op.Status.OK, 5, 6),
                read(2, "k", "v1", Op.Status.OK, 7, 8));    // same bytes, wrongly as a linearizable read -> RED
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("5b stale-GET-as-linread", staleAsLinRead));
    }

    // 6. Unique-token precondition: reusing a value token fails a recorder assert.
    @Test
    void test6_uniqueTokenPrecondition() {
        HistoryRecorder rec = new HistoryRecorder();
        rec.recordPut(0, "k1", "dup", Op.Status.INFO, 1, 1);
        assertThrows(IllegalStateException.class,
                () -> rec.recordPut(1, "k2", "dup", Op.Status.INFO, 2, 2),
                "reusing a PUT token must fail the recorder precondition");
    }
}
