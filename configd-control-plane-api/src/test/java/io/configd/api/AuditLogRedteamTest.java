package io.configd.api;

import io.configd.common.Clock;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent red-team pass over the {@link AuditLog} chain-bound record header.
 *
 * <p>The builder proved drop, reorder, a bad {@code AUDIT_MAGIC}, and a flipped {@code recordVersion}
 * are all caught. This pass adds the two attack shapes the builder did not perform directly — an
 * INSERTION of a forged record and an IN-PLACE field-byte mutation — plus the specific
 * version-downgrade-to-0 case (the frozen reserved-illegal record version), all bound into the
 * keyed HMAC chain. Frames are tampered on an in-memory store with no frame CRC, so it is the AUDIT
 * chain — not the container checksum — that must catch each attack.
 */
class AuditLogRedteamTest {

    private static Clock fixedClock(AtomicLong millis) {
        return new Clock() {
            @Override public long currentTimeMillis() { return millis.get(); }
            @Override public long nanoTime() { return millis.get() * 1_000_000L; }
        };
    }

    private static SecretKey auditKey() {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) 0x11);
        return new SecretKeySpec(k, "HmacSHA256");
    }

    @Test
    void insertingAForgedRecordBreaksTheChain() {
        AtomicLong now = new AtomicLong(1_000L);
        TamperStore store = new TamperStore();
        AuditLog log = new AuditLog(store, fixedClock(now), auditKey());
        log.record("writer", "PUT", "app/a", "committed seq=1");
        now.incrementAndGet();
        log.record("writer", "DELETE", "app/b", "committed seq=2");
        assertTrue(log.verifyPersisted().valid(), "precondition: untampered chain verifies");

        // Attack: splice a duplicate of frame 0 into the middle. The inserted record's declared
        // prevHash (genesis) no longer matches the expected predecessor (record 0's hash), so the
        // strict linkage check breaks the chain at the insertion point.
        store.insertFrame(1, store.frame(0).clone());

        AuditLog.VerifyResult r = log.verifyPersisted();
        assertFalse(r.valid(), "an inserted record must break the chain linkage");
        assertEquals(1, r.brokenIndex(), "verify must pinpoint the inserted record");
    }

    @Test
    void inPlaceFieldByteMutationBreaksTheChain() {
        AtomicLong now = new AtomicLong(1_000L);
        TamperStore store = new TamperStore();
        AuditLog log = new AuditLog(store, fixedClock(now), auditKey());
        log.record("writer", "PUT", "app/a", "committed seq=1");
        now.incrementAndGet();
        log.record("admin", "DELETE", "app/secret", "committed seq=2");
        assertTrue(log.verifyPersisted().valid(), "precondition: untampered chain verifies");

        // Attack: flip a byte inside the canonical block of the second frame WITHOUT touching the
        // magic/version/lengths, so the frame still decodes. Layout is
        // [magic:4][version:1][canonicalLen:8][canonical...]; offset 20 is the timestamp's low byte,
        // a pure value byte. The MAC is over the canonical bytes, so the edit no longer matches the
        // stored recordHash.
        store.flipByte(1, 20);

        AuditLog.VerifyResult r = log.verifyPersisted();
        assertFalse(r.valid(), "an in-place canonical-field edit must break the MAC");
        assertEquals(1, r.brokenIndex());
    }

    @Test
    void recordVersionDowngradeToZeroBreaksTheChain() {
        AtomicLong now = new AtomicLong(1_000L);
        TamperStore store = new TamperStore();
        AuditLog log = new AuditLog(store, fixedClock(now), auditKey());
        log.record("writer", "PUT", "app/a", "committed seq=1");
        assertTrue(log.verifyPersisted().valid(), "precondition: untampered chain verifies");

        // Attack: set the recordVersion byte (offset 4) to the reserved-illegal 0. The magic still
        // matches so decode accepts the frame, but the version rides into the chain MAC input, so the
        // downgrade is caught as a MAC mismatch — not silently accepted as an older record.
        store.setByte(0, 4, (byte) 0);

        AuditLog.VerifyResult r = log.verifyPersisted();
        assertFalse(r.valid(), "a recordVersion downgrade to 0 must break the chain (version is MAC-bound)");
        assertEquals(0, r.brokenIndex());
    }

    private static final class TamperStore implements Storage {
        private final Map<String, List<byte[]>> logs = new HashMap<>();

        @Override public void put(String key, byte[] value) { }
        @Override public byte[] get(String key) { return null; }
        @Override public synchronized void appendToLog(String logName, byte[] data) {
            logs.computeIfAbsent(logName, k -> new ArrayList<>()).add(data.clone());
        }
        @Override public synchronized List<byte[]> readLog(String logName) {
            List<byte[]> l = logs.get(logName);
            if (l == null) {
                return List.of();
            }
            List<byte[]> copy = new ArrayList<>(l.size());
            for (byte[] f : l) {
                copy.add(f.clone());
            }
            return copy;
        }
        @Override public synchronized void truncateLog(String logName) { logs.remove(logName); }
        @Override public synchronized void renameLog(String from, String to) {
            List<byte[]> l = logs.remove(from);
            if (l != null) {
                logs.put(to, l);
            }
        }
        @Override public void sync() { }

        synchronized byte[] frame(int i) { return logs.get(AuditLog.LOG_NAME).get(i); }
        synchronized void insertFrame(int at, byte[] frame) {
            logs.get(AuditLog.LOG_NAME).add(at, frame);
        }
        synchronized void flipByte(int frameIndex, int off) {
            logs.get(AuditLog.LOG_NAME).get(frameIndex)[off] ^= 0x01;
        }
        synchronized void setByte(int frameIndex, int off, byte v) {
            logs.get(AuditLog.LOG_NAME).get(frameIndex)[off] = v;
        }
    }
}
