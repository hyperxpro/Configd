package io.configd.api;

import io.configd.common.Clock;
import io.configd.common.Storage;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Tamper-evident, append-only audit log of security-relevant control-plane events.
 * <p>
 * Every record is one of: a mutating attempt (PUT/DELETE) or an authentication/
 * authorization failure (401/403). Each record carries the fields
 * {@code timestampMs}, {@code actor} (the authenticated principal, or {@code "-"}
 * when unauthenticated), {@code action}, {@code target} key, and {@code outcome}
 * (e.g. {@code committed seq=N} / a deny reason / {@code failed}).
 *
 * <h2>Tamper-evidence (KEYED HMAC chain)</h2>
 * Each record carries a self-versioning header {@code [AUDIT_MAGIC:4][recordVersion:1]} and is
 * linked to its predecessor by a chain MAC over
 * {@code AUDIT_MAGIC || recordVersion || prevHash || canonicalBytes(record)}, with the genesis
 * {@code prevHash} being 32 zero bytes. Binding the header into the MAC means a version edit of a
 * record breaks the chain, not just a payload edit. {@link #verify()} re-walks the chain, recomputes
 * each MAC, and (with a constant-time {@link MessageDigest#isEqual compare}) detects any insertion,
 * deletion, reorder, or in-place mutation.
 * <p>
 * There are two modes:
 * <ul>
 *   <li><b>Keyed</b> ({@link #AuditLog(Storage, Clock, SecretKey)}): the chain is an
 *       <em>HMAC-SHA256</em> under {@code K_audit}:
 *       <pre>recordHash = HMAC-SHA256(K_audit, AUDIT_MAGIC || recordVersion || prevHash || canonicalBytes(record))</pre>
 *       This is the production-grade, tamper-EVIDENT mode. An attacker
 *       who can rewrite the persisted file cannot forge a consistent
 *       chain without {@code K_audit}: editing a record and re-chaining the whole
 *       log with plain SHA-256 / a wrong key yields MACs that {@link #verify()}
 *       under the real key rejects. {@code K_audit} is HKDF-derived from the cluster
 *       signing key, DOMAIN-SEPARATED by a distinct {@code info} string so it is
 *       independent of the Raft at-rest key.</li>
 *   <li><b>Keyless</b> ({@link #AuditLog(Storage, Clock)}): a plain <em>SHA-256</em>
 *       chain. This is evidence against CARELESS / accidental edits ONLY - a
 *       deliberate attacker with file-write access can recompute the whole keyless
 *       chain (SHA-256 needs no secret) and defeat it. Retained for unit tests and
 *       in-memory / back-compat deployments; NOT suitable for production security.</li>
 * </ul>
 * The on-disk frame additionally carries a CRC32C (via {@link Storage#appendToLog})
 * so accidental corruption is caught at read time. The canonical form is
 * length-prefixed per field (see {@link #canonicalBytes}) so no field-boundary
 * ambiguity can be exploited to forge a colliding record.
 * <p>
 * <b>Residual (honest):</b> even keyed, an attacker who holds {@code K_audit}
 * (full-host compromise, since the key is derived from the co-located cluster
 * signing key) can forge the chain.
 *
 * <h2>Bounding (anti-DoS)</h2>
 * The in-memory chain is bounded to {@code maxRecords} (default
 * {@link #DEFAULT_MAX_RECORDS}); when the cap is reached the on-disk log is
 * rotated (truncated and re-seeded from the most recent record's hash) so the
 * audit log cannot grow without bound and become its own memory/disk exhaustion
 * lever. Rotation preserves the chain's forward integrity: the new segment's
 * genesis {@code prevHash} is the last retained {@code recordHash}, so
 * {@link #verify()} over the retained segment still holds. (Cross-segment
 * continuity across a rotation is out of scope; within a segment the chain is
 * fully verifiable.)
 *
 * <h2>Secrets</h2>
 * NEVER logs a credential/token - only the resolved principal. Callers must pass
 * the principal, never the bearer token.
 *
 * <h2>Thread safety</h2>
 * All mutating methods are synchronized on this instance; {@link #verify()} and
 * {@link #records()} take a consistent snapshot under the same lock.
 */
public final class AuditLog {

    /** Default cap on the in-memory chain before the on-disk log rotates. */
    public static final int DEFAULT_MAX_RECORDS = 100_000;

    /** Storage log name for the persisted audit records. */
    public static final String LOG_NAME = "security-audit";

    /**
     * Self-versioning record-header magic (ASCII "RAUD"), prepended inside each FileStorage
     * frame. Mirrors the {@code RaftArtifactMagic} registry entry (that registry cannot be
     * referenced from this module - it is package-private in configd-consensus-core).
     */
    private static final int AUDIT_MAGIC = 0x5241_5544;

    /**
     * Audit record format version. It is bound into the chain MAC (see {@link #chainHash}), so a
     * version edit of a persisted record breaks the chain rather than merely being a payload edit.
     */
    private static final int RECORD_VERSION = 1;

    private static final byte[] GENESIS_PREV_HASH = new byte[32];

    /**
     * A canonical, recorded security event. {@code recordVersion} is the self-versioning record
     * format (bound into the chain MAC); {@code prevHash}/{@code recordHash} are 32-byte chain MACs
     * (HMAC-SHA256 keyed, SHA-256 keyless).
     */
    public record Record(long timestampMs,
                         String actor,
                         String action,
                         String target,
                         String outcome,
                         int recordVersion,
                         byte[] prevHash,
                         byte[] recordHash) {
        public Record {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(prevHash, "prevHash");
            Objects.requireNonNull(recordHash, "recordHash");
        }
    }

    private final Storage storage;
    private final Clock clock;
    private final int maxRecords;
    // The chain MAC key. Non-null => keyed HMAC-SHA256 mode; null => keyless SHA-256.
    private final SecretKey key;

    // The in-memory chain (append order). Bounded to maxRecords; rotation drops
    // the oldest entries and re-seeds the on-disk log from the surviving head.
    private final Deque<Record> chain = new ArrayDeque<>();
    private byte[] lastHash = GENESIS_PREV_HASH;

    /**
     * Creates a KEYLESS audit log (plain SHA-256 chain) with the default record
     * cap. Evidence against careless edits only - see the class javadoc; use the
     * {@link #AuditLog(Storage, Clock, SecretKey)} keyed ctor for tamper-evidence
     * against an attacker with file-write access.
     *
     * @param storage durable, append+CRC storage (non-null)
     * @param clock   time source (non-null)
     */
    public AuditLog(Storage storage, Clock clock) {
        this(storage, clock, DEFAULT_MAX_RECORDS, null);
    }

    /**
     * Creates a KEYLESS audit log with an explicit record cap.
     *
     * @param storage    durable, append+CRC storage (non-null)
     * @param clock      time source (non-null)
     * @param maxRecords cap on the in-memory chain before rotation (&gt; 0)
     */
    public AuditLog(Storage storage, Clock clock, int maxRecords) {
        this(storage, clock, maxRecords, null);
    }

    /**
     * Creates a KEYED audit log (HMAC-SHA256 chain under {@code key}) with the
     * default record cap. This is the production, tamper-EVIDENT mode: an attacker
     * who can rewrite the persisted file cannot forge a consistent chain without
     * {@code key}. {@code key} must be derived DOMAIN-SEPARATED from the Raft at-rest
     * key (a distinct HKDF {@code info} string).
     *
     * @param storage durable, append+CRC storage (non-null)
     * @param clock   time source (non-null)
     * @param key     the HMAC-SHA256 chain key {@code K_audit} (non-null)
     */
    public AuditLog(Storage storage, Clock clock, SecretKey key) {
        this(storage, clock, DEFAULT_MAX_RECORDS, Objects.requireNonNull(key, "key"));
    }

    /**
     * Creates an audit log with an explicit record cap and an optional MAC key.
     *
     * @param storage    durable, append+CRC storage (non-null)
     * @param clock      time source (non-null)
     * @param maxRecords cap on the in-memory chain before rotation (&gt; 0)
     * @param key        the HMAC-SHA256 chain key (non-null = keyed; null = keyless SHA-256)
     */
    public AuditLog(Storage storage, Clock clock, int maxRecords, SecretKey key) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("maxRecords must be positive: " + maxRecords);
        }
        this.maxRecords = maxRecords;
        this.key = key; // nullable
    }

    /**
     * Records one security event and returns the persisted record. The
     * {@code timestampMs} is taken from the clock; the record is linked to the
     * chain head and appended durably before returning.
     *
     * @param actor   the principal, or {@code "-"} if unauthenticated (non-null)
     * @param action  the attempted action, e.g. {@code PUT}/{@code DELETE} (non-null)
     * @param target  the target config key (non-null)
     * @param outcome the outcome, e.g. {@code committed seq=N} / a deny reason (non-null)
     * @return the appended record
     */
    public synchronized Record record(String actor, String action, String target, String outcome) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(outcome, "outcome");

        long ts = clock.currentTimeMillis();
        byte[] canonical = canonicalBytes(ts, actor, action, target, outcome);
        byte[] recordHash = chainHash(key, RECORD_VERSION, lastHash, canonical);
        Record rec = new Record(ts, actor, action, target, outcome,
                RECORD_VERSION, lastHash.clone(), recordHash);

        // Persist the FULL framed record (fields + prevHash + recordHash) so a
        // reader can reconstruct and re-verify the chain independently.
        storage.appendToLog(LOG_NAME, encode(rec));
        storage.sync();

        chain.addLast(rec);
        lastHash = recordHash;

        if (chain.size() > maxRecords) {
            rotate();
        }
        return rec;
    }

    /**
     * Rotates the on-disk log when the in-memory cap is exceeded: keep the most
     * recent {@code maxRecords} records, truncate the persisted log, and re-write
     * the retained tail (whose chain is intact - the retained head's
     * {@code prevHash} links to a record we just dropped, so verification of the
     * retained segment is anchored at that head, not at the all-zero genesis).
     */
    private void rotate() {
        while (chain.size() > maxRecords) {
            chain.pollFirst();
        }
        storage.truncateLog(LOG_NAME);
        for (Record r : chain) {
            storage.appendToLog(LOG_NAME, encode(r));
        }
        storage.sync();
    }

    /**
     * Re-walks the in-memory chain, recomputing each record's hash from its
     * predecessor's hash and its canonical bytes, and checking linkage. Detects
     * any in-place field mutation, dropped record, or reorder.
     *
     * @return a {@link VerifyResult} that is {@link VerifyResult#ok()} on a clean
     *         chain, or pinpoints the first broken record otherwise
     */
    public synchronized VerifyResult verify() {
        return verifyRecords(new ArrayList<>(chain), key);
    }

    /**
     * Re-reads the persisted log from {@link Storage} and verifies it, so a
     * tamper of the on-disk bytes (a flipped/dropped/reordered frame) is caught
     * independently of the in-memory chain. (A frame whose CRC was broken by the
     * tamper surfaces as a {@link Storage}-level read failure before this runs.)
     *
     * @return the verification result over the persisted records
     */
    public synchronized VerifyResult verifyPersisted() {
        return verifyPersistedWith(key);
    }

    /**
     * Verifies the persisted log under an ARBITRARY key (or keyless when null),
     * independent of this instance's key. Test seam for the keyed-vs-keyless
     * distinction: re-walk the attacker-rewritten bytes under the REAL key
     * (must be broken) versus the keyless function (would have passed).
     *
     * @param verifyKey the key to verify under, or null for keyless SHA-256
     * @return the verification result over the persisted records
     */
    public synchronized VerifyResult verifyPersistedWith(SecretKey verifyKey) {
        List<byte[]> frames = storage.readLog(LOG_NAME);
        List<Record> persisted = new ArrayList<>(frames.size());
        for (byte[] frame : frames) {
            persisted.add(decode(frame));
        }
        return verifyRecords(persisted, verifyKey);
    }

    /**
     * Re-reads and decodes the persisted audit records (in append order), independent of this
     * instance's in-memory chain. Used by the node-anchor boot cross-check to confirm the replayed
     * chain still reaches the anchored head {@code (auditRecordCount, auditHeadHash)}: if the
     * anchored head's {@code recordHash} is absent from the persisted records, the on-disk log was
     * truncated below the last anchored head - a detected tamper the caller REFUSEs.
     *
     * <p>Decode-only (structural); {@link #verifyPersisted()} is the separate chain-linkage check. A
     * frame whose CRC was broken surfaces as a {@link Storage} read failure before this runs.
     *
     * @return the decoded persisted records in append order (empty if the log is empty)
     */
    public synchronized List<Record> persistedRecords() {
        List<byte[]> frames = storage.readLog(LOG_NAME);
        List<Record> persisted = new ArrayList<>(frames.size());
        for (byte[] frame : frames) {
            persisted.add(decode(frame));
        }
        return persisted;
    }

    private static VerifyResult verifyRecords(List<Record> records, SecretKey verifyKey) {
        // An empty chain verifies vacuously true.
        byte[] expectedPrev = null;
        for (int i = 0; i < records.size(); i++) {
            Record r = records.get(i);
            // The very first retained record may be anchored at a non-genesis
            // prevHash after a rotation; accept its declared prevHash as the
            // segment anchor, then enforce strict linkage from there on.
            if (i == 0) {
                expectedPrev = r.prevHash();
            } else if (!MessageDigest.isEqual(expectedPrev, r.prevHash())) {
                return VerifyResult.broken(i,
                        "prevHash linkage broken: record " + i + " does not chain to record " + (i - 1));
            }
            byte[] canonical = canonicalBytes(r.timestampMs(), r.actor(), r.action(), r.target(), r.outcome());
            // Recompute with the record's DECLARED version so a version edit (not just a payload
            // edit) is caught here: the persisted version rides into the MAC input, so flipping it
            // yields a hash that no longer matches the stored recordHash.
            byte[] recomputed = chainHash(verifyKey, r.recordVersion(), r.prevHash(), canonical);
            // Constant-time compare: a keyed MAC verify must not leak via timing.
            if (!MessageDigest.isEqual(recomputed, r.recordHash())) {
                return VerifyResult.broken(i,
                        "recordHash MAC mismatch at record " + i
                                + " (field tampered or wrong/absent key)");
            }
            expectedPrev = r.recordHash();
        }
        return VerifyResult.ok();
    }

    /** A read-only snapshot of the in-memory chain in append order. */
    public synchronized List<Record> records() {
        return new ArrayList<>(chain);
    }

    /** The number of records currently retained in memory. */
    public synchronized int size() {
        return chain.size();
    }

    /** Result of a chain verification: clean, or the index + reason of the first break. */
    public record VerifyResult(boolean valid, int brokenIndex, String reason) {
        static VerifyResult ok() {
            return new VerifyResult(true, -1, null);
        }
        static VerifyResult broken(int index, String reason) {
            return new VerifyResult(false, index, reason);
        }
    }

    // Canonical encoding + hashing

    /**
     * Canonical, unambiguous record bytes: a fixed field order, each
     * variable-length field length-prefixed (4-byte big-endian length + UTF-8
     * bytes) and the timestamp as 8 big-endian bytes. Length-prefixing prevents a
     * field-boundary-shift forgery (e.g. moving bytes between {@code action} and
     * {@code target} cannot collide).
     */
    static byte[] canonicalBytes(long timestampMs, String actor, String action, String target, String outcome) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeLong(out, timestampMs);
        writeField(out, actor);
        writeField(out, action);
        writeField(out, target);
        writeField(out, outcome);
        return out.toByteArray();
    }

    /**
     * The chain MAC over {@code AUDIT_MAGIC || recordVersion || prevHash || canonical}. Keyed mode
     * ({@code key != null}) computes HMAC-SHA256 under {@code key}; keyless mode computes a plain
     * SHA-256 digest. Both yield 32 bytes, so the record format and chain logic are identical across
     * modes. Binding the magic+version into the input means a version downgrade (or any header edit)
     * of a persisted record breaks the chain, not just a payload edit.
     */
    private static byte[] chainHash(SecretKey key, int recordVersion, byte[] prevHash, byte[] canonical) {
        byte[] header = recordHeaderBytes(recordVersion); // [AUDIT_MAGIC:4][recordVersion:1]
        if (key != null) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(key);
                mac.update(header);
                mac.update(prevHash);
                mac.update(canonical);
                return mac.doFinal();
            } catch (GeneralSecurityException e) {
                // HmacSHA256 is mandated by the JCA spec on every conformant JRE.
                throw new IllegalStateException("HmacSHA256 unavailable", e);
            }
        }
        MessageDigest digest = sha256();
        digest.update(header);
        digest.update(prevHash);
        digest.update(canonical);
        return digest.digest();
    }

    /** The self-versioning record header {@code [AUDIT_MAGIC:4][recordVersion:1]} (chain-MAC input + on-disk prefix). */
    private static byte[] recordHeaderBytes(int recordVersion) {
        return new byte[] {
                (byte) (AUDIT_MAGIC >>> 24), (byte) (AUDIT_MAGIC >>> 16),
                (byte) (AUDIT_MAGIC >>> 8), (byte) AUDIT_MAGIC,
                (byte) recordVersion
        };
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every JRE (JCA spec); absence is unrecoverable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * On-disk frame: the self-versioning record header {@code [AUDIT_MAGIC:4][recordVersion:1]},
     * then the canonical fields followed by the two 32-byte hashes, with the canonical block itself
     * length-prefixed so the reader can split it from the trailing hashes. {@link Storage#appendToLog}
     * wraps this in its own {@code [len][data][CRC32C]} frame.
     */
    private static byte[] encode(Record r) {
        byte[] canonical = canonicalBytes(r.timestampMs(), r.actor(), r.action(), r.target(), r.outcome());
        ByteArrayOutputStream out = new ByteArrayOutputStream(canonical.length + 72 + 5);
        writeInt(out, AUDIT_MAGIC);       // 4-byte record-header magic
        out.write(r.recordVersion());     // 1-byte record version
        writeLong(out, canonical.length); // 8-byte length prefix for the canonical block
        out.writeBytes(canonical);
        out.writeBytes(r.prevHash());     // 32 bytes
        out.writeBytes(r.recordHash());   // 32 bytes
        return out.toByteArray();
    }

    private static Record decode(byte[] frame) {
        int pos = 0;
        int magic = readInt(frame, pos);
        pos += 4;
        if (magic != AUDIT_MAGIC) {
            // Not an audit record (foreign frame or corrupt magic) - refuse rather than parse it as
            // one. A deliberate magic edit is additionally caught by the chain, since the magic is a
            // MAC-input constant; this structural check gives the clearer error for a non-record frame.
            throw new IllegalStateException("corrupt audit frame: bad record magic 0x" + Integer.toHexString(magic));
        }
        int recordVersion = frame[pos] & 0xFF;
        pos += 1;
        long canonicalLen = readLong(frame, pos);
        pos += 8;
        if (canonicalLen < 0 || canonicalLen > frame.length - pos - 64) {
            throw new IllegalStateException("corrupt audit frame: bad canonical length " + canonicalLen);
        }
        int cLen = (int) canonicalLen;
        int cStart = pos;
        // Parse the canonical block back into fields.
        long ts = readLong(frame, pos);
        pos += 8;
        String actor = readField(frame, pos);
        pos += 4 + utf8Len(actor);
        String action = readField(frame, pos);
        pos += 4 + utf8Len(action);
        String target = readField(frame, pos);
        pos += 4 + utf8Len(target);
        String outcome = readField(frame, pos);
        pos += 4 + utf8Len(outcome);
        if (pos != cStart + cLen) {
            throw new IllegalStateException("corrupt audit frame: canonical block under/overrun");
        }
        byte[] prevHash = Arrays.copyOfRange(frame, pos, pos + 32);
        pos += 32;
        byte[] recordHash = Arrays.copyOfRange(frame, pos, pos + 32);
        return new Record(ts, actor, action, target, outcome, recordVersion, prevHash, recordHash);
    }

    private static void writeField(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeInt(out, b.length);
        out.writeBytes(b);
    }

    private static String readField(byte[] buf, int pos) {
        int len = readInt(buf, pos);
        if (len < 0 || pos + 4 + len > buf.length) {
            throw new IllegalStateException("corrupt audit frame: bad field length " + len);
        }
        return new String(buf, pos + 4, len, StandardCharsets.UTF_8);
    }

    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((v >>> shift) & 0xFF));
        }
    }

    private static int readInt(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 24)
                | ((buf[pos + 1] & 0xFF) << 16)
                | ((buf[pos + 2] & 0xFF) << 8)
                | (buf[pos + 3] & 0xFF);
    }

    private static long readLong(byte[] buf, int pos) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (buf[pos + i] & 0xFF);
        }
        return v;
    }
}
