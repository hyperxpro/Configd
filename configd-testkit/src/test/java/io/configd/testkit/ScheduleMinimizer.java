package io.configd.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Delta-debugging (ddmin) schedule minimizer for a failing adversarial seed
 * (adversarial-sim-design §5). Because the seed fully determines the schedule,
 * "minimize the seed" is meaningless; instead we expand the seed once into its
 * concrete {@link AdversarialSchedule} and greedily reduce the fault-event and
 * client-op lists, keeping a removal iff the failure still reproduces. The result
 * is a minimal, standalone, replayable schedule that REDs without seed expansion —
 * stable even if {@code mixSeed} derivation later changes.
 * <p>
 * The reduction is the classic monotone greedy ddmin specialization (remove one
 * element at a time until a fixpoint), which is sufficient and predictable here;
 * the failure predicate is supplied by the caller (it re-runs a sim built from the
 * candidate schedule and reports whether the violation still fires).
 */
final class ScheduleMinimizer {

    private final Predicate<AdversarialSchedule> stillFails;

    ScheduleMinimizer(Predicate<AdversarialSchedule> stillFails) {
        this.stillFails = stillFails;
    }

    /**
     * Minimizes {@code failing} to a 1-minimal schedule that still satisfies
     * {@code stillFails}. Requires that {@code failing} already fails.
     */
    AdversarialSchedule minimize(AdversarialSchedule failing) {
        if (!stillFails.test(failing)) {
            throw new IllegalArgumentException("input schedule does not reproduce the failure");
        }
        AdversarialSchedule current = failing;

        boolean reduced = true;
        while (reduced) {
            reduced = false;

            // Try dropping each fault event.
            for (int i = 0; i < current.events().size(); i++) {
                AdversarialSchedule candidate = withoutEvent(current, i);
                if (stillFails.test(candidate)) {
                    current = candidate;
                    reduced = true;
                    break;
                }
            }
            if (reduced) {
                continue;
            }

            // Then try dropping each client op.
            for (int i = 0; i < current.ops().size(); i++) {
                AdversarialSchedule candidate = withoutOp(current, i);
                if (stillFails.test(candidate)) {
                    current = candidate;
                    reduced = true;
                    break;
                }
            }
        }
        return current;
    }

    private static AdversarialSchedule withoutEvent(AdversarialSchedule s, int idx) {
        List<AdversarialSchedule.Event> events = new ArrayList<>(s.events());
        events.remove(idx);
        return s.withEventsAndOps(events, s.ops(), s.totalTicks());
    }

    private static AdversarialSchedule withoutOp(AdversarialSchedule s, int idx) {
        List<AdversarialSchedule.Op> ops = new ArrayList<>(s.ops());
        ops.remove(idx);
        return s.withEventsAndOps(s.events(), ops, s.totalTicks());
    }

    /** Serializes a (minimized) schedule to a standalone, replayable JSON artifact. */
    static String toJson(AdversarialSchedule s) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"seed\":").append(s.seed())
          .append(",\"totalTicks\":").append(s.totalTicks())
          .append(",\"events\":[");
        for (int i = 0; i < s.events().size(); i++) {
            AdversarialSchedule.Event e = s.events().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"tick\":").append(e.tick())
              .append(",\"kind\":\"").append(e.kind()).append('"')
              .append(",\"a\":").append(e.a())
              .append(",\"b\":").append(e.b())
              .append(",\"param\":").append(e.param())
              .append(",\"intParam\":").append(e.intParam()).append('}');
        }
        sb.append("],\"ops\":[");
        for (int i = 0; i < s.ops().size(); i++) {
            AdversarialSchedule.Op o = s.ops().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"tick\":").append(o.tick())
              .append(",\"kind\":\"").append(o.kind()).append('"')
              .append(",\"clientId\":").append(o.clientId())
              .append(",\"opSeq\":").append(o.opSeq())
              .append(",\"key\":\"").append(o.key()).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
