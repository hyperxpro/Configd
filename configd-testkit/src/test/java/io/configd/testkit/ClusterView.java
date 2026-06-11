package io.configd.testkit;

import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.store.VersionedConfigStore;

/**
 * Minimal read-only view of a simulated cluster, sufficient for the cross-node
 * safety checks in {@link SimInvariants}. Implemented by both
 * {@link ConsistencyPropertyTests.ClusterHarness} (the property-test / seed-sweep
 * harness) and {@link AdversarialSim} (the §4.3 adversarial harness), so one
 * invariant checker serves both without coupling them to each other.
 */
interface ClusterView {

    int nodeCount();

    RaftNode node(int i);

    RaftLog log(int i);

    VersionedConfigStore store(int i);
}
