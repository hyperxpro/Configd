package io.configd.testkit;

/**
 * Per-seed activity accumulator — the RR-012 vacuity defence.
 * <p>
 * RR-012: the old {@code SeedSweepTest} contained three bare {@code return}
 * statements (no-leader, no-commit, no-failover) that passed green having
 * asserted nothing. A "20,000 green tests" count therefore proved nothing about
 * how often the safety property was actually exercised.
 * <p>
 * The fix distinguishes two outcome kinds per seed:
 * <ul>
 *   <li><b>Safety</b> — invariants that must ALWAYS hold. A breach FAILS the seed
 *       (handled by {@link SimInvariants}).</li>
 *   <li><b>Liveness</b> — goals a given seed may legitimately not reach within its
 *       tick budget (a leader is elected, a value commits, a failover completes).
 *       A miss is a <em>recorded liveness stall</em>, NOT a pass and NOT a failure
 *       (charter: liveness findings are registered, not hidden).</li>
 * </ul>
 * A seed counts as a <em>real assertion</em> of the property only when it reached
 * the assertion (its activity predicate held). This object records which liveness
 * goals were reached so the sweep can (a) skip-but-record seeds that stalled and
 * (b) report the honest "how many seeds actually checked the property" number that
 * replaces the inflated execution count.
 * <p>
 * Not thread-safe; one instance per seed, mutated on the single sim thread.
 */
final class Activity {

    private boolean leaderElected;
    private int distinctTermsWithLeader;
    private long highestTermWithLeader = Long.MIN_VALUE;
    private boolean valueCommitted;
    private boolean failoverCompleted;
    private int faultsFired;
    private int crashesExecuted;
    private boolean linearizableReadOk;
    private boolean committedEntryExistedPreCrash;

    void recordLeaderAtTerm(long term) {
        leaderElected = true;
        if (term > highestTermWithLeader) {
            highestTermWithLeader = term;
            distinctTermsWithLeader++;
        }
    }

    void recordCommit() {
        valueCommitted = true;
    }

    void recordFailover() {
        failoverCompleted = true;
    }

    void recordFault() {
        faultsFired++;
    }

    void recordCrash() {
        crashesExecuted++;
    }

    void recordLinearizableReadOk() {
        linearizableReadOk = true;
    }

    void recordCommittedEntryPreCrash() {
        committedEntryExistedPreCrash = true;
    }

    boolean leaderElected() {
        return leaderElected;
    }

    int distinctTermsWithLeader() {
        return distinctTermsWithLeader;
    }

    boolean valueCommitted() {
        return valueCommitted;
    }

    boolean failoverCompleted() {
        return failoverCompleted;
    }

    int faultsFired() {
        return faultsFired;
    }

    int crashesExecuted() {
        return crashesExecuted;
    }

    boolean linearizableReadOk() {
        return linearizableReadOk;
    }

    boolean committedEntryExistedPreCrash() {
        return committedEntryExistedPreCrash;
    }

    @Override
    public String toString() {
        return "Activity[leader=" + leaderElected
                + ", terms=" + distinctTermsWithLeader
                + ", commit=" + valueCommitted
                + ", failover=" + failoverCompleted
                + ", faults=" + faultsFired
                + ", crashes=" + crashesExecuted
                + ", readOk=" + linearizableReadOk + "]";
    }
}
