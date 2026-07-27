package io.configd.distribution.fanout;

@FunctionalInterface
public interface ShardResolver {
    int[] coveredGids(WatchTarget target);
}
