package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource.Result;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.observability.MetricsRegistry;
import io.configd.store.CommandCodec;
import io.configd.store.Compactor;
import io.configd.store.ConfigStateMachine;
import io.configd.store.VersionedConfigStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * The N-way fan-out merge proof. Drives the REAL production helper
 * {@link ConfigdServer#registerShardedFanOut} - one {@link FanOutBuffer} + {@link Compactor} per shard,
 * each fed by ITS group's commit listener - and discriminates the four G1 obligations:
 *
 * <ul>
 *   <li><b>N=1 byte-identity foundation</b> - a single shard builds exactly one buffer + compactor for
 *       the primary group, and the listener publishes the same per-commit notification as the prior
 *       single-buffer wiring.</li>
 *   <li><b>Per-shard isolation (S2/S4)</b> - a committed write to shard k lands in shard k's buffer
 *       ONLY; a sibling shard's buffer never sees it.</li>
 *   <li><b>Per-shard monotonicity through the merge</b> - each shard's buffer yields a strictly
 *       ascending, contiguous, per-shard seq run (no lost/dup/reordered-within-shard), and the seqs are
 *       INDEPENDENT per shard (no fabricated cross-shard global order - ADR-D-A/D-C).</li>
 *   <li><b>Thread-safety</b> - N threads (one per group, the owner-thread model) publishing concurrently
 *       to their own buffers cause no corruption (single-writer per buffer holds under real
 *       concurrency).</li>
 * </ul>
 */
class ShardedFanOutTest {

    /** Matches the production {@code ConfigdServer.FANOUT_BUFFER_CAPACITY}. */
    private static final int CAP = 10_000;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Builds N minimal runtimes (groupId + state machine + store; the helper touches only those). */
    private static List<ConfigdServer.RaftGroupRuntime> runtimes(int n) {
        List<ConfigdServer.RaftGroupRuntime> rts = new ArrayList<>(n);
        for (int gid = 0; gid < n; gid++) {
            VersionedConfigStore store = new VersionedConfigStore();
            ConfigStateMachine sm = new ConfigStateMachine(store);
            rts.add(new ConfigdServer.RaftGroupRuntime(gid, null, null, store, sm, null, null, null));
        }
        return rts;
    }

    private static MetricsRegistry.Counter droppedCounter() {
        return new MetricsRegistry().counter("fanout.buffer.dropped");
    }

    /** Reads every retained notification (cursor 0 => everything) as an OK run. */
    private static List<CommitNotification> drain(FanOutBuffer buffer) {
        Result r = buffer.readSince(0);
        assertFalse(r.isGap(), "buffer should not GAP within capacity");
        return ((Result.Ok) r).notifications();
    }

    // ---- N=1 byte-identity foundation ------------------------------------------------------

    @Test
    void n1BuildsExactlyOnePrimaryBufferAndCompactorAndPublishesEachCommit() {
        List<ConfigdServer.RaftGroupRuntime> rts = runtimes(1);
        ConfigdServer.ShardedFanOut fan =
                ConfigdServer.registerShardedFanOut(rts, Clock.system(), droppedCounter(), CAP);

        assertEquals(1, fan.buffers().size(), "N=1 builds exactly one buffer");
        assertEquals(1, fan.compactors().size(), "N=1 builds exactly one compactor");
        assertTrue(fan.buffers().containsKey(0), "the one buffer is keyed by the primary gid 0");

        ConfigStateMachine sm = rts.get(0).stateMachine();
        sm.apply(1, 1, CommandCodec.encodePut("alpha", bytes("v0")));
        sm.apply(2, 1, CommandCodec.encodePut("beta", bytes("v1")));

        List<CommitNotification> out = drain(fan.buffers().get(0));
        assertEquals(2, out.size(), "both commits published to the primary buffer");
        assertEquals(1, out.get(0).seq());
        assertEquals(2, out.get(1).seq());
        // Compactor retained both snapshots (versions 1 and 2).
        assertEquals(2, fan.compactors().get(0).snapshotCount());
    }

    // ---- Per-shard isolation (S2/S4) -------------------------------------------------------

    @Test
    void perShardCommitsLandInTheirOwnBufferOnly() {
        int n = 4;
        List<ConfigdServer.RaftGroupRuntime> rts = runtimes(n);
        ConfigdServer.ShardedFanOut fan =
                ConfigdServer.registerShardedFanOut(rts, Clock.system(), droppedCounter(), CAP);
        assertEquals(n, fan.buffers().size());

        // Each shard commits a key unique to that shard.
        for (int gid = 0; gid < n; gid++) {
            rts.get(gid).stateMachine().apply(1, 1, CommandCodec.encodePut("k" + gid, bytes("v" + gid)));
        }

        for (int gid = 0; gid < n; gid++) {
            List<CommitNotification> out = drain(fan.buffers().get(gid));
            assertEquals(1, out.size(), "shard " + gid + " buffer holds exactly its own commit");
            String key = onlyKey(out.get(0));
            assertEquals("k" + gid, key, "shard " + gid + " buffer must hold ONLY its own key (no leak)");
        }
    }

    // ---- Per-shard monotonicity + no fabricated global order --------------------------------

    @Test
    void perShardSequenceIsMonotonicAndIndependentAcrossShards() {
        int n = 3;
        int writesPerShard = 50;
        List<ConfigdServer.RaftGroupRuntime> rts = runtimes(n);
        ConfigdServer.ShardedFanOut fan =
                ConfigdServer.registerShardedFanOut(rts, Clock.system(), droppedCounter(), CAP);

        for (int gid = 0; gid < n; gid++) {
            ConfigStateMachine sm = rts.get(gid).stateMachine();
            for (int i = 1; i <= writesPerShard; i++) {
                sm.apply(i, 1, CommandCodec.encodePut("g" + gid + ".k" + i, bytes("v" + i)));
            }
        }

        for (int gid = 0; gid < n; gid++) {
            List<CommitNotification> out = drain(fan.buffers().get(gid));
            assertEquals(writesPerShard, out.size(), "shard " + gid + " retained every commit");
            // Strictly ascending, contiguous, starting at 1 - per-shard monotonicity, no dup/gap/reorder.
            for (int i = 0; i < out.size(); i++) {
                assertEquals(i + 1L, out.get(i).seq(),
                        "shard " + gid + " seq must be the contiguous per-shard sequence");
            }
        }

        // No fabricated cross-shard global order: every shard's sequence INDEPENDENTLY starts at 1 and
        // runs 1..M - i.e. seq=1 exists in all N buffers simultaneously. A global merge sequence would
        // have made these disjoint. This pins the ADR-D-A/D-C "no cross-shard total order" decision.
        for (int gid = 0; gid < n; gid++) {
            assertEquals(1L, drain(fan.buffers().get(gid)).get(0).seq(),
                    "shard " + gid + " sequence is independent (per-shard), not a global counter");
        }
    }

    // ---- Per-shard replay floor is per-shard ----------------------------------------------

    @Test
    void perShardReplaySourceFloorIsThatShardsVersion() {
        int n = 3;
        List<ConfigdServer.RaftGroupRuntime> rts = runtimes(n);
        ConfigdServer.registerShardedFanOut(rts, Clock.system(), droppedCounter(), CAP);

        // Different number of commits per shard -> distinct per-shard replay floors.
        for (int gid = 0; gid < n; gid++) {
            ConfigStateMachine sm = rts.get(gid).stateMachine();
            for (int i = 1; i <= gid + 1; i++) {
                sm.apply(i, 1, CommandCodec.encodePut("g" + gid + ".k" + i, bytes("v")));
            }
        }
        for (int gid = 0; gid < n; gid++) {
            ReplaySource replay = new SnapshotReplaySource(rts.get(gid).configStore()::snapshot);
            assertEquals(gid + 1L, replay.replayFromSnapshot().seq(),
                    "shard " + gid + " replay floor is its OWN per-shard version (a cursor vector, "
                            + "not a global snapshot)");
        }
    }

    // ---- Thread-safety: concurrent per-shard publish (the owner-thread model) ---------------

    @Test
    void concurrentPerShardPublishHasNoCorruption() throws Exception {
        int n = 8;
        int writesPerShard = 2_000; // well within CAP so nothing is evicted - every commit must survive
        assertTrue(writesPerShard <= CAP, "test invariant: no eviction expected");
        List<ConfigdServer.RaftGroupRuntime> rts = runtimes(n);
        ConfigdServer.ShardedFanOut fan =
                ConfigdServer.registerShardedFanOut(rts, Clock.system(), droppedCounter(), CAP);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>(n);
        for (int gid = 0; gid < n; gid++) {
            final int g = gid;
            // ONE thread per group = the owner-thread model: each state machine (and thus its buffer) is
            // driven by exactly one thread, so the property under test is the genuine single-writer-PER-
            // BUFFER design under real cross-buffer concurrency. (The assertOwnerThread tripwire itself is
            // inert here - these SMs use the NOOP invariant checker - so this proves no-corruption, not
            // the tripwire; the tripwire's non-vacuity is proven at N>1 in the G2/G3 live sims.)
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    ConfigStateMachine sm = rts.get(g).stateMachine();
                    for (int i = 1; i <= writesPerShard; i++) {
                        sm.apply(i, 1, CommandCodec.encodePut("g" + g + ".k" + i, bytes("v" + i)));
                    }
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                } finally {
                    done.countDown();
                }
            }, "owner-" + gid);
            threads.add(t);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "all owner threads finished");
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertEquals(null, failure.get(), "no owner thread threw");

        // Each buffer has exactly its shard's commits, contiguous 1..M - no loss/dup/corruption under
        // concurrent writes to the N distinct buffers.
        for (int gid = 0; gid < n; gid++) {
            List<CommitNotification> out = drain(fan.buffers().get(gid));
            assertEquals(writesPerShard, out.size(),
                    "shard " + gid + " buffer lost/duplicated no commit under concurrency");
            for (int i = 0; i < out.size(); i++) {
                assertEquals(i + 1L, out.get(i).seq(), "shard " + gid + " seq " + (i + 1) + " intact");
            }
        }
    }

    // ---- Shared dropped counter aggregates across shards (drop-amplification observability) --

    @Test
    void sharedDroppedCounterAggregatesAcrossShards() {
        int n = 3;
        int smallCap = 4;
        int writesPerShard = 10; // 10 - 4 = 6 evictions per shard
        List<ConfigdServer.RaftGroupRuntime> rts = runtimes(n);
        MetricsRegistry.Counter dropped = droppedCounter();
        ConfigdServer.ShardedFanOut fan =
                ConfigdServer.registerShardedFanOut(rts, Clock.system(), dropped, smallCap);

        for (int gid = 0; gid < n; gid++) {
            ConfigStateMachine sm = rts.get(gid).stateMachine();
            for (int i = 1; i <= writesPerShard; i++) {
                sm.apply(i, 1, CommandCodec.encodePut("g" + gid + ".k" + i, bytes("v")));
            }
        }

        long expectedPerShard = writesPerShard - smallCap;
        for (int gid = 0; gid < n; gid++) {
            assertEquals(expectedPerShard, fan.buffers().get(gid).droppedTotal(),
                    "shard " + gid + " evicted independently (its own drop-amplification)");
        }
        assertEquals(expectedPerShard * n, dropped.get(),
                "the shared fanout.buffer.dropped counter is the AGGREGATE across shards");
    }

    private static String onlyKey(CommitNotification n) {
        assertEquals(1, n.delta().mutations().size(), "one mutation per single-key commit");
        return switch (n.delta().mutations().get(0)) {
            case io.configd.store.ConfigMutation.Put p -> p.key();
            case io.configd.store.ConfigMutation.Delete d -> d.key();
        };
    }
}
