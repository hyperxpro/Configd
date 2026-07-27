package io.configd.testkit;

import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;

import java.util.List;

/**
 * The server-side, per-edge streaming seam - the simulator's stand-in
 * for the fan-out service that pushes committed mutations to subscribed edges.
 * <p>
 * <b>The stream driver implements this; the contract is the commit-notification handoff
 * spec's consumer loop.</b> A faithful implementation, per subscribed edge, holds a
 * cursor and each driver step:
 * <ol>
 *   <li>calls {@link CommitNotificationSource#readSince(long) source.readSince(cursor)}
 *       on the edge's subscribed CP node;</li>
 *   <li>on {@code Ok(notifications)} pushes one {@link EdgeStream.Notify} per
 *       notification over the edge network in seq order and advances the cursor to
 *       the last seq;</li>
 *   <li>on {@code Gap(oldestRetainedSeq)} calls
 *       {@link ReplaySource#replayFromSnapshot()}, pushes an
 *       {@link EdgeStream.Snapshot}, sets the cursor to the replay seq, and resumes
 *       tailing - exactly-once over effect, no hole, no duplicate.</li>
 * </ol>
 * That real implementation is the <b>stream driver</b> and is intentionally <b>NOT</b>
 * written here: this is verification machinery built before the data-plane
 * component exists, so the simulator must NOT smuggle the production drain in.
 *
 * <p>Exactly two implementations:
 * <ul>
 *   <li>{@link #NONE} - the honest current state of the system: nothing is ever
 *       delivered. This is the default, and it is why {@link EdgePropagationBacklogTest}
 *       fails (the executable backlog).</li>
 *   <li>{@link DirectInjectionDriver} - a TEST-ONLY driver that pushes handcrafted
 *       (including deliberately invalid) message sequences so the edge invariants
 *       and {@link EdgeActor} apply paths can be exercised directly
 *       (test-the-tester).</li>
 * </ul>
 */
interface StreamDriver {

    /**
     * The per-tick context handed to the driver: the per-CP-node commit-notification
     * sources and replay sources (the commit-notification handoff seams), the edge network it pushes
     * over, the edge roster, and the current sim time. A real stream driver consumes
     * {@code source(cpNode)} / {@code replaySource(cpNode)}; {@link #NONE} ignores
     * everything.
     */
    interface Context {

        /** Live edges (deterministic order). The driver streams to each. */
        List<EdgeActor> edges();

        CommitNotificationSource source(int cpNode);

        ReplaySource replaySource(int cpNode);

        /** Pushes a message to {@code edge} over the edge network (latency applies). */
        void send(EdgeActor edge, EdgeStream message);

        long nowMs();
    }

    void drive(Context ctx);

    StreamDriver NONE = ctx -> { /* deliberately empty - see EdgePropagationBacklogTest */ };
}
