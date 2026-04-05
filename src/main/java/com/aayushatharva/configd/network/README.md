# Network Layer

The network layer provides inter-node communication using Netty, a high-performance non-blocking I/O framework. All internal communication (replication, cache lookups, relay requests, gossip) flows through this layer.

## Architecture

```
  +------------------+            +------------------+
  |  Proxy Node      |            |  Replica Node    |
  |                  |            |                  |
  |  NetworkClient --+-- TCP ---->+ NetworkServer    |
  |                  |            |    |             |
  |  ConnectionPool  |            |    v             |
  |  (manages        |            |  MessageCodec    |
  |   clients)       |            |    |             |
  |                  |            |    v             |
  |                  |            |  Handler         |
  |                  |            |  (routes by      |
  |                  |            |   MessageType)   |
  +------------------+            +------------------+
```

## Wire Protocol

All messages use a length-prefixed binary protocol:

```
+----------------+----------+--------------+-------------------+
| Frame Length   | Type     | Request ID   | Payload           |
| (4 bytes)      | (1 byte) | (8 bytes)    | (variable)        |
| uint32         | enum     | uint64       | bytes             |
+----------------+----------+--------------+-------------------+

Frame Length = size of everything after this field (type + requestId + payload)
```

**Request/response correlation**: Each request carries a unique `requestId`. The response echoes the same ID. The client maintains a `ConcurrentHashMap<Long, CompletableFuture<Message>>` to match responses to their pending requests.

**Fire-and-forget**: Some messages (like `PREFETCH_NOTIFY` and `CACHE_STORE_REQUEST`) don't expect a response. These use `sendOneWay()` which writes without registering a pending future.

## Message Types

| Type | Code | Direction | Description |
|------|------|-----------|-------------|
| `REPLICATION_PULL_REQUEST` | `0x01` | downstream -> upstream | Pull transaction log entries |
| `REPLICATION_PULL_RESPONSE` | `0x02` | upstream -> downstream | Entries response |
| `CACHE_LOOKUP_REQUEST` | `0x10` | proxy -> shard owner | L2 sharded cache lookup |
| `CACHE_LOOKUP_RESPONSE` | `0x11` | shard owner -> proxy | Cached value response |
| `CACHE_STORE_REQUEST` | `0x12` | proxy -> shard owner | Store value in L2 cache |
| `CACHE_STORE_RESPONSE` | `0x13` | shard owner -> proxy | Store confirmation |
| `RELAY_GET_REQUEST` | `0x20` | proxy -> relay | Fetch from full replica |
| `RELAY_GET_RESPONSE` | `0x21` | relay -> proxy | Replica value response |
| `PREFETCH_NOTIFY` | `0x30` | relay -> proxies | Broadcast resolved cache miss |
| `GOSSIP_PING` | `0x40` | node -> node | Gossip heartbeat |
| `GOSSIP_PONG` | `0x41` | node -> node | Gossip response |
| `GOSSIP_MEMBER_LIST` | `0x42` | node -> node | Full member list exchange |
| `HEALTH_CHECK` | `0x50` | any -> any | Liveness probe |
| `HEALTH_RESPONSE` | `0x51` | any -> any | Liveness confirmation |

## Components

### MessageCodec

A Netty `ByteToMessageCodec` that handles framing:

- **Encode**: Writes the frame length, type byte, request ID, and payload.
- **Decode**: Reads the frame length, waits for the full frame to arrive, then extracts the type, request ID, and payload. Handles partial reads correctly (TCP streaming).
- **Max frame size**: 64 MB. Frames larger than this are rejected to prevent memory exhaustion.

### NetworkServer

A Netty `NioServerSocketChannel` that accepts incoming TCP connections. Each connection gets its own pipeline:

```
  SocketChannel pipeline:
    MessageCodec  ->  Handler (delegates to ConfigdInstance.handleInternalMessage)
```

Configuration:
- Boss group: 1 thread (accepts connections)
- Worker group: default (2x CPU cores, handles I/O)
- `SO_BACKLOG`: 256
- `SO_KEEPALIVE`: enabled
- `TCP_NODELAY`: enabled (disables Nagle's algorithm for low latency)

### NetworkClient

A Netty `NioSocketChannel` that connects to a remote node. Supports:

- **Request/response**: `send(message)` returns a `CompletableFuture<Message>` that completes when the response with the matching request ID arrives.
- **Timeout**: Requests time out after 5 seconds.
- **Fire-and-forget**: `sendOneWay(message)` writes without waiting for a response.
- **Connection state**: `isConnected()` checks if the channel is active.
- **Automatic cleanup**: When the channel becomes inactive, all pending futures are completed exceptionally.

### ConnectionPool

Manages a pool of `NetworkClient` instances, one per peer address:

- `getConnection(host, port)` -- lazy creation: connects on first use, reuses existing connections.
- `getOrReconnect(host, port)` -- checks if the existing connection is still active. If not, closes it and creates a new one.
- `removeConnection(host, port)` -- explicitly closes and removes a connection.
- `activeConnections()` -- count of currently active connections.

This prevents the thundering herd problem where many proxies all try to connect directly to a replica. Instead, relay nodes pool connections.

## Why Netty?

- **Non-blocking**: Handles thousands of concurrent connections without thread-per-connection overhead
- **Zero-copy**: Minimizes data copying between kernel and user space
- **Pipeline model**: Clean separation of framing, decoding, and business logic
- **Battle-tested**: Used by virtually every high-performance Java networking project (gRPC, Cassandra, Elasticsearch, etc.)
