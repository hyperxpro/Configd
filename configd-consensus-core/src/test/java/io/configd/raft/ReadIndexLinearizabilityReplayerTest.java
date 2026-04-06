package io.configd.raft;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the safety invariants of {@code spec/ReadIndexSpec.tla} against
 * the live {@link ReadIndexState} implementation.
 *
 * <p>Closes the spec→code link for SPEC-GAP-4 / R-14b / PA-5023. The TLA+
 * model proves the protocol-level invariants over an abstract state
 * machine; this replayer drives the concrete Java implementation through
 * a randomised sequence of model-equivalent actions and asserts the same
 * invariants hold step-by-step.
 *
 * <p>Invariants checked (mirroring the TLA spec):
 * <ul>
 *   <li><b>ReadIndexBoundedByMaxIndex</b> — every recorded readIndex is
 *       &le; the maxCommitIndex visible at the time the read was started.</li>
 *   <li><b>ReadFreshness</b> — a read can only become {@code isReady}
 *       once {@code lastApplied >= readIndex} AND leadership has been
 *       confirmed.</li>
 *   <li><b>NoStaleLeaderServe</b> — after a step-down ({@code clear()}),
 *       no previously-pending read can become ready.</li>
 * </ul>
 */
class ReadIndexLinearizabilityReplayerTest {

    /** Abstract action drawn from the spec's Next predicate. */
    sealed interface Action {
        record Commit(int delta) implements Action {}
        record StartRead() implements Action {}
        record HeartbeatRound(int ackCount, int quorumSize) implements Action {}
        record Apply(int delta) implements Action {}
        record StepDown() implements Action {}
    }

    @Property(tries = 200)
    void replaySatisfiesAllSpecInvariants(@ForAll("actionSequences") List<Action> actions) {
        ReadIndexState state = new ReadIndexState();
        long commitIndex = 0L;
        long lastApplied = 0L;
        int quorumSize = 2;  // 3-node cluster

        // Shadow ledger: id -> (readIndex, leadershipConfirmed) — what the
        // spec invariant should observe.
        Map<Long, ShadowEntry> shadow = new LinkedHashMap<>();
        // Track every ID ever issued so we can audit step-down behaviour.
        Map<Long, Long> historicalReadIndex = new HashMap<>();

        for (Action action : actions) {
            switch (action) {
                case Action.Commit c -> commitIndex += Math.max(1, c.delta());
                case Action.Apply a -> {
                    long bump = Math.max(1, a.delta());
                    lastApplied = Math.min(commitIndex, lastApplied + bump);
                }
                case Action.StartRead s -> {
                    long id = state.startRead(commitIndex);
                    shadow.put(id, new ShadowEntry(commitIndex, false));
                    historicalReadIndex.put(id, commitIndex);

                    // ReadIndexBoundedByMaxIndex
                    assertTrue(state.readIndex(id) <= commitIndex,
                            "spec: ReadIndexBoundedByMaxIndex violated at startRead");
                    assertTrue(state.readIndex(id) >= 0,
                            "readIndex must be non-negative");
                }
                case Action.HeartbeatRound h -> {
                    int ack = h.ackCount();
                    int q = h.quorumSize() > 0 ? h.quorumSize() : quorumSize;
                    state.confirmAll(ack, q);
                    if (ack >= q) {
                        shadow.replaceAll((id, e) -> e.confirmed());
                    }
                }
                case Action.StepDown sd -> {
                    state.clear();
                    // NoStaleLeaderServe: every pending read is dropped.
                    for (Map.Entry<Long, ShadowEntry> e : shadow.entrySet()) {
                        long id = e.getKey();
                        assertFalse(state.isReady(id, lastApplied),
                                "spec: NoStaleLeaderServe violated — id " + id
                                        + " is ready after step-down");
                        assertFalse(state.readIndex(id) >= 0,
                                "deposed leader still exposes readIndex for id " + id);
                    }
                    shadow.clear();
                }
            }

            // Step-by-step ReadFreshness check.
            for (Map.Entry<Long, ShadowEntry> e : shadow.entrySet()) {
                long id = e.getKey();
                ShadowEntry sh = e.getValue();
                boolean ready = state.isReady(id, lastApplied);
                if (ready) {
                    assertTrue(sh.leadershipConfirmed(),
                            "spec: ReadFreshness — ready before leadership confirmed");
                    assertTrue(lastApplied >= sh.readIndex(),
                            "spec: ReadFreshness — ready before lastApplied caught up");
                }
                // ReadIndexBoundedByMaxIndex: shadow's recorded readIndex
                // must equal the live one until completion.
                assertTrue(state.readIndex(id) == sh.readIndex(),
                        "spec: readIndex drifted from value at startRead time");
            }
        }
    }

