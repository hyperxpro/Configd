package io.configd.testkit;

import io.configd.common.Storage;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * B1 self-test (Session 4): proves {@link FaultInjectingStorage} injects each operational
 * fault exactly per the oracle catalogue, and otherwise delegates transparently. A fault
 * injector that doesn't actually inject is worse than none.
 */
class FaultInjectingStorageTest {

    @Test
    void failNextWritesThrowsThenRecovers() {
        FaultInjectingStorage s = new FaultInjectingStorage(Storage.inMemory()).failNextWrites(2);
        assertThrows(UncheckedIOException.class, () -> s.appendToLog("wal", new byte[]{1}));
        assertThrows(UncheckedIOException.class, () -> s.put("k", new byte[]{2}));
        // Third write succeeds (countdown exhausted) and is durable.
        s.put("k", new byte[]{3});
        assertArrayEquals(new byte[]{3}, s.get("k"));
        assertEquals(2, s.writeFaultsFired());
    }

    @Test
    void enospcFiresOnceCumulativeBytesExceedLimit() {
        FaultInjectingStorage s = new FaultInjectingStorage(Storage.inMemory()).enospcAfterBytes(5);
        s.appendToLog("wal", new byte[]{1, 2, 3});      // total 3 <= 5 ok
        assertThrows(UncheckedIOException.class,
                () -> s.appendToLog("wal", new byte[]{4, 5, 6}));  // total 6 > 5 -> ENOSPC
        assertEquals(1, s.readLog("wal").size(), "the rejected append must not be persisted");
    }

    @Test
    void failNextSyncsThrows() {
        FaultInjectingStorage s = new FaultInjectingStorage(Storage.inMemory()).failNextSyncs(1);
        assertThrows(UncheckedIOException.class, s::sync);
        s.sync(); // second sync ok
        assertEquals(1, s.syncFaultsFired());
    }

    @Test
    void shortReadDropsLastFrame() {
        FaultInjectingStorage s = new FaultInjectingStorage(Storage.inMemory()).shortReadLog("wal");
        s.appendToLog("wal", new byte[]{1});
        s.appendToLog("wal", new byte[]{2});
        s.appendToLog("wal", new byte[]{3});
        List<byte[]> read = s.readLog("wal");
        assertEquals(2, read.size(), "short read must drop the last frame");
        assertArrayEquals(new byte[]{1}, read.get(0));
        assertArrayEquals(new byte[]{2}, read.get(1));
        // A different log is unaffected.
        s.appendToLog("other", new byte[]{9});
        assertEquals(1, s.readLog("other").size());
    }

    @Test
    void transparentWhenNoFaultArmed() {
        FaultInjectingStorage s = new FaultInjectingStorage(Storage.inMemory());
        s.put("a", new byte[]{7});
        s.appendToLog("wal", new byte[]{8});
        s.sync();
        assertArrayEquals(new byte[]{7}, s.get("a"));
        assertEquals(1, s.readLog("wal").size());
        assertEquals(0, s.writeFaultsFired());
    }
}
