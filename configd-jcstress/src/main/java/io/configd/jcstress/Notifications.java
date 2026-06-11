package io.configd.jcstress;

import io.configd.distribution.CommitNotification;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import java.util.List;

/**
 * Tiny factory helpers shared by the {@link io.configd.distribution.FanOutBuffer}
 * jcstress tests. Keeping them out of the @State classes avoids allocating in the
 * hot actor path and keeps the test bodies focused on the interleaving.
 */
final class Notifications {

    private Notifications() {
    }

    /** Single-byte value so equality/identity is cheap; the seq IS the identity we assert on. */
    static CommitNotification of(long seq) {
        // toVersion must be >= fromVersion and the seq carried explicitly. Use
        // (seq, seq+1) so each notification has a distinct, ascending delta window.
        ConfigDelta delta = new ConfigDelta(
                seq, seq + 1,
                List.of(new ConfigMutation.Put("k", new byte[]{(byte) seq})));
        return new CommitNotification(seq, 0L, delta);
    }
}
