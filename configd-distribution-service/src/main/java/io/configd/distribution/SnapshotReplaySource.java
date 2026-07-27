package io.configd.distribution;

import io.configd.store.ConfigSnapshot;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Minimal {@link ReplaySource} backed by the config store's current snapshot.
 *
 * <p>It delivers a <b>snapshot-equivalent state</b>: the cumulative committed
 * state at the store's current version. The supplier is typically
 * {@code versionedConfigStore::snapshot} - a single volatile read of the
 * immutable {@link ConfigSnapshot} pointer (no copy, no lock; the HAMT inside is
 * persistent and shareable). The snapshot's {@code version} is the
 * applied-mutation sequence S the consumer adopts as its post-replay cursor.
 *
 * <p>This is the path a consumer takes after a
 * {@link CommitNotificationSource#readSince(long)} GAP: apply the snapshot, set
 * the cursor to {@link ReplaySource.Replay#seq()}, resume tailing. It is NOT a
 * per-mutation historical replay (see {@link ReplaySource} for why that is out of
 * scope for the eventually-consistent edge data plane).
 */
public final class SnapshotReplaySource implements ReplaySource {

    private final Supplier<ConfigSnapshot> snapshotSupplier;

    public SnapshotReplaySource(Supplier<ConfigSnapshot> snapshotSupplier) {
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier must not be null");
    }

    @Override
    public Replay replayFromSnapshot() {
        ConfigSnapshot snap = snapshotSupplier.get();
        Objects.requireNonNull(snap, "snapshotSupplier returned null");
        return new Replay(snap, snap.version());
    }
}
