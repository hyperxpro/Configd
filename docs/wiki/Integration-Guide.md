# Integration Guide

Configd is an embeddable library. This guide covers how to integrate each layer into your Java application.

## Choosing Your Integration Level

| Level | Modules Needed | Use Case |
|-------|---------------|----------|
| **Edge reads only** | common, config-store, edge-cache | Application reads config from a local store that receives deltas from elsewhere |
| **Full consensus** | All core modules | Application participates in the Raft cluster and manages its own config |
| **Testing/simulation** | All + testkit | Deterministic simulation of multi-node clusters |

## Edge-Only Integration (Most Common)

Most applications only need to read config. A separate control plane manages writes and pushes deltas to edge nodes.

### 1. Create the Local Store

```java
import io.configd.edge.LocalConfigStore;
import io.configd.store.ReadResult;

// Create an empty edge store
LocalConfigStore store = new LocalConfigStore();
```

### 2. Read Config Values

```java
// Zero-allocation read — safe from any thread
ReadResult result = store.get("feature.flags.dark-mode");
if (result.found()) {
    byte[] value = result.value();
    long version = result.version();
    // Use the value...
}
```

### 3. Apply Deltas (From Your Replication Layer)

A single writer thread applies incoming deltas:

```java
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

// Build a delta (typically deserialized from the wire)
ConfigDelta delta = new ConfigDelta(
    currentVersion,          // fromVersion: must match store.currentVersion()
    newVersion,              // toVersion
    List.of(
        new ConfigMutation.Put("feature.flags.dark-mode", newValueBytes),
        new ConfigMutation.Delete("deprecated.key")
    )
);

store.applyDelta(delta);
```

### 4. Enforce Monotonic Reads

Use `VersionCursor` to prevent reading stale data after observing a newer version:

```java
import io.configd.edge.VersionCursor;

// After a read, capture the cursor
VersionCursor cursor = new VersionCursor(result.version(), System.currentTimeMillis());

// Subsequent reads enforce monotonicity
ReadResult next = store.get("some.key", cursor);
// Returns NOT_FOUND if the store has fallen behind the cursor
```

### 5. Monitor Staleness

```java
import io.configd.edge.StalenessTracker;

StalenessTracker tracker = new StalenessTracker();

// Call after each successful delta application
tracker.recordUpdate(store.currentVersion(), System.currentTimeMillis());

// Check health
switch (tracker.currentState()) {
    case CURRENT     -> { /* healthy */ }
    case STALE       -> { /* >500ms behind — log warning */ }
    case DEGRADED    -> { /* >5s behind — alert */ }
    case DISCONNECTED -> { /* >30s — circuit break / fail open */ }
}
```

## Control Plane Integration

For applications that participate in the Raft cluster directly.

### 1. Configure the Raft Node

```java
import io.configd.common.NodeId;
import io.configd.raft.*;

import java.util.Set;
import java.util.Random;

NodeId self = NodeId.of(1);
Set<NodeId> peers = Set.of(NodeId.of(2), NodeId.of(3));

RaftConfig config = RaftConfig.of(self, peers);
RaftLog log = new RaftLog();

// You must implement RaftTransport for your network layer
RaftTransport transport = new MyNettyTransport();

// You must implement StateMachine — this is where committed entries
// are applied to your VersionedConfigStore
StateMachine stateMachine = (index, term, command) -> {
    // Deserialize command, apply to VersionedConfigStore
};

RaftNode raft = new RaftNode(config, log, transport, stateMachine, new Random());
```

### 2. Drive the Raft Node

Configd's Raft implementation is tick-driven, not threaded. You call `tick()` at a regular interval from your I/O thread:

```java
// On your dedicated Raft I/O thread (single-threaded — no synchronization needed)
ScheduledExecutorService raftThread = Executors.newSingleThreadScheduledExecutor();

raftThread.scheduleAtFixedRate(() -> {
    raft.tick();
}, 0, 1, TimeUnit.MILLISECONDS);

// When messages arrive from peers:
transport.onMessage(message -> raft.handleMessage(message));
```

### 3. Propose Writes

```java
byte[] command = serialize(new PutCommand("my.key", valueBytes));
boolean accepted = raft.propose(command);
// accepted == false if this node is not the leader
```

### 4. Use the Versioned Store

The `VersionedConfigStore` is the control plane's MVCC store:

```java
import io.configd.store.VersionedConfigStore;

VersionedConfigStore store = new VersionedConfigStore();

// Writer thread (Raft apply thread)
store.put("my.key", valueBytes, sequenceNumber);

// Reader threads (any thread, lock-free)
ReadResult result = store.get("my.key");
```

## Thread Safety Summary

| Component | Writer | Readers | Synchronization |
|-----------|--------|---------|-----------------|
| `RaftNode` | Single I/O thread | None (query via getters) | None — single-threaded by design |
| `VersionedConfigStore` | Single apply thread | Any thread | Volatile pointer to immutable snapshot |
| `LocalConfigStore` | Single delta applier | Any thread | Volatile pointer to immutable snapshot |
| `StalenessTracker` | Single delta applier | Any thread | Volatile fields |
