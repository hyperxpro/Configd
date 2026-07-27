package io.configd.distribution.fanout;

import io.configd.common.Clock;
import io.configd.common.ConfigScope;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.replication.StaticShardMap;
import io.configd.server.fanout.ShardMapResolver;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declared in {@code io.configd.distribution.fanout} (not the server test package) so it can call the
 * coordinator's package-private {@code sweep}/{@code drainInboundCommands} directly.
 */
class RealHashCompletenessTest {

    private static final int N = 4;
    private final TestClock clock = new TestClock();
    private final CapturingSink out = new CapturingSink();

    private FanOutBuffer[] buffers;
    private FanOutConnectionDriver driver;
    private StaticShardMap shardMap;

    private void setup() {
        this.shardMap = new StaticShardMap(N);
        this.buffers = new FanOutBuffer[N];
        Map<Integer, CommitNotificationSource> sources = new LinkedHashMap<>();
        Map<Integer, ReplaySource> replays = new LinkedHashMap<>();
        int[] gids = new int[N];
        for (int g = 0; g < N; g++) {
            buffers[g] = new FanOutBuffer(64);
            sources.put(g, buffers[g]);
            ConfigSnapshot empty = new ConfigSnapshot(HamtMap.<String, VersionedValue>empty(), 0L, 0L);
            replays.put(g, new SnapshotReplaySource(() -> empty));
            gids[g] = g;
        }
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        this.driver = new FanOutConnectionDriver(sources, replays, gids, new ShardMapResolver(shardMap),
                shardMap.epoch(), out, FanOutConfig.defaults(), FanOutSessionMetrics.NOOP, clock, gov,
                "edge-1", (c, m) -> { }, (p, r, t) -> true);
    }

    @Test
    void fullWatchDeliversEachShardsCommitTaggedWithItsRealHashRoutedGid() {
        setup();
        driver.onInboundFrame(fullCreate(1, WatchCursor.fromNow()));
        driver.drainInboundCommands();

        Map<Integer, String> keyByShard = new LinkedHashMap<>();
        for (int g = 0; g < N; g++) {
            String key = keyHashingTo(g);
            keyByShard.put(g, key);
            buffers[g].publish(put(1, key, "v"));
        }
        driver.sweep(clock.currentTimeMillis());

        for (int g = 0; g < N; g++) {
            int shard = g;
            String key = keyByShard.get(g);
            List<EdgeFrame.WatchEvent> hits = out.events().stream()
                    .filter(e -> e.gid() == shard && e.changes().get(0).key().equals(key)).toList();
            assertEquals(1, hits.size(), "shard " + shard + " change for key " + key + " delivered with gid=" + shard);
        }
        assertTrue(out.events().stream().anyMatch(e -> e.gid() != 0),
                "a change hash-routed to a non-zero shard was delivered tagged with that gid");
    }

    @Test
    void keyWatchOnANonZeroShardCoversOnlyThatShardAndDeliversWithThatGid() {
        setup();
        int target = -1;
        String key = null;
        for (int g = 1; g < N; g++) {
            key = keyHashingTo(g);
            target = g;
            break;
        }
        assertFalse(target == 0, "found a key on a non-zero shard");

        driver.onInboundFrame(keyCreate(1, key, WatchCursor.fromNow()));
        driver.drainInboundCommands();

        EdgeFrame.WatchCreated created = out.frames().stream()
                .filter(f -> f instanceof EdgeFrame.WatchCreated).map(f -> (EdgeFrame.WatchCreated) f)
                .findFirst().orElseThrow();
        assertArrayEquals(new int[]{target},
                created.shards().stream().mapToInt(EdgeFrame.ShardMode::gid).toArray(),
                "a KEY watch covers ONLY the shard its key hash-routes to");

        buffers[target].publish(put(1, key, "v"));
        driver.sweep(clock.currentTimeMillis());
        List<EdgeFrame.WatchEvent> events = out.events();
        assertEquals(1, events.size());
        assertEquals(target, events.get(0).gid(), "delivered tagged with the real hash-routed gid");
        assertEquals(key, events.get(0).changes().get(0).key());
    }

    @Test
    void keyWatchCarryingFullChainVerifyCoversEveryShardOverTheRealResolver() {
        setup();
        // A KEY target with the full_chain_verify flag matches every key and is root-authorized, so
        // the real ShardMapResolver must scatter it to ALL shards - never the single shard its path
        // hashes to (which would silently miss the other shards' state).
        driver.onInboundFrame(new EdgeFrame.WatchCreate(1, 0, EdgeFrame.WATCH_TARGET_KEY,
                "/app/db/host".getBytes(StandardCharsets.UTF_8), WatchCursor.fromNow(),
                EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY));
        driver.drainInboundCommands();

        EdgeFrame.WatchCreated created = out.frames().stream()
                .filter(f -> f instanceof EdgeFrame.WatchCreated).map(f -> (EdgeFrame.WatchCreated) f)
                .findFirst().orElseThrow();
        assertArrayEquals(new int[]{0, 1, 2, 3},
                created.shards().stream().mapToInt(EdgeFrame.ShardMode::gid).toArray(),
                "KEY+full_chain_verify covers every shard over the real ShardMapResolver");

        for (int g = 0; g < N; g++) {
            buffers[g].publish(put(1, keyHashingTo(g), "v"));
        }
        driver.sweep(clock.currentTimeMillis());
        for (int g = 0; g < N; g++) {
            int shard = g;
            assertTrue(out.events().stream().anyMatch(e -> e.gid() == shard),
                    "shard " + shard + " change delivered under KEY+full_chain_verify (match-all)");
        }
    }

    private String keyHashingTo(int g) {
        for (int i = 0; i < 100_000; i++) {
            String key = "/k/" + i;
            if (shardMap.shardFor(ConfigScope.GLOBAL, key) == g) {
                return key;
            }
        }
        throw new IllegalStateException("no key found hashing to shard " + g + " within the search budget");
    }

    private static EdgeFrame.WatchCreate fullCreate(long id, WatchCursor cursor) {
        return new EdgeFrame.WatchCreate(id, 0, EdgeFrame.WATCH_TARGET_FULL, new byte[0], cursor, 0);
    }

    private static EdgeFrame.WatchCreate keyCreate(long id, String path, WatchCursor cursor) {
        return new EdgeFrame.WatchCreate(id, 0, EdgeFrame.WATCH_TARGET_KEY,
                path.getBytes(StandardCharsets.UTF_8), cursor, 0);
    }

    private static CommitNotification put(long seq, String key, String val) {
        return new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }

    private static final class CapturingSink implements TransportSink {
        private final List<EdgeFrame> sent = new ArrayList<>();

        @Override
        public boolean offer(EdgeFrame frame) {
            sent.add(frame);
            return true;
        }

        @Override
        public void close(ErrorCode code, String message) {
        }

        List<EdgeFrame> frames() {
            return sent;
        }

        List<EdgeFrame.WatchEvent> events() {
            return sent.stream().filter(f -> f instanceof EdgeFrame.WatchEvent)
                    .map(f -> (EdgeFrame.WatchEvent) f).toList();
        }
    }

    private static final class TestClock implements Clock {
        private long now = 1_000L;

        @Override
        public long currentTimeMillis() {
            return now;
        }

        @Override
        public long nanoTime() {
            return now * 1_000_000L;
        }
    }
}
