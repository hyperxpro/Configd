# Discovery (Gossip Protocol)

Configd uses a gossip-based discovery protocol called the Network Oracle to enable self-organized, self-healing cluster topology. This replaces Configd v1's hardcoded IP addresses in Salt configuration with a dynamic peer-to-peer system.

## Architecture

```
  +--------+     gossip (UDP)     +--------+
  | Node A | <------------------> | Node B |
  +---+----+                      +---+----+
      |          gossip (UDP)         |
      +----------+       +----------+
                 |       |
              +--v-------v--+
              |   Node C    |
              +-------------+

  Each node exchanges its member list with random peers
  every gossipIntervalMs (default: 1 second).
```

## How Gossip Works

The gossip protocol uses a **pull-push** model over UDP:

1. **Periodic round** (every `gossipIntervalMs`): Each node selects up to 3 random known peers and sends its full member list.
2. **Seed contact**: Every round also sends to configured seed nodes, ensuring convergence even if the random selection misses some nodes.
3. **On receive**: When a node receives a member list, it merges it with its own. If a received entry is newer (by `lastSeen` timestamp), it replaces the local entry. The receiver also sends its own member list back (push).
4. **Expiration**: Members that haven't been heard from in 30 gossip intervals are removed.

This achieves **eventual convergence**: given enough gossip rounds, every node learns about every other node, even without a central registry.

## Components

### GossipProtocol

The main discovery service. Implements both the gossip transport (UDP via Netty) and the `NodeDiscovery` interface.

**Member list format** (JSON over UDP):
```json
[
  {
    "nodeId": "proxy-dc1-1",
    "host": "10.0.1.50",
    "internalPort": "7400",
    "apiPort": "7401",
    "role": "LEAF",
    "mode": "PROXY",
    "dataCenter": "dc1",
    "region": "us-east",
    "lastSeen": "1712345678000",
    "rtt": "1500000"
  }
]
```

**RTT tracking**: When a gossip response is received, the round-trip time is computed and stored with the member entry. This enables the `findClosestNode()` method to select the lowest-latency peer for operations like relay selection.

**Discovery queries**:
- `getKnownNodes()` -- all nodes in the cluster
- `getNodesInDataCenter(dc)` -- nodes in a specific data center
- `findClosestNode(dc)` -- the node with the lowest RTT in a data center
- `isAlive(nodeId)` -- whether a node is considered alive

### NodeDiscovery (Interface)

The abstract contract for node discovery. The `GossipProtocol` is the implementation, but the interface allows for alternative discovery backends (e.g., DNS-based, Consul-based) without changing the rest of the system.

### HealthMonitor

Monitors the health of peer nodes:

- **Heartbeat recording**: When a gossip message is received from a node, its heartbeat timestamp and RTT are recorded.
- **Failure detection**: A periodic task checks all known nodes. If a node hasn't sent a heartbeat within 15 seconds, its failure count increments. After 3 consecutive failures, the node is marked as dead.
- **Recovery**: A new heartbeat from a "dead" node resets its failure count and marks it alive again.

The health monitor runs on its own scheduled thread and is consulted by the replication system when choosing upstream nodes and by the sharded cache when routing requests.

## Why Gossip?

Quicksilver v1 hardcoded upstream IP addresses in Salt configuration. This was rigid and required manual updates when servers were added, removed, or their IPs changed. The gossip-based Network Oracle provides:

- **Self-organization**: New nodes discover the cluster by contacting any seed node
- **Self-healing**: Failed nodes are automatically detected and removed from the member list
- **No single point of failure**: Every node participates in gossip; there's no central registry to fail
- **Latency awareness**: RTT measurements enable smart routing decisions
- **Low overhead**: UDP messages are small (a few KB for the member list) and infrequent (1/second)

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| gossipPort | 7402 | UDP port for gossip messages |
| gossipIntervalMs | 1000 | How often to gossip with peers |
| gossipSeeds | [] | Initial seed addresses for bootstrap |
