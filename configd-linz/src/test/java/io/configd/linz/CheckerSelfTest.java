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
 * Checker self-test: hand-written glue (recorder + encoding) NOT trusted until every
 * synthetic history produces pinned verdict through real PorcupineHistoryWriter+porcupine-check.
 * Skipped unless PORCUPINE_BIN set (default ./mvnw test fast without Go toolchain).
 */
@EnabledIfEnvironmentVariable(named = "PORCUPINE_BIN", matches = ".+")
class CheckerSelfTest {

    private final PorcupineChecker checker = PorcupineChecker.fromEnvironment();

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

    @Test
    void test1_sequentialSane() throws Exception {
        List<Op> h = List.of(
                put(0, "k", "v1", Op.Status.INFO, 1),
                read(0, "k", "v1", Op.Status.OK, 2, 3),
                put(0, "k", "v2", Op.Status.INFO, 4),
                read(0, "k", "v2", Op.Status.OK, 5, 6));
        assertEquals(Verdict.LINEARIZABLE, verdict("1 sequential-sane", h));
    }

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

    @Test
    void test3_timeoutInfoNeverFail() throws Exception {
        List<Op> asInfo = List.of(
                put(0, "k", "T", Op.Status.INFO, 1),
                read(1, "k", "T", Op.Status.OK, 2, 3));
        assertEquals(Verdict.LINEARIZABLE, verdict("3a timeout-as-INFO", asInfo));

        List<Op> asFail = List.of(
                put(0, "k", "T", Op.Status.FAIL, 1),
                read(1, "k", "T", Op.Status.OK, 2, 3));
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("3b timeout-flipped-to-FAIL", asFail));
    }

    @Test
    void test4_linRead503Info() throws Exception {
        List<Op> dropped = List.of(
                put(0, "k", "T", Op.Status.INFO, 1),
                read(1, "k", "T", Op.Status.OK, 2, 3),
                read(2, "k", "", Op.Status.INFO, 4, 5));
        assertEquals(Verdict.LINEARIZABLE, verdict("4a linread-503-INFO", dropped));

        List<Op> fabricated = List.of(
                put(0, "k", "T", Op.Status.INFO, 1),
                read(1, "k", "T", Op.Status.OK, 2, 3),
                read(2, "k", "FABRICATED", Op.Status.OK, 4, 5));
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("4b linread-503-flipped-to-OK", fabricated));
    }

    @Test
    void test5_staleGetNotLinearizableRead() throws Exception {
        List<Op> staleAsInfo = List.of(
                put(0, "k", "v1", Op.Status.INFO, 1),
                read(0, "k", "v1", Op.Status.OK, 2, 3),
                put(0, "k", "v2", Op.Status.INFO, 4),
                read(1, "k", "v2", Op.Status.OK, 5, 6),
                read(2, "k", "v1", Op.Status.INFO, 7, 8));
        assertEquals(Verdict.LINEARIZABLE, verdict("5a stale-GET-as-INFO", staleAsInfo));

        List<Op> staleAsLinRead = List.of(
                put(0, "k", "v1", Op.Status.INFO, 1),
                read(0, "k", "v1", Op.Status.OK, 2, 3),
                put(0, "k", "v2", Op.Status.INFO, 4),
                read(1, "k", "v2", Op.Status.OK, 5, 6),
                read(2, "k", "v1", Op.Status.OK, 7, 8));
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("5b stale-GET-as-linread", staleAsLinRead));
    }

    @Test
    void test6_uniqueTokenPrecondition() {
        HistoryRecorder rec = new HistoryRecorder();
        rec.recordPut(0, "k1", "dup", Op.Status.INFO, 1, 1);
        assertThrows(IllegalStateException.class,
                () -> rec.recordPut(1, "k2", "dup", Op.Status.INFO, 2, 2),
                "reusing a PUT token must fail the recorder precondition");
    }

    @Test
    void test7_committedOkWriteReadYourWrites() throws Exception {
        List<Op> ok = List.of(
                put(0, "k", "C", Op.Status.OK, 1),
                read(1, "k", "C", Op.Status.OK, 2, 3));
        assertEquals(Verdict.LINEARIZABLE, verdict("7a committed-OK-write-read-your-writes", ok));

        List<Op> stale = List.of(
                put(0, "k", "v1", Op.Status.OK, 1),
                read(0, "k", "v1", Op.Status.OK, 2, 3),
                put(0, "k", "v2", Op.Status.OK, 4),
                read(1, "k", "v2", Op.Status.OK, 5, 6),
                read(2, "k", "v1", Op.Status.OK, 7, 8));
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("7b committed-OK-write-vanished", stale));
    }

    @Test
    void test8_fiveXxOtherWriteInfoNeverFail() throws Exception {
        List<Op> asInfo = List.of(
                put(0, "k", "U", Op.Status.INFO, 1),
                read(1, "k", "U", Op.Status.OK, 2, 3));
        assertEquals(Verdict.LINEARIZABLE, verdict("8a 5xx-other-as-INFO", asInfo));

        List<Op> asFail = List.of(
                put(0, "k", "U", Op.Status.FAIL, 1),
                read(1, "k", "U", Op.Status.OK, 2, 3));
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("8b 5xx-other-flipped-to-FAIL", asFail));
    }
}
