# REST API

The REST API provides HTTP access to Configd's key-value store. It implements the pattern from Quicksilver's QSrest service: a stateless layer that enforces access control and keyspace separation.

## Endpoints

### Key-Value Operations

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/kv/{key}` | Read the current value of a key |
| `GET` | `/v1/kv/{key}?version=N` | MVCC read: value as of version N |
| `PUT` | `/v1/kv/{key}` | Write a key (request body = value) |
| `DELETE` | `/v1/kv/{key}` | Delete a key |

### Range Operations

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/scan?start=X&end=Y&limit=N` | Range scan from start to end |

### Operational

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/status` | Node status, cache metrics, version |
| `GET` | `/v1/health` | Health check (returns healthy + version) |

## Request/Response Format

### GET /v1/kv/{key}

**Response** (200 OK):
```json
{
  "success": true,
  "data": {
    "key": "dns:example.com",
    "value": "MS4yLjMuNA==",
    "version": 42
  }
}
```

Values are Base64-encoded to support arbitrary binary data.

**Response** (404 Not Found):
```json
{
  "success": false,
  "error": "Key not found: dns:example.com"
}
```

### PUT /v1/kv/{key}

**Request**: Raw bytes in the request body.

**Response** (200 OK):
```json
{
  "success": true,
  "data": {
    "key": "dns:example.com",
    "version": 43
  }
}
```

The `version` is the transaction log sequence number assigned to this write. It can be used for MVCC reads and replication tracking.

### GET /v1/scan

**Query parameters**:
- `start` -- Start key (inclusive). Required.
- `end` -- End key (exclusive). Optional.
- `limit` -- Maximum number of entries. Default: 100.

**Response**:
```json
{
  "success": true,
  "data": [
    {"key": "dns:a.com", "value": "...", "version": 10},
    {"key": "dns:b.com", "value": "...", "version": 15}
  ]
}
```

### GET /v1/status

Returns comprehensive node status:

```json
{
  "success": true,
  "data": {
    "nodeId": "root-1",
    "role": "ROOT",
    "mode": "REPLICA",
    "dataCenter": "dc1",
    "currentVersion": 1042,
    "approximateKeys": 50000,
    "cache": {
      "l1Hits": 98234,
      "l2Hits": 1520,
      "l3Hits": 246,
      "misses": 0,
      "hitRate": "1.0000",
      "localCacheEntries": 12400,
      "localCacheUtilization": "0.45"
    }
  }
}
```

## Implementation

The REST API is built on Netty's HTTP codec stack:

```
  Netty Pipeline:
    HttpServerCodec        -> HTTP request/response encoding
    HttpObjectAggregator   -> Assembles chunked requests (up to 1 MB)
    ApiHandler             -> Routes requests to handlers
```

### Read Path

1. Parse the key from the URL path
2. Parse optional `version` query parameter
3. Call `cacheManager.get(key)` or `cacheManager.get(key, version)` for MVCC reads
4. The cache manager checks L1, L1.5, L2, and L3 in order
5. Return the value (Base64-encoded) with the current version

### Write Path

1. Parse the key from the URL path
2. Read the raw request body as the value
3. Call `writer.put(key, value)` which queues the write
4. Await the `CompletableFuture<Long>` for the assigned sequence number
5. Return the key and version

Writes go through the `BatchingTransactionLogWriter`, which means they are batched within 500ms windows. The API awaits the future, so the response is sent only after the write is durably committed.

## Notes

- The API server listens on a separate port from the internal protocol (default: 7401 vs. 7400)
- Multiple Configd instances on the same server each get their own API port (base + instance index)
- The API is stateless: it delegates all storage and caching to the underlying components
- Content type is always `application/json`
- Max request body size: 1 MB (configurable in `HttpObjectAggregator`)
