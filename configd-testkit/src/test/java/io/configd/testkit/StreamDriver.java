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
 * <p>Three implementations:
 * <ul>
 *   <li>{@link #NONE} - delivers nothing. The default, and what
 *       {@link EdgePropagationBacklogTest} runs against.</li>
 *   <li>{@link DirectInjectionDriver} - pushes handcrafted (including deliberately
 *       invalid) message sequences so the edge invariants and {@link EdgeActor} apply
 *       paths can be exercised directly (test-the-tester).</li>
 *   <li>{@code C1StreamDriver} - the faithful consumer loop described above.</li>
 * </ul>
 */
interface StreamDriver {

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
