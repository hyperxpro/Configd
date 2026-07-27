package io.configd.server.fanout;

import io.configd.common.ConfigScope;
import io.configd.distribution.fanout.WatchTarget;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.replication.StaticShardMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit proof for {@link ShardMapResolver}: watch coverage is exactly the write path's routing truth.
 * A KEY target resolves to the single shard {@link StaticShardMap#shardFor} routes its key to (so a
 * KEY watch covers precisely the shard that key is committed to); a PREFIX / FULL target scatters
 * across every shard. This is the real-hash half of the completeness proof (the coordinator's delivery
 * over a covered shard is proven in {@code MultiShardCoordinatorTest}); together they discharge "a
 * change on a non-zero shard is delivered".
 */
class ShardMapResolverTest {

    @Test
    void keyTargetCoversExactlyTheShardTheWritePathRoutesTheKeyTo() {
        StaticShardMap map = new StaticShardMap(4);
        ShardMapResolver resolver = new ShardMapResolver(map);
        for (String key : new String[]{"/a", "/app/db/host", "/x/y/z", "/s3/thing", "/_acl/roles/r"}) {
            int expected = map.shardFor(ConfigScope.GLOBAL, key);
            int[] covered = resolver.coveredGids(new WatchTarget(
                    ConfigScope.GLOBAL.ordinal(), EdgeFrame.WATCH_TARGET_KEY, key, false));
            assertArrayEquals(new int[]{expected}, covered,
                    "KEY " + key + " covers exactly shardFor(GLOBAL, key)=" + expected);
        }
    }

    @Test
    void prefixTargetScattersAcrossEveryShard() {
        StaticShardMap map = new StaticShardMap(4);
        ShardMapResolver resolver = new ShardMapResolver(map);
        int[] covered = resolver.coveredGids(new WatchTarget(
                ConfigScope.GLOBAL.ordinal(), EdgeFrame.WATCH_TARGET_PREFIX, "/app/", false));
        assertArrayEquals(new int[]{0, 1, 2, 3}, covered, "a prefix is not hash-contiguous - it scatters to all shards");
    }

    @Test
    void fullAndFullChainVerifyTargetsScatterAcrossEveryShard() {
        StaticShardMap map = new StaticShardMap(3);
        ShardMapResolver resolver = new ShardMapResolver(map);
        assertArrayEquals(new int[]{0, 1, 2}, resolver.coveredGids(
                new WatchTarget(ConfigScope.GLOBAL.ordinal(), EdgeFrame.WATCH_TARGET_FULL, "", false)));
        assertArrayEquals(new int[]{0, 1, 2}, resolver.coveredGids(
                new WatchTarget(ConfigScope.GLOBAL.ordinal(), EdgeFrame.WATCH_TARGET_FULL, "", true)));
    }

    @Test
    void keyTargetCarryingFullChainVerifyScattersAcrossEveryShardNotJustItsHashShard() {
        // full_chain_verify is a flag independent of the target kind: it matches every key and is
        // authorized at root, so a KEY+full_chain_verify target must cover ALL shards - never the
        // single shard the literal path hashes to (that would silently miss the other shards' state).
        StaticShardMap map = new StaticShardMap(4);
        ShardMapResolver resolver = new ShardMapResolver(map);
        String key = "/app/db/host";
        int hashShard = map.shardFor(ConfigScope.GLOBAL, key);
        int[] covered = resolver.coveredGids(new WatchTarget(
                ConfigScope.GLOBAL.ordinal(), EdgeFrame.WATCH_TARGET_KEY, key, true));
        assertArrayEquals(new int[]{0, 1, 2, 3}, covered,
                "KEY+full_chain_verify (match-all) covers every shard, not just shardFor=" + hashShard);
    }

    @Test
    void nEquals1EveryTargetResolvesToTheSingleShardZero() {
        StaticShardMap map = new StaticShardMap(1);
        ShardMapResolver resolver = new ShardMapResolver(map);
        assertArrayEquals(new int[]{0}, resolver.coveredGids(
                new WatchTarget(ConfigScope.GLOBAL.ordinal(), EdgeFrame.WATCH_TARGET_KEY, "/anything", false)));
        assertArrayEquals(new int[]{0}, resolver.coveredGids(
                new WatchTarget(ConfigScope.GLOBAL.ordinal(), EdgeFrame.WATCH_TARGET_FULL, "", false)));
    }

    @Test
    void coverageIsAscendingByGid() {
        StaticShardMap map = new StaticShardMap(8);
        int[] covered = new ShardMapResolver(map).coveredGids(new WatchTarget(
                ConfigScope.GLOBAL.ordinal(), EdgeFrame.WATCH_TARGET_PREFIX, "/p/", false));
        for (int i = 1; i < covered.length; i++) {
            assertEquals(covered[i - 1] + 1, covered[i], "covered gids ascend (WatchCursor strict-ascending invariant)");
        }
    }
}