    /**
     * NoStaleLeaderServe variant: after step-down, even a fresh apply that
     * pushes lastApplied past the old readIndex must not make any old read
     * ready (because they were dropped).
     */
    @Property(tries = 100)
    void stepDownDropsAllPendingReads(
            @ForAll @IntRange(min = 1, max = 20) int numPending,
            @ForAll @LongRange(min = 1, max = 1000) long applyAfterStepDown) {

        ReadIndexState state = new ReadIndexState();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < numPending; i++) {
            ids.add(state.startRead(i));
        }
        state.confirmAllLeadership();

        state.clear();

        for (Long id : ids) {
            assertFalse(state.isReady(id, applyAfterStepDown),
                    "deposed leader served read id " + id);
        }
        assertTrue(state.pendingCount() == 0,
                "step-down did not clear pending reads");
    }

    /**
     * ReadFreshness — a read can never be served before its readIndex is
     * caught up by lastApplied, regardless of how many heartbeat rounds
     * have confirmed leadership.
     */
    @Property(tries = 200)
    void readNeverReadyBeforeApplyCatchesUp(
            @ForAll @LongRange(min = 1, max = 10_000) long startCommitIndex,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE / 2) long lastAppliedBelow) {

        ReadIndexState state = new ReadIndexState();
        long readId = state.startRead(startCommitIndex);
        state.confirmAllLeadership();

        long lastApplied = Math.min(lastAppliedBelow, startCommitIndex - 1);
        if (lastApplied < 0) lastApplied = 0;

        assertFalse(state.isReady(readId, lastApplied),
                "ReadFreshness: read served while lastApplied < readIndex");
    }

    /**
     * ReadIndexBoundedByMaxIndex — the recorded readIndex is exactly
     * the commit index passed in, and never higher than the cluster's
     * current commit at the time of issue.
     */
    @Property(tries = 200)
    void readIndexEqualsCommitIndexAtStart(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE / 2) long commitIndex) {

        ReadIndexState state = new ReadIndexState();
        long id = state.startRead(commitIndex);
        assertTrue(state.readIndex(id) == commitIndex,
                "readIndex deviated from commitIndex at startRead");
    }

    @Provide
    Arbitrary<List<Action>> actionSequences() {
        Arbitrary<Action> commit = Arbitraries.integers().between(1, 5)
                .map(d -> (Action) new Action.Commit(d));
        Arbitrary<Action> apply = Arbitraries.integers().between(1, 5)
                .map(d -> (Action) new Action.Apply(d));
        Arbitrary<Action> startRead = Arbitraries.just((Action) new Action.StartRead());
        Arbitrary<Action> heartbeat = Arbitraries.integers().between(1, 3)
                .map(ack -> (Action) new Action.HeartbeatRound(ack, 2));
        Arbitrary<Action> stepDown = Arbitraries.just((Action) new Action.StepDown());

        return Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(4, commit),
                        net.jqwik.api.Tuple.of(4, apply),
                        net.jqwik.api.Tuple.of(3, startRead),
                        net.jqwik.api.Tuple.of(3, heartbeat),
                        net.jqwik.api.Tuple.of(1, stepDown))
                .list().ofMinSize(5).ofMaxSize(60);
    }

    private record ShadowEntry(long readIndex, boolean leadershipConfirmed) {
        ShadowEntry confirmed() {
            return new ShadowEntry(readIndex, true);
        }
    }
}
