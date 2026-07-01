# Integration Guide

Configd ships runnable services (see [Getting Started](Getting-Started.md)), but the modules are also
embeddable libraries. This guide covers embedding each layer in your Java application. If you want a
turnkey control plane, run `configd-server` instead of hand-wiring the consensus core.

## Important v1 limitations (read first)

- **Watches: server-side only, no shipped client driver yet.** For change-subscription (watches), the
  RFC section 2 watch protocol is implemented server-side (N=1) on the edge endpoint, but a conforming
  client driver is the next deliverable - so until one ships, clients poll (edge reads are in-process
  and sub-millisecond). Read via `LocalConfigStore.get(...)` (edge) or the control-plane `GET`, and
  apply deltas from your replication layer (below). N>1 multi-shard watch is v3. See
  [known-limitations section 2](../operations/known-limitations.md) for the watch guarantees, the
  deployment security model (segregate watch clients from the legacy SUBSCRIBE path), and the
  boundaries.
- **No encryption at rest.** Configd stores values plaintext (integrity-checked only - HMAC, ADR-0042;
  not encrypted). The `secure/` key prefix is a read-freshness guarantee (always-linearizable,
  fail-closed for security-critical keys), not confidentiality. Do not store secrets (passwords,
  tokens, private keys) in Configd - use a dedicated secret manager and keep only non-secret references
  here. At-rest encryption is a v2 item (RR-098).

See [`../operations/known-limitations.md`](../operations/known-limitations.md) for the complete,
current list.

## Choosing your integration level

| Level | Modules needed | Use case |
|---|---|---|
| Edge reads only | common, config-store, edge-cache | Application reads config from a local store fed by deltas from elsewhere |
| Full consensus | the core modules (or just run `configd-server`) | Application participates in the Raft cluster and manages its own config |
| Testing / simulation | the above plus testkit | Deterministic simulation of multi-node clusters |

## Edge-only integration (most common)

Most applications only need to read config. A separate control plane manages writes and pushes deltas
to edge nodes.

### 1. Create the local store

```java
import io.configd.edge.LocalConfigStore;
import io.configd.store.ReadResult;

// Create an empty edge store
LocalConfigStore store = new LocalConfigStore();
```

### 2. Read config values

```java
// Zero-allocation read - safe from any thread
ReadResult result = store.get("feature.flags.dark-mode");
if (result.found()) {
    byte[] value = result.value();
    long version = result.version();
    // Use the value...
}
```

### 3. Apply deltas (from your replication layer)

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

### 4. Enforce monotonic reads

Use `VersionCursor` to prevent reading stale data after observing a newer version:

```java
import io.configd.edge.VersionCursor;

// After a read, capture the cursor
VersionCursor cursor = new VersionCursor(result.version(), System.currentTimeMillis());

// Subsequent reads enforce monotonicity
ReadResult next = store.get("some.key", cursor);
// Returns NOT_FOUND if the store has fallen behind the cursor
```

### 5. Monitor staleness

```java
import io.configd.edge.StalenessTracker;

StalenessTracker tracker = new StalenessTracker();

// Call after each successful delta application. In real usage pass the LEADER-assigned
// commit timestamp carried on the notification (ADR-0035 section 2) - staleness is measured
// against that frontier (ADR-0039); the local clock here is illustrative only and would
// understate real data age on a quiet or lagging link.
tracker.recordUpdate(store.currentVersion(), System.currentTimeMillis());

// Check health
switch (tracker.currentState()) {
    case CURRENT     -> { /* healthy */ }
    case STALE       -> { /* >500ms behind - log warning */ }
    case DEGRADED    -> { /* >5s behind - alert */ }
    case DISCONNECTED -> { /* >30s - circuit break / fail open */ }
}
```

## Control-plane integration

For applications that participate in the Raft cluster directly. The turnkey option is to run
`configd-server` (main class `io.configd.server.ConfigdServer`), which wires all of the below for you,
including the owner-thread binding. Embed the consensus core directly only if you need low-level
control - and if you do, you MUST honor the owner-thread threading contract
([`../architecture/raft-threading-contract.md`](../architecture/raft-threading-contract.md)).

### 1. Configure the Raft node

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

// You must implement StateMachine - this is where committed entries
// are applied to your VersionedConfigStore (long apply(index, term, command))
StateMachine stateMachine = (index, term, command) -> {
    // Deserialize command, apply to VersionedConfigStore, return the applied seq
    return index;
};

RaftNode raft = new RaftNode(config, log, transport, stateMachine, new Random());
```

### 2. Drive the Raft node on a single owner thread

Configd's Raft implementation is tick-driven and single-owner: every entry point for a given node
must run on that node's one owner thread. Bind the owner as the FIRST task submitted to that executor
(never in the constructor), then drive `tick()` from it.

```java
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// One single-thread executor owns this RaftNode for the life of the process
ScheduledExecutorService owner = Executors.newSingleThreadScheduledExecutor();

// Bind ownership as the first task on the owner thread
owner.execute(raft::bindOwnerThread);

// Drive the tick loop on the owner thread
owner.scheduleAtFixedRate(raft::tick, 0, 1, TimeUnit.MILLISECONDS);

// Marshal inbound peer messages onto the owner thread - never call handleMessage inline
transport.onMessage(message -> owner.execute(() -> raft.handleMessage(message)));
```

### 3. Propose writes

```java
// propose() is an owner-only entry point - marshal onto the owner thread
byte[] command = serialize(new PutCommand("my.key", valueBytes));
owner.execute(() -> {
    ProposeOutcome outcome = raft.propose(command);
    // outcome indicates accept/reject; a non-leader rejects
});
```

### 4. Use the versioned store

The `VersionedConfigStore` is the control plane's MVCC store:

```java
import io.configd.store.VersionedConfigStore;

VersionedConfigStore store = new VersionedConfigStore();

// Writer thread (the Raft apply path)
store.put("my.key", valueBytes, sequenceNumber);

// Reader threads (any thread, lock-free)
ReadResult result = store.get("my.key");
```

## Thread-safety summary

| Component | Writer | Readers | Synchronization |
|---|---|---|---|
| `RaftNode` | Single owner thread per group | `monitorView()` / the safe volatile set only | None inside the node - owner-thread ownership (see the threading contract) |
| `VersionedConfigStore` | Single apply thread | Any thread | Volatile pointer to an immutable snapshot |
| `LocalConfigStore` | Single delta applier | Any thread | Volatile pointer to an immutable snapshot |
| `StalenessTracker` | Single delta applier | Any thread | Volatile fields |
