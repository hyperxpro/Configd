package io.configd.testkit;

import io.configd.distribution.CommitNotification;
import io.configd.edge.VersionCursor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.ReadResult;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-session monotonic reads ACROSS edge restart (the read/cursor half is the edge client; the
 * re-bootstrap path is the recovery path). An edge is a cache: a crash loses the store. A client
 * that holds a cursor at version V, after the edge crashes + restarts + re-bootstraps, must have its reads
 * with cursor V <b>refused</b> (NOT_FOUND via the monotonic_read seam) until the rebuilt
 * store catches up PAST V - never serving pre-crash-stale data.
 *
 * <p>The clause that gets silently dropped without this test: after restart the edge
 * re-bootstraps from a snapshot that may be >= or &lt; the client's cursor; the cursor check
 * must run against the POST-BOOTSTRAP store version, and reads during bootstrap (store still
 * behind the cursor) must refuse. Driven through the real {@link EdgeActor} ->
 * {@link io.configd.edge.EdgeClientCore} read path (the monitor-wired store + monotonic_read seam).
 */
class MonotonicReadAcrossEdgeRestartTest {

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private EdgeActor newEdge() {
        return new EdgeActor(EdgeActor.EDGE_ID_BASE, 0, now::get);
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private CommitNotification notif(long seq, long from, long to, String key, String value) {
        var delta = new ConfigDelta(from, to,
                List.<ConfigMutation>of(new ConfigMutation.Put(key, bytes(value))));
        return new CommitNotification(seq, now.get(), delta);
    }

    private static ConfigSnapshot snapshot(long version, String key, String value) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        data = data.put(key, new VersionedValue(bytes(value), version, version));
        return new ConfigSnapshot(data, version, version);
    }

    @Test
    void readWithHeldCursorRefusesUntilPostRestartStoreCatchesUp() {
        EdgeActor edge = newEdge();

        for (long s = 1; s <= 5; s++) {
            edge.deliver(new EdgeStream.Notify(notif(s, s - 1, s, "k" + s, "v" + s)));
        }
        edge.tick();
        assertEquals(5, edge.cursor());
        VersionCursor heldCursor = new VersionCursor(5, now.get());
        ReadResult preCrash = edge.get("k5", heldCursor);
        assertTrue(preCrash.found(), "client reads k5 at version 5 before the crash");

        edge.crash();
        edge.restart();
        assertEquals(0, edge.cursor(), "a restarted cache is empty at cursor 0");

        // Reads with the held cursor (version 5) against the empty post-restart store must
        // REFUSE - never serve pre-crash data. The monotonic_read seam (test mode) THROWS on a cursor
        // ahead of the store, which is the strongest form of "refused".
        assertThrows(AssertionError.class, () -> edge.get("k5", heldCursor),
                "a held cursor ahead of the rebuilt store must refuse (INV-M1), not serve stale");

        // RE-BOOTSTRAP in progress: a snapshot at version 3 (BELOW the held cursor 5) - a
        // partial catch-up. Reads with cursor 5 must STILL refuse (store 3 < cursor 5).
        edge.deliver(new EdgeStream.Snapshot(snapshot(3, "k3", "v3"), 3));
        edge.tick();
        assertEquals(3, edge.cursor());
        assertThrows(AssertionError.class, () -> edge.get("k5", heldCursor),
                "during bootstrap (store 3 < cursor 5) the read must still refuse");

        // Bootstrap completes PAST the held cursor: a snapshot at version 6 (>= 5). Now the
        // store has caught up past the cursor, so the read is served again (monotonic).
        edge.deliver(new EdgeStream.Snapshot(snapshot(6, "k5", "v5"), 6));
        edge.tick();
        assertEquals(6, edge.cursor());
        ReadResult postBootstrap = edge.get("k5", heldCursor);
        assertTrue(postBootstrap.found(),
                "once the rebuilt store catches up past the cursor, reads are served again");
        assertArrayEquals(bytes("v5"), postBootstrap.value(),
                "the served value is the post-bootstrap state, never pre-crash-stale data");
    }

    @Test
    void cursorlessReadAfterRestartNeverServesPreCrashData() {
        EdgeActor edge = newEdge();
        edge.deliver(new EdgeStream.Notify(notif(1, 0, 1, "secret", "pre-crash")));
        edge.tick();
        assertTrue(edge.get("secret").found());

        edge.crash();
        edge.restart();

        assertFalse(edge.get("secret").found(),
                "a restarted cache must not serve the pre-crash value (the cache was lost)");
    }
}
