package io.configd.testkit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * TEST-ONLY {@link StreamDriver} that pushes handcrafted {@link EdgeStream}
 * messages — including deliberately invalid sequences — at scripted sim ticks
 * (RR-012 test-the-tester). It does NOT read the {@link StreamDriver.Context}
 * sources; it is the manual counterpart to the real C1 drain, used by
 * {@link EdgeInvariantsTestTheTesterTest} to drive each edge invariant into a
 * firing state and to verify the {@link EdgeActor} apply paths.
 * <p>
 * Determinism: injections are emitted in FIFO order at or after their scheduled
 * tick, addressed to the first edge matching a predicate. No randomness.
 * <p>
 * This class is intentionally <b>not</b> a model of the production protocol — it
 * exists only to construct arbitrary (valid and invalid) inputs for the checker.
 */
final class DirectInjectionDriver implements StreamDriver {

    private record ScheduledInjection(long atTick, Predicate<EdgeActor> target, EdgeStream message) {}

    private final Deque<ScheduledInjection> pending = new ArrayDeque<>();
    private long tick;

    /** Schedules {@code message} for delivery to the first edge matching {@code target} at {@code atTick}. */
    DirectInjectionDriver inject(long atTick, Predicate<EdgeActor> target, EdgeStream message) {
        pending.addLast(new ScheduledInjection(atTick, target,
                Objects.requireNonNull(message, "message must not be null")));
        return this;
    }

    /** Schedules {@code message} for delivery to the edge with id {@code edgeId} at {@code atTick}. */
    DirectInjectionDriver injectTo(long atTick, int edgeId, EdgeStream message) {
        return inject(atTick, e -> e.edgeId() == edgeId, message);
    }

    @Override
    public void drive(Context ctx) {
        while (!pending.isEmpty() && pending.peekFirst().atTick() <= tick) {
            ScheduledInjection injection = pending.pollFirst();
            for (EdgeActor edge : ctx.edges()) {
                if (injection.target().test(edge)) {
                    ctx.send(edge, injection.message());
                    break;
                }
            }
        }
        tick++;
    }
}
