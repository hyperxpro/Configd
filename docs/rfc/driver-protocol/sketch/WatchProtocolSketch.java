// =============================================================================
// NON-WIRED DESIGN ARTIFACT for Configd driver-protocol RFC §2 (The Watch Protocol).
//
//   * NOT compiled into any Configd module. NOT production code. NOT wired to
//     any build. It imports NO Configd production class — only java.base.
//   * Its sole purpose is to make the wire-relevant *types* in 02-watches.md
//     concrete and javac-checkable, so the spec's encodings are unambiguous and
//     internally consistent. The normative source is 02-watches.md; if this file
//     and the spec ever disagree, the spec wins.
//   * Compiles standalone under JDK 25:  javac WatchProtocolSketch.java
//     (default package, single file; `java WatchProtocolSketch` runs a tiny
//      self-check of the cursor codec, max-merge, and the dedup predicate).
//
// It models exactly the §2 wire-relevant surface:
//   - the per-shard cursor VECTOR  (§3 / W3-1..W3-7): gid->S, the (uint32 gid,
//     uint64 S)[] encode/decode shape, update-one-component, component-wise
//     max-merge, "from now" (empty) — vector even at N=1 (W1-1).
//   - the new WATCH_* frame records and their FrameType codes from 0x0A (§5 / W5-1).
//   - the per-event (gid, S) stamp (§5.4 / W5-6).
//   - the at-least-once dedup predicate: drop iff S <= cursor[gid]  (W6-1).
//   - the NOT_AUTHORIZED (11) 403-class error-code addition (§7 / W7-5a).
// =============================================================================

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public final class WatchProtocolSketch {

    private WatchProtocolSketch() { }

    // -------------------------------------------------------------------------
    // §5.1 / W5-1 — FrameType code additions (the next free edge code is 0x0A).
    // The existing edge frames (0x01..0x09) are unchanged (W1-3) and not modeled
    // here; only the §2 additions are. WATCH_SNAPSHOT_* (0x10..0x12) reuse the
    // chunked snapshot catch-up mechanism plus a (watch_id, gid) multiplex tag (W5-3).
    // -------------------------------------------------------------------------
    public enum WatchFrameType {
        WATCH_CREATE(0x0A),          // client -> server
        WATCH_CANCEL(0x0B),          // client -> server
        WATCH_CREATED(0x0C),         // server -> client (ack; per-shard initial mode vector)
        WATCH_EVENT(0x0D),           // server -> client (a per-shard change batch, tagged (gid,S))
        WATCH_PROGRESS(0x0E),        // server -> client (bookmark; advance idle cursors)
        WATCH_CANCELED(0x0F),        // server -> client (terminal per-watch close)
        WATCH_SNAPSHOT_BEGIN(0x10),  // server -> client (per-(watch_id,gid) catch-up header)
        WATCH_SNAPSHOT_CHUNK(0x11),  // server -> client (per-(watch_id,gid) catch-up chunk)
        WATCH_SNAPSHOT_END(0x12);    // server -> client (per-(watch_id,gid) catch-up trailer)

        private final int code;
        WatchFrameType(int code) { this.code = code; }
        public int code() { return code; }
    }

    /** §1 A2-1 scope enum (typed field, never a path segment). */
    public enum Scope { GLOBAL, REGIONAL, LOCAL }

    /** §2.1 / W2-2 watch target forms. */
    public enum WatchTargetKind { KEY, PREFIX, FULL }

    /** §5.3 / W5-5 per-shard initial mode (mirrors the built EdgeFrame.Mode). */
    public enum WatchMode { TAIL, SNAPSHOT_FIRST }

    /** §5.4 / W5-6 change kind. */
    public enum ChangeKind { PUT, DELETE }

    // -------------------------------------------------------------------------
    // §7 / W7-5a — the ErrorCode taxonomy as it is USED on the watch path. Codes
    // 1..10 are pinned by the BUILT taxonomy (io.configd.distribution.wire.ErrorCode,
    // the source of truth — NOT assigned by §2); this sketch reproduces only the
    // subset §2 references, so it can be checked against that enum and cannot
    // silently diverge. §2 ADDS exactly ONE code — NOT_AUTHORIZED(11), the 403-class
    // authorization reject — distinct from the built AUTH_FAIL(4) (401-class authn).
    // -------------------------------------------------------------------------
    public enum WatchErrorCode {
        AUTH_FAIL(4),           // 401-class: identity/credential unacceptable (BUILT taxonomy)
        BAD_SUBSCRIBE(5),       // 400-class: malformed target/path/cursor (BUILT taxonomy)
        GAP_UNRECOVERABLE(6),   // replay source cannot cover the range -> re-list + re-create (BUILT taxonomy)
        DEMOTED_TO_CATCHUP(7),  // non-fatal: streaming -> snapshot catch-up (BUILT taxonomy)
        QUARANTINED(8),         // slow-consumer governor close; re-bootstrap after cooldown (BUILT taxonomy)
        SERVER_SHUTDOWN(9),     // orderly close (BUILT taxonomy)
        PROTOCOL_VIOLATION(10), // unexpected frame for state (BUILT taxonomy)
        NOT_AUTHORIZED(11);     // 403-class authorization reject  <-- §2 ADDITION (W7-5a)

        private final int code;
        WatchErrorCode(int code) { this.code = code; }
        public int code() { return code; }
    }

    // =========================================================================
    // §3 / W3 — THE PER-SHARD CURSOR VECTOR (the single most important type).
    // Wire shape (W3-5, identical to §1 A9-1/A4-4):
    //     [ count:u32 ] ( gid:u32  S:u64 )*count        ordered by gid ascending
    //     count == 0  ⇒  "from now per shard"  (W3-4)
    // gid is an unsigned 32-bit shard id (held in an int; ordered/printed
    // unsigned). S is the unsigned-64 applied-mutation sequence (held in a long).
    // A driver MUST treat this as a vector even at N=1 (a one-element vector). A
    // missing component reads as 0 = "from now" (W3-4).
    // =========================================================================
    public static final class WatchCursor {

        // Ordered by gid ASCENDING using UNSIGNED comparison (matches the wire order W3-5).
        private final NavigableMap<Integer, Long> components =
                new TreeMap<>(Comparator.comparingLong(Integer::toUnsignedLong));

        /** A fresh "from now per shard" cursor (W3-4): the empty vector. */
        public static WatchCursor fromNow() { return new WatchCursor(); }

        /** The S a driver holds for {@code gid}; absent component reads as 0 ("from now", W3-4). */
        public long get(int gid) { return components.getOrDefault(gid, 0L); }

        /** Number of present components (1 at N=1; still a vector — W1-1). */
        public int size() { return components.size(); }

        /** W3-6(1): update ONE component on a delivered event for {@code gid}. Never regresses. */
        public WatchCursor updateComponent(int gid, long s) {
            components.merge(gid, s, Math::max);
            return this;
        }

        /**
         * W3-6(2) / W6-5: component-wise MAX-MERGE (resume / failover). A driver
         * MUST NOT regress any component, so the merge takes the per-gid max. This
         * is what makes independent per-shard failover safe (the vector payoff).
         */
        public WatchCursor maxMerge(WatchCursor other) {
            for (Map.Entry<Integer, Long> e : other.components.entrySet()) {
                components.merge(e.getKey(), e.getValue(), Math::max);
            }
            return this;
        }

        /** W3-6(3): serialize to the (uint32 gid, uint64 S)[] wire shape, ordered by gid. */
        public byte[] encode() {
            ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES + components.size() * (Integer.BYTES + Long.BYTES));
            buf.putInt(components.size());
            for (Map.Entry<Integer, Long> e : components.entrySet()) { // TreeMap => ascending gid
                buf.putInt(e.getKey());
                buf.putLong(e.getValue());
            }
            return buf.array();
        }

        /** W3-6(3): deserialize the wire shape. Validates ascending-unsigned-gid order. */
        public static WatchCursor decode(ByteBuffer buf) {
            int count = buf.getInt();
            if (count < 0) {
                throw new IllegalArgumentException("bad cursor count: " + count);
            }
            WatchCursor c = new WatchCursor();
            long prevGidUnsigned = -1L;
            for (int i = 0; i < count; i++) {
                int gid = buf.getInt();
                long s = buf.getLong();
                long gidUnsigned = Integer.toUnsignedLong(gid);
                if (gidUnsigned <= prevGidUnsigned) {
                    throw new IllegalArgumentException(
                            "cursor not ordered by ascending gid at index " + i + " (gid=" + gidUnsigned + ")");
                }
                prevGidUnsigned = gidUnsigned;
                c.components.put(gid, s);
            }
            return c;
        }

        public static WatchCursor decode(byte[] bytes) { return decode(ByteBuffer.wrap(bytes)); }

        @Override
        public boolean equals(Object o) {
            return o instanceof WatchCursor that && this.components.equals(that.components);
        }

        @Override
        public int hashCode() { return components.hashCode(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Integer, Long> e : components.entrySet()) {
                if (!first) sb.append(", ");
                sb.append("gid=").append(Integer.toUnsignedLong(e.getKey())).append(":S=").append(e.getValue());
                first = false;
            }
            return sb.append('}').toString();
        }
    }

    /** §5.4 / W5-6 — the per-event (gid, S) stamp a driver dedups and reasons on. */
    public record EventStamp(int gid, long s) { }

    /**
     * W6-1 — the at-least-once DEDUP predicate: a driver MUST drop a WATCH_EVENT
     * iff its S is at or below the cursor component already held for that shard.
     * Otherwise it advances the component and delivers (per-key order, W4-2).
     */
    public static boolean isDuplicate(WatchCursor cursor, EventStamp stamp) {
        return stamp.s() <= cursor.get(stamp.gid());
    }

    // =========================================================================
    // §5 / W5 — the WATCH_* frame records (sealed family, mirroring EdgeFrame).
    // Field order matches the payload layouts in 02-watches.md §5.
    // =========================================================================
    public sealed interface WatchFrame
            permits WatchCreate, WatchCancel, WatchCreated, WatchEvent, WatchProgress,
                    WatchCanceled, WatchSnapshotBegin, WatchSnapshotChunk, WatchSnapshotEnd {
        WatchFrameType type();
    }

    /**
     * §5.2 WATCH_CREATE (client->server). cursor==fromNow() ⇒ "from now per shard".
     * The `flags` byte (W5-4a) is modeled as three booleans:
     *   bit0=fullChainVerify, bit1=prevValue, bit2=withInitialSnapshot.
     * `withInitialSnapshot` is the ONLY way to request existing state; cursor 0
     * ALONE means "from now per shard", NOT "replay" (W3-4 / W5-5).
     */
    public record WatchCreate(long watchId, Scope scope, WatchTargetKind targetKind,
                              String path, WatchCursor cursor,
                              boolean fullChainVerify, boolean prevValue,
                              boolean withInitialSnapshot) implements WatchFrame {
        public WatchCreate {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(cursor, "cursor");
            if (targetKind == WatchTargetKind.FULL && !path.isEmpty()) {
                throw new IllegalArgumentException("FULL target must carry an empty path (W5-4)");
            }
        }
        @Override public WatchFrameType type() { return WatchFrameType.WATCH_CREATE; }
    }

    /** §5.6 WATCH_CANCEL (client->server). */
    public record WatchCancel(long watchId) implements WatchFrame {
        @Override public WatchFrameType type() { return WatchFrameType.WATCH_CANCEL; }
    }

    /** §5.3 WATCH_CREATED (server->client) — per-shard initial (gid, latestSeq, mode). */
    public record WatchCreated(long watchId, List<ShardInit> shards) implements WatchFrame {
        public WatchCreated {
            Objects.requireNonNull(shards, "shards");
            shards = List.copyOf(shards);
        }
        @Override public WatchFrameType type() { return WatchFrameType.WATCH_CREATED; }

        /** Per-shard initial state in WATCH_CREATED (W5-5). */
        public record ShardInit(int gid, long latestSeq, WatchMode mode) {
            public ShardInit { Objects.requireNonNull(mode, "mode"); }
        }
    }

    /** §5.4 WATCH_EVENT (server->client) — one shard-commit, tagged (gid,S), batch-atomic (W5-6). */
    public record WatchEvent(long watchId, int gid, long s, long commitTsMillis,
                             List<Change> changes) implements WatchFrame {
        public WatchEvent {
            Objects.requireNonNull(changes, "changes");
            changes = List.copyOf(changes);
        }
        @Override public WatchFrameType type() { return WatchFrameType.WATCH_EVENT; }

        /** The (gid, S) stamp a driver dedups on (W6-1). */
        public EventStamp stamp() { return new EventStamp(gid, s); }
    }

    /**
     * §5.4 a single key change. `value` is null for a DELETE (W5-6: on the wire, val_len == -1).
     * `prev` is the pre-image and is NOT a v1 wire field — it is a forward-looking placeholder for the
     * W10-2 v2 `prev_value` extension (W5-4a) and is always null in v1.
     */
    public record Change(byte[] key, ChangeKind kind, byte[] value, byte[] prev /* W10-2 v2 placeholder; null in v1 */) {
        public Change {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** §5.5 WATCH_PROGRESS (server->client) — the bookmark: advance idle cursors, no events (W4-4). */
    public record WatchProgress(long watchId, WatchCursor cursor, long serverNowMillis) implements WatchFrame {
        public WatchProgress { Objects.requireNonNull(cursor, "cursor"); }
        @Override public WatchFrameType type() { return WatchFrameType.WATCH_PROGRESS; }
    }

    /** §5.7 WATCH_CANCELED (server->client) — terminal per-watch close (W5-9). */
    public record WatchCanceled(long watchId, WatchErrorCode code, WatchCursor oldest, String message)
            implements WatchFrame {
        public WatchCanceled {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            // `oldest` (per-shard oldestRetainedSeq) is present only for the GAP case (W6-4); may be null.
        }
        @Override public WatchFrameType type() { return WatchFrameType.WATCH_CANCELED; }
    }

    /** §5.8 WATCH_SNAPSHOT_BEGIN (server->client) — per-(watch_id, gid) catch-up header (W5-10). */
    public record WatchSnapshotBegin(long watchId, int gid, long snapshotSeq, int chunkCount, long totalBytes)
            implements WatchFrame {
        @Override public WatchFrameType type() { return WatchFrameType.WATCH_SNAPSHOT_BEGIN; }
    }

    /** §5.8 WATCH_SNAPSHOT_CHUNK (server->client). */
    public record WatchSnapshotChunk(long watchId, int gid, int index, byte[] bytes) implements WatchFrame {
        public WatchSnapshotChunk { Objects.requireNonNull(bytes, "bytes"); }
        @Override public WatchFrameType type() { return WatchFrameType.WATCH_SNAPSHOT_CHUNK; }
    }

    /** §5.8 WATCH_SNAPSHOT_END (server->client) — cutover: set cursor[gid]=snapshotSeq, resume (W5-10). */
    public record WatchSnapshotEnd(long watchId, int gid, long snapshotSeq) implements WatchFrame {
        @Override public WatchFrameType type() { return WatchFrameType.WATCH_SNAPSHOT_END; }
    }

    // =========================================================================
    // Tiny self-check (illustrative; not a test framework). Exercises the
    // cursor codec round-trip, the "from now" empty vector, update-one,
    // component-wise max-merge, and the dedup predicate.
    // =========================================================================
    public static void main(String[] args) {
        // (a) "from now per shard" is the empty vector (W3-4); a missing component reads as 0.
        WatchCursor now = WatchCursor.fromNow();
        require(now.size() == 0, "fromNow is empty");
        require(now.get(0) == 0L, "missing component reads as 0 (from now)");

        // (b) vector even at N=1 (W1-1): one component, still a vector.
        WatchCursor n1 = WatchCursor.fromNow().updateComponent(0, 42L);
        require(n1.size() == 1, "N=1 is a one-element vector");
        require(roundTrip(n1).equals(n1), "N=1 cursor round-trips");

        // (c) multi-shard encode/decode round-trip, ordered by gid ascending (W3-5).
        WatchCursor multi = WatchCursor.fromNow()
                .updateComponent(2, 7L).updateComponent(0, 100L).updateComponent(1, 9L);
        require(multi.size() == 3, "three components");
        require(roundTrip(multi).equals(multi), "multi-shard cursor round-trips");

        // (d) component-wise max-merge never regresses (W3-6 / W6-5).
        WatchCursor a = WatchCursor.fromNow().updateComponent(0, 100L).updateComponent(1, 5L);
        WatchCursor b = WatchCursor.fromNow().updateComponent(1, 9L).updateComponent(2, 3L);
        WatchCursor merged = WatchCursor.fromNow().maxMerge(a).maxMerge(b);
        require(merged.get(0) == 100L && merged.get(1) == 9L && merged.get(2) == 3L, "max-merge takes per-gid max");

        // (e) at-least-once dedup: drop iff S <= cursor[gid] (W6-1).
        WatchCursor c = WatchCursor.fromNow().updateComponent(1, 50L);
        require(isDuplicate(c, new EventStamp(1, 50L)), "S == cursor[gid] is a duplicate");
        require(isDuplicate(c, new EventStamp(1, 49L)), "S < cursor[gid] is a duplicate");
        require(!isDuplicate(c, new EventStamp(1, 51L)), "S > cursor[gid] is fresh");
        require(!isDuplicate(c, new EventStamp(9, 1L)), "a never-seen shard is fresh (from 0)");

        // (f) a WATCH_EVENT's stamp feeds the same dedup; the §2 addition is wired.
        WatchEvent ev = new WatchEvent(1L, 1, 51L, 1_700_000_000_000L,
                List.of(new Change("/a/b".getBytes(StandardCharsets.UTF_8), ChangeKind.PUT,
                        "v".getBytes(StandardCharsets.UTF_8), null)));
        require(!isDuplicate(c, ev.stamp()), "fresh event delivered");
        require(WatchErrorCode.NOT_AUTHORIZED.code() == 11, "NOT_AUTHORIZED is the 403-class code 11 (W7-5a)");
        require(WatchFrameType.WATCH_CREATE.code() == 0x0A, "WATCH_* codes start at 0x0A (W5-1)");

        // (g) a malformed (descending-gid) cursor is rejected on decode (W3-5 ordering invariant).
        require(rejectsDescendingGid(), "decode rejects non-ascending gid order");

        // (h) the with_initial_snapshot flag (bit2, W5-4a) is the ONLY way to request existing
        //     state; cursor 0 alone is "from now" (W3-4). A FULL target carries an empty path (W5-4).
        WatchCreate full = new WatchCreate(7L, Scope.GLOBAL, WatchTargetKind.FULL, "",
                WatchCursor.fromNow(), /*fullChainVerify*/ true, /*prevValue*/ false,
                /*withInitialSnapshot*/ true);
        require(full.withInitialSnapshot() && full.cursor().size() == 0,
                "with_initial_snapshot requests existing state while cursor stays 'from now' (W3-4)");

        System.out.println("WatchProtocolSketch self-check OK");
    }

    private static WatchCursor roundTrip(WatchCursor c) { return WatchCursor.decode(c.encode()); }

    private static boolean rejectsDescendingGid() {
        ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES + 2 * (Integer.BYTES + Long.BYTES));
        buf.putInt(2);
        buf.putInt(5); buf.putLong(1L);   // gid 5 first ...
        buf.putInt(2); buf.putLong(1L);   // ... then gid 2 (descending) => must be rejected
        buf.flip();
        try {
            WatchCursor.decode(buf);
            return false;
        } catch (IllegalArgumentException | BufferUnderflowException expected) {
            return true;
        }
    }

    private static void require(boolean cond, String what) {
        if (!cond) {
            throw new IllegalStateException("sketch self-check failed: " + what);
        }
    }
}
