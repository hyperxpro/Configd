package io.configd.api;

import io.configd.common.Clock;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tamper-evident, append-only {@link AuditLog}.
 * <p>
 * Per the prime directive (section 2.1) a control is verified ONLY by a passing
 * negative test that performs the attack and asserts rejection. The
 * tamper-evidence tests below mutate / drop / reorder a persisted record and
 * assert that {@link AuditLog#verifyPersisted()} reports the break - and a
 * non-vacuity test asserts a clean chain verifies true.
 * <p>
 * The KEYED-vs-keyless distinction (the threat-model A2 bar) is covered by
 * {@code keyedChainDefeatsAttackerWhoRechainsTheWholeLogWithoutTheKey}: an
 * attacker who edits the file AND re-chains the whole log without {@code K_audit}
 * defeats a keyless SHA-256 chain but is rejected by the keyed HMAC chain - and
 * the test proves the keyless function would have passed the same bytes.
 */
final class AuditLogTest {

    private static Clock fixedClock(AtomicLong millis) {
        return new Clock() {
            @Override public long currentTimeMillis() { return millis.get(); }
            @Override public long nanoTime() { return millis.get() * 1_000_000L; }
        };
    }

    // ------------------------------------------------------------------
    // (a) Completeness: each event produces exactly one correct record.
    // ------------------------------------------------------------------

    @Test
    void everyEventProducesExactlyOneCorrectRecord() {
        AtomicLong now = new AtomicLong(1_000L);
        Storage storage = Storage.inMemory();
        AuditLog log = new AuditLog(storage, fixedClock(now));

        log.record("writer", "PUT", "app/feature", "committed seq=7");
        now.addAndGet(1);
        log.record("writer", "DELETE", "app/feature", "committed seq=8");
        now.addAndGet(1);
        log.record("reader", "PUT", "app/feature", "denied: insufficient permissions");
        now.addAndGet(1);
        log.record("-", "PUT", "app/feature", "unauthenticated"); // no principal

        List<AuditLog.Record> records = log.records();
        assertEquals(4, records.size(), "four events -> exactly four records");

        assertEquals("PUT", records.get(0).action());
        assertEquals("committed seq=7", records.get(0).outcome());
        assertEquals("DELETE", records.get(1).action());
        assertEquals("reader", records.get(2).actor());
        assertTrue(records.get(2).outcome().startsWith("denied"));
        assertEquals("-", records.get(3).actor(), "unauthenticated attempt is recorded with actor '-'");

        // Persisted store has exactly the same four framed records.
        assertEquals(4, storage.readLog(AuditLog.LOG_NAME).size());
        assertTrue(log.verify().valid(), "the freshly-built chain must verify clean");
        assertTrue(log.verifyPersisted().valid(), "the persisted chain must verify clean");
    }

    @Test
    void noCredentialIsEverWrittenToTheRecord() {
        AtomicLong now = new AtomicLong(1_000L);
        AuditLog log = new AuditLog(Storage.inMemory(), fixedClock(now));
        // The caller passes the PRINCIPAL, never the token. We assert the API
        // shape encourages that: a record holds actor/action/target/outcome only.
        AuditLog.Record r = log.record("writer", "PUT", "app/db/password", "committed seq=1");
        assertEquals("writer", r.actor());
        assertEquals("app/db/password", r.target());
        // The bearer token "s3cr3t-token" is never an argument and cannot appear.
        assertFalse(r.toString().contains("s3cr3t-token"));
    }

    // ------------------------------------------------------------------
    // (b) Tamper-evidence: mutate / drop / reorder -> verify() is false.
    // ------------------------------------------------------------------

    @Test
    void flippingAByteInAPersistedRecordIsDetected() {
        AtomicLong now = new AtomicLong(1_000L);
        // A mutable in-memory storage we can reach into to flip a byte.
        TamperableStorage storage = new TamperableStorage();
        AuditLog log = new AuditLog(storage, fixedClock(now));
        log.record("writer", "PUT", "app/a", "committed seq=1");
        now.addAndGet(1);
        log.record("writer", "PUT", "app/b", "committed seq=2");
        now.addAndGet(1);
        log.record("writer", "PUT", "app/c", "committed seq=3");

        assertTrue(log.verifyPersisted().valid(), "precondition: untampered chain verifies");

        // Attack: flip one byte inside the canonical block of the middle record
        // (offset 20 lands inside the field region, past the 8-byte canonical
        // length prefix + 8-byte timestamp).
        storage.flipByte(1, 20);

        AuditLog.VerifyResult result = log.verifyPersisted();
        assertFalse(result.valid(), "a flipped byte in a persisted record must be detected");
        assertEquals(1, result.brokenIndex(), "verify() must pinpoint the tampered record index");
    }

    @Test
    void droppingARecordBreaksTheChain() {
        AtomicLong now = new AtomicLong(1_000L);
        TamperableStorage storage = new TamperableStorage();
        AuditLog log = new AuditLog(storage, fixedClock(now));
        log.record("writer", "PUT", "app/a", "committed seq=1");
        now.addAndGet(1);
        log.record("writer", "PUT", "app/b", "committed seq=2");
        now.addAndGet(1);
        log.record("writer", "PUT", "app/c", "committed seq=3");

        // Attack: delete the middle record from the persisted log.
        storage.dropFrame(1);

        AuditLog.VerifyResult result = log.verifyPersisted();
        assertFalse(result.valid(), "a dropped record must break prevHash linkage");
        // After dropping index 1, the old index 2 is now at index 1 and its
        // prevHash points at the deleted record's hash -> linkage break at 1.
        assertEquals(1, result.brokenIndex());
    }

    @Test
    void reorderingTwoRecordsBreaksTheChain() {
        AtomicLong now = new AtomicLong(1_000L);
        TamperableStorage storage = new TamperableStorage();
        AuditLog log = new AuditLog(storage, fixedClock(now));
        log.record("writer", "PUT", "app/a", "committed seq=1");
        now.addAndGet(1);
        log.record("writer", "PUT", "app/b", "committed seq=2");
        now.addAndGet(1);
        log.record("writer", "PUT", "app/c", "committed seq=3");

        // Attack: swap records at indices 1 and 2.
        storage.swapFrames(1, 2);

        assertFalse(log.verifyPersisted().valid(), "reordering records must be detected");
    }

    // ------------------------------------------------------------------
    // (c) Non-vacuity: a clean chain over many records verifies true.
    // ------------------------------------------------------------------

    @Test
    void cleanChainVerifiesTrue() {
        AtomicLong now = new AtomicLong(1_000L);
        AuditLog log = new AuditLog(Storage.inMemory(), fixedClock(now));
        for (int i = 0; i < 50; i++) {
            log.record("writer", "PUT", "app/k" + i, "committed seq=" + i);
            now.addAndGet(1);
        }
        AuditLog.VerifyResult r = log.verify();
        assertTrue(r.valid(), "a 50-record clean chain must verify true");
        assertEquals(-1, r.brokenIndex());
        assertTrue(log.verifyPersisted().valid());
    }

    @Test
    void changingTheActorChangesTheRecordHash() {
        // The hash is a function of the content; two records that differ only in
        // actor must have different recordHashes (so an actor swap is detectable).
        byte[] a = AuditLog.canonicalBytes(1L, "alice", "PUT", "app/x", "committed seq=1");
        byte[] b = AuditLog.canonicalBytes(1L, "bob", "PUT", "app/x", "committed seq=1");
        assertNotEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(b),
                "canonical bytes must differ when the actor differs");
    }

    // ------------------------------------------------------------------
    // Bounding: the in-memory chain never exceeds the cap (anti-DoS).
    // ------------------------------------------------------------------

    @Test
    void inMemoryChainIsBoundedByMaxRecords() {
        AtomicLong now = new AtomicLong(1_000L);
        Storage storage = Storage.inMemory();
        AuditLog log = new AuditLog(storage, fixedClock(now), 10); // tiny cap
        for (int i = 0; i < 100; i++) {
            log.record("writer", "PUT", "app/k" + i, "committed seq=" + i);
            now.addAndGet(1);
        }
        assertTrue(log.size() <= 10, "the in-memory chain must not exceed the cap: " + log.size());
        // The retained segment (anchored at its head's prevHash) still verifies.
        assertTrue(log.verify().valid(), "the retained segment must still verify after rotation");
        assertTrue(log.verifyPersisted().valid(),
                "the rotated on-disk log must verify (re-seeded from the retained head)");
        assertTrue(storage.readLog(AuditLog.LOG_NAME).size() <= 10,
                "the on-disk log must be bounded too");
    }

    // ------------------------------------------------------------------
    // (d) THE keyed-vs-keyless distinction. The A2 attacker
    // can EDIT the file AND re-chain the WHOLE persisted log. A keyless SHA-256
    // chain is defeated (they recompute every hash - no secret needed). A KEYED
    // HMAC chain is NOT: re-chaining without K_audit yields MACs the real key
    // rejects. This test performs the full re-chain attack and proves the gap.
    // ------------------------------------------------------------------

    @Test
    void keyedChainDefeatsAttackerWhoRechainsTheWholeLogWithoutTheKey() {
        AtomicLong now = new AtomicLong(1_000L);
        javax.crypto.SecretKey kAudit = hmacKey("the-real-audit-key-not-known-to-attacker");

        // 1) A genuine KEYED audit log records three events.
        TamperableStorage victim = new TamperableStorage();
        AuditLog keyed = new AuditLog(victim, fixedClock(now), kAudit);
        keyed.record("writer", "PUT", "app/a", "committed seq=1");
        now.addAndGet(1);
        keyed.record("writer", "DELETE", "app/secret", "committed seq=2");
        now.addAndGet(1);
        keyed.record("writer", "PUT", "app/c", "committed seq=3");
        assertTrue(keyed.verifyPersisted().valid(), "precondition: the genuine keyed chain verifies");

        // 2) The A2 attacker rewrites history: they change the middle record's
        //    outcome (hiding that a secret delete happened) and RE-CHAIN the whole
        //    log. They have no key, so they re-chain with plain SHA-256 (keyless) -
        //    the strongest a keyless attacker can do. Model this by replaying the
        //    forged event sequence through a fresh KEYLESS AuditLog and copying its
        //    (fully self-consistent, keyless) frames over the victim's log.
        AtomicLong forgeClock = new AtomicLong(1_000L);
        TamperableStorage forgeStore = new TamperableStorage();
        AuditLog attackerKeyless = new AuditLog(forgeStore, fixedClock(forgeClock));
        attackerKeyless.record("writer", "PUT", "app/a", "committed seq=1");
        forgeClock.addAndGet(1);
        attackerKeyless.record("writer", "DELETE", "app/secret", "DENIED nothing happened"); // forged
        forgeClock.addAndGet(1);
        attackerKeyless.record("writer", "PUT", "app/c", "committed seq=3");
        // Overwrite the victim's persisted bytes with the attacker's re-chained log.
        victim.replaceAllFrames(forgeStore.readLog(AuditLog.LOG_NAME));

        // 3) Verifying the rewritten bytes under the REAL key DETECTS the forgery.
        AuditLog.VerifyResult underRealKey = keyed.verifyPersisted();
        assertFalse(underRealKey.valid(),
                "a keyed chain must reject an attacker re-chain done without K_audit");
        assertEquals(0, underRealKey.brokenIndex(),
                "the very first record's MAC fails under the real key (attacker used SHA-256)");

        // 4) PROVE THE GAP: the SAME attacker bytes verify TRUE under the keyless
        //    function - i.e. a keyless AuditLog would have been fully defeated.
        //    verifyPersistedWith(null) re-walks the persisted bytes keyless.
        assertTrue(keyed.verifyPersistedWith(null).valid(),
                "the attacker's re-chained log is self-consistent under keyless SHA-256 — "
                        + "this is exactly the gap the HMAC key closes");
    }

    @Test
    void verifyingAKeyedLogUnderAWrongKeyFails() {
        // A wrong key must not verify (constant-time MAC compare).
        AtomicLong now = new AtomicLong(1_000L);
        TamperableStorage storage = new TamperableStorage();
        AuditLog keyed = new AuditLog(storage, fixedClock(now), hmacKey("right-key"));
        keyed.record("writer", "PUT", "app/a", "committed seq=1");
        keyed.record("writer", "PUT", "app/b", "committed seq=2");
        assertTrue(keyed.verifyPersisted().valid(), "the real key verifies");
        assertFalse(keyed.verifyPersistedWith(hmacKey("wrong-key")).valid(),
                "a wrong HMAC key must fail verification");
        assertFalse(keyed.verifyPersistedWith(null).valid(),
                "keyless verification of a keyed chain must fail");
    }

    @Test
    void keyedCleanChainAndTamperBehaveLikeKeylessForOrdinaryAttacks() {
        // The keyed mode must still catch the ordinary in-place tamper (sanity that
        // the HMAC path didn't regress the basic property).
        AtomicLong now = new AtomicLong(1_000L);
        TamperableStorage storage = new TamperableStorage();
        AuditLog keyed = new AuditLog(storage, fixedClock(now), hmacKey("k"));
        keyed.record("writer", "PUT", "app/a", "committed seq=1");
        now.addAndGet(1);
        keyed.record("writer", "PUT", "app/b", "committed seq=2");
        assertTrue(keyed.verifyPersisted().valid());
        storage.flipByte(0, 20); // flip a byte in the first record's canonical block
        AuditLog.VerifyResult r = keyed.verifyPersisted();
        assertFalse(r.valid(), "keyed mode must still catch a naive in-place edit");
        assertEquals(0, r.brokenIndex());
    }

    private static javax.crypto.SecretKey hmacKey(String material) {
        return new javax.crypto.spec.SecretKeySpec(
                material.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
    }

    // ------------------------------------------------------------------
    // A storage that lets a test tamper the persisted frames directly.
    // ------------------------------------------------------------------

    private static final class TamperableStorage implements Storage {
        private final java.util.Map<String, java.util.List<byte[]>> logs = new java.util.HashMap<>();
        private final java.util.Map<String, byte[]> kv = new java.util.HashMap<>();

        @Override public void put(String key, byte[] value) { kv.put(key, value.clone()); }
        @Override public byte[] get(String key) { byte[] v = kv.get(key); return v == null ? null : v.clone(); }
        @Override public synchronized void appendToLog(String logName, byte[] data) {
            logs.computeIfAbsent(logName, k -> new java.util.ArrayList<>()).add(data.clone());
        }
        @Override public synchronized java.util.List<byte[]> readLog(String logName) {
            java.util.List<byte[]> l = logs.get(logName);
            if (l == null) return java.util.List.of();
            java.util.List<byte[]> copy = new java.util.ArrayList<>(l.size());
            for (byte[] b : l) copy.add(b.clone());
            return copy;
        }
        @Override public synchronized void truncateLog(String logName) { logs.remove(logName); }
        @Override public synchronized void renameLog(String from, String to) {
            java.util.List<byte[]> l = logs.remove(from);
            if (l != null) logs.put(to, l);
        }
        @Override public void sync() { }

        synchronized void flipByte(int frameIndex, int byteOffset) {
            byte[] frame = logs.get(AuditLog.LOG_NAME).get(frameIndex);
            frame[byteOffset] ^= 0x01;
        }
        synchronized void dropFrame(int frameIndex) {
            logs.get(AuditLog.LOG_NAME).remove(frameIndex);
        }
        synchronized void swapFrames(int i, int j) {
            java.util.List<byte[]> l = logs.get(AuditLog.LOG_NAME);
            byte[] tmp = l.get(i);
            l.set(i, l.get(j));
            l.set(j, tmp);
        }
        /** A2 attacker: replace the ENTIRE persisted log with a re-chained one. */
        synchronized void replaceAllFrames(java.util.List<byte[]> frames) {
            java.util.List<byte[]> l = new java.util.ArrayList<>(frames.size());
            for (byte[] f : frames) l.add(f.clone());
            logs.put(AuditLog.LOG_NAME, l);
        }
    }
}
