# Transaction Log

The transaction log is the foundation of Configd's replication protocol. Every write to the system -- whether a SET or DELETE -- is recorded as a log entry with a monotonically increasing sequence number. Downstream nodes replicate by pulling entries from the log.

## Architecture

```
  Writer (API / Internal)
       |
       v
  +-----------------------------+
  | BatchingTransactionLogWriter |   Accumulates writes in 500ms windows
  +-------------+---------------+
                |  flush
                v
  +-----------------------------+
  |     TransactionLog          |   Interface
  +-------------+---------------+
                |
  +-------------v---------------+
  |  RocksDBTransactionLog      |   Implementation
  |                             |
  |  key: [8-byte seq number]   |
  |  val: Snappy(serialized     |
  |       TransactionLogEntry)  |
  +-----------------------------+
```

## Components

### TransactionLogEntry

A single log entry. Each entry contains:

| Field | Size | Description |
|-------|------|-------------|
| sequenceNumber | 8 bytes | Monotonically increasing ID |
| operation | 1 byte | `0x01` = SET, `0x02` = DELETE |
| timestamp | 8 bytes | Epoch milliseconds when written |
| keyLength | 4 bytes | Length of the key |
| key | N bytes | The key |
| valueLength | 4 bytes | Length of the value (0 for DELETE) |
| value | M bytes | The value (absent for DELETE) |
| crc32 | 4 bytes | CRC32 checksum of all preceding bytes |

**Integrity**: The CRC32 checksum at the end of each entry allows detection of corruption at any level -- disk, network, or software. Deserialization will throw `IllegalStateException` on CRC mismatch.

**Binary format**: Entries are serialized to a compact binary format (not JSON or protobuf) for minimal overhead. The format is self-describing -- each entry encodes its own field lengths.

### RocksDBTransactionLog

Persists log entries in a dedicated RocksDB instance. Keys are 8-byte big-endian sequence numbers, so entries are naturally ordered. Values are Snappy-compressed serialized entries.

Operations:

- `append(operation, key, value)` -- assigns the next sequence number, serializes, compresses, and writes. Returns the assigned sequence number.
- `readAfter(afterSequence, limit)` -- seeks to `afterSequence + 1` and reads up to `limit` entries. This is the core replication query.
- `readRange(fromSequence, toSequence)` -- reads a specific range (inclusive).
- `truncateBefore(sequenceNumber)` -- uses `deleteRange` to remove old entries. Called periodically to bound log size.
- `getLatestSequence()` -- returns the highest sequence number.
- `getOldestSequence()` -- returns the lowest available sequence number (entries before this have been truncated).

**Recovery**: On startup, the log scans to find the latest and oldest sequence numbers, so the counter resumes correctly after a restart.

**Compression**: Snappy compression is applied per-entry. For Configd's workload (small config values, often repetitive), this provides meaningful savings with negligible CPU cost.

### BatchingTransactionLogWriter

Cloudflare's Quicksilver batches writes within 500ms windows into single disk flushes. This dramatically reduces I/O overhead -- instead of one fsync per write, there's one per batch.

The batching writer:

1. Accepts writes via `put(key, value)` and `delete(key)`, returning a `CompletableFuture<Long>` for the assigned sequence number.
2. Accumulates writes in a list protected by a lock.
3. A scheduled task runs every 500ms (configurable), draining the pending list and flushing all operations as a batch.
4. Each entry is appended to the transaction log and applied to the KV store.
5. All futures are completed with their assigned sequence numbers.

Callers can await the future if they need confirmation, or fire-and-forget if they trust the batching.

## Data Flow

### Write Path

```
1. Client calls PUT /v1/kv/my-key
2. RestApiServer calls writer.put(key, value)
3. Write is queued in BatchingTransactionLogWriter
4. Within 500ms, the batch flushes:
   a. TransactionLog.append() -> assigns seq=42, serializes, Snappy-compresses, writes to RocksDB
   b. KVStore.put(key, value, version=42) -> writes to default CF and MVCC CF
5. Future completes with seq=42
6. API responds with {"version": 42}
```

### Replication Query

```
1. Downstream node sends: "give me entries after sequence 40, limit 1000"
2. RocksDBTransactionLog.readAfter(40, 1000)
3. Seeks to sequence key 41 in RocksDB
4. Iterates forward, decompressing and deserializing each entry
5. Returns entries [41, 42, 43, ...]
```

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| batchWriteIntervalMs | 500 | Flush interval for batched writes |
| Write buffer size | 32 MB | RocksDB write buffer for the log |
| Compaction style | FIFO | Old entries are naturally compacted away |

## Sequence Number Guarantees

- **Monotonically increasing**: Each entry gets a strictly higher number than the previous.
- **No gaps after truncation**: `readAfter(n, limit)` returns entries starting from `n+1`, regardless of truncation.
- **Persisted across restarts**: Sequence counter is recovered from the last entry in the log on startup.
- **Single writer**: The AtomicLong counter is threadsafe, but the batching writer serializes all writes through a single scheduled thread, avoiding contention.
