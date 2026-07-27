package io.configd.testkit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Predicate;

final class DirectInjectionDriver implements StreamDriver {

    private record ScheduledInjection(long atTick, Predicate<EdgeActor> target, EdgeStream message) {}

    private final Deque<ScheduledInjection> pending = new ArrayDeque<>();
    private long tick;

    DirectInjectionDriver inject(long atTick, Predicate<EdgeActor> target, EdgeStream message) {
        pending.addLast(new ScheduledInjection(atTick, target,
                Objects.requireNonNull(message, "message must not be null")));
        return this;
    }

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
