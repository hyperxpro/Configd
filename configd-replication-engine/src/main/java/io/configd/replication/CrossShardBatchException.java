package io.configd.replication;

import io.configd.common.ConfigScope;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Thrown when a multi-key write (a {@code BATCH}) spans more than one shard. Cross-shard atomicity
 * is disclaimed: the message names the offending key-to-shard mapping so the operator can co-locate
 * the keys (route them to one shard via a shared scope/prefix) and use the single-shard atomic BATCH
 * instead. A clear rejection, never a silent partial write.
 *
 * @see CrossShardWriteGuard
 */
public final class CrossShardBatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, Integer> keyToShard;

    /**
     * @param scope      the write's scope
     * @param keyToShard each key of the batch mapped to the shard it resolves to (ordered)
     */
    public CrossShardBatchException(ConfigScope scope, Map<String, Integer> keyToShard) {
        super(buildMessage(scope, keyToShard));
        this.keyToShard = keyToShard;
    }

    /** The offending key-to-shard mapping (the keys did not all resolve to one shard). */
    public Map<String, Integer> keyToShard() {
        return keyToShard;
    }

    private static String buildMessage(ConfigScope scope, Map<String, Integer> keyToShard) {
        Set<Integer> shards = new TreeSet<>(keyToShard.values());
        return "cross-shard multi-key write rejected (DISCLAIM): scope=" + scope + " keys span shards "
                + shards + " — " + keyToShard + ". Co-locate these keys (route them to one shard via a "
                + "shared scope/prefix) to use the single-shard atomic BATCH; Configd does not offer "
                + "cross-shard atomicity.";
    }
}
