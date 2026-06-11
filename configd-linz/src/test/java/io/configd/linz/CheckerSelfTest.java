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

    // 7. THE ADR-0033 one: a 200 is now a COMMITTED write recorded as OK (was INFO when
    //    200 was a leader-local-append lie). An OK PUT is a definite write — it must be
    //    treated exactly as strongly as the register model treats any kept write.
    //    7a: an OK PUT observed by a later read -> GREEN (committed value is what's read).
    //    7b: an OK PUT of v2 confirmed, then a later linearizable read returns the
    //        superseded v1 -> RED (a committed write cannot be un-observed by a later
    //        real-time read). This is the exact RR-004 read-your-writes guarantee.
    @Test
    void test7_committedOkWriteReadYourWrites() throws Exception {
        // 7a: OK PUT then OK read of the same token -> linearizable.
        List<Op> ok = List.of(
                put(0, "k", "C", Op.Status.OK, 1),         // 200 Committed
                read(1, "k", "C", Op.Status.OK, 2, 3));    // reads the committed value
        assertEquals(Verdict.LINEARIZABLE, verdict("7a committed-OK-write-read-your-writes", ok));

        // 7b: OK PUT v1, confirm v1; OK PUT v2, confirm v2; then a stale OK read of v1
        //     after v2 was committed -> non-linearizable (a committed write was lost).
        List<Op> stale = List.of(
                put(0, "k", "v1", Op.Status.OK, 1),
                read(0, "k", "v1", Op.Status.OK, 2, 3),
                put(0, "k", "v2", Op.Status.OK, 4),        // 200 Committed: v2
                read(1, "k", "v2", Op.Status.OK, 5, 6),    // confirm v2 committed
                read(2, "k", "v1", Op.Status.OK, 7, 8));   // stale: committed v2 vanished -> RED
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("7b committed-OK-write-vanished", stale));
    }

    // 8. 5xx-other on the WRITE path: an unknown server failure cannot guarantee the
    //    write did NOT commit, so the recorder must use INFO (indeterminate), never FAIL.
    //    FAIL is the UNSAFE direction — it drops the write, so a read that observed it
    //    would have no writer -> a FALSE RED that masks (or fabricates) an anomaly.
    //    8a: a 5xx-other write recorded INFO, later observed by an OK read -> GREEN
    //        (the write may have committed, and a read confirms it did).
    //    8b: the SAME write wrongly recorded FAIL (the bug) -> the observing read has no
    //        writer -> RED. The flip between 8a and 8b is the gate on the mapping fix.
    @Test
    void test8_fiveXxOtherWriteInfoNeverFail() throws Exception {
        // 8a: INFO write (5xx-other) observed by a read -> linearizable.
        List<Op> asInfo = List.of(
                put(0, "k", "U", Op.Status.INFO, 1),       // 5xx-other: may have committed
                read(1, "k", "U", Op.Status.OK, 2, 3));    // a read observes it -> it did commit
        assertEquals(Verdict.LINEARIZABLE, verdict("8a 5xx-other-as-INFO", asInfo));

        // 8b: the SAME write flipped to FAIL (the pre-fix bug) -> dropped -> read of U
        //     has no writer -> false RED.
        List<Op> asFail = List.of(
                put(0, "k", "U", Op.Status.FAIL, 1),
                read(1, "k", "U", Op.Status.OK, 2, 3));
        assertEquals(Verdict.NON_LINEARIZABLE, verdict("8b 5xx-other-flipped-to-FAIL", asFail));
    }
}
