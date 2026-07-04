package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.configd.raft.RaftArtifactMagic.KEYRING_MAGIC;

/**
 * The {@code raft-keyring} body codec + rotation primitives (frozen-format §2.6 / §A2).
 *
 * <p>Roots are INDEPENDENT random 32-byte secrets (NOT {@code HKDF(signing key)}), wrapped per-term
 * and retained forever; rotation APPENDS a term and never re-encrypts old data. The whole body rides
 * an outer HMAC {@link IntegrityEnvelope} under {@code K_keyringMac} (strip/swap/add/truncate of
 * entries fails the outer MAC). Each local entry's root is AES-256-GCM-wrapped under {@code KEK_wrap}
 * with an AAD that binds {@code (KEYRING_MAGIC, keyringFormatVersion, term, wrapAlgId, nodeKeyId,
 * "root")}, so a wrapped root cannot be replayed into a different term slot or a different node.
 *
 * <pre>
 *   KEYRING_BODY:
 *     [keyringFormatVersion:2 = 1][keyringSeq:8][activeTerm:4][entryCount:4]
 *     entry * entryCount:
 *       [term:4][wrapAlgId:1][nonceLen:1][nonce:nonceLen][wrappedLen:4][wrappedRoot:wrappedLen]
 * </pre>
 *
 * <p>This is the pure body/rotation layer; the physical dual-slot placement (crash-atomic
 * signing-key handover) lives in {@link KeyringFile}, which reuses the proven {@link AnchorIO}
 * transport. Roots decouple from the signing key: rotating the signing key only rewraps the keyring
 * ({@link #rewrapUnderNewKek}), so every retained {@code root[term]} is unchanged and all prior
 * data still decrypts/verifies. This is what makes the documented data-destroying rotation
 * impossible by construction.
 *
 * <p><b>Reserved-value discipline (fail-closed):</b> {@code keyringFormatVersion == 0} or {@code != 1}
 * throws; a keyring entry {@code term == 0} throws (term 0 is the signing-key domain, illegal here);
 * an unknown {@code wrapAlgId} throws; a length field that overruns the body throws. Nothing is ever
 * best-effort parsed.
 */
final class KeyringCodec {

    /** Inner body format version (distinct from the outer envelope formatVersion). */
    static final short KEYRING_FORMAT_VERSION = 1;
    /** Root wrapped locally by the signing-key-derived KEK (AES-256-GCM). */
    static final byte WRAP_ALG_LOCAL_GCM = 1;
    /** Root sealed by an external KMS - an opaque blob, unsealed only by that provider. */
    static final byte WRAP_ALG_CLOUD_KMS = 2;
    /** Independent random 256-bit root per term. */
    static final int KEYRING_ROOT_LEN = 32;

    private static final byte[] ROOT_LABEL = "root".getBytes(StandardCharsets.UTF_8);
    private static final String GCM_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int WRAP_NONCE_LEN_LOCAL = 12;

    private KeyringCodec() {
        // codec + pure-function holder
    }

    /**
     * One wrapped root. For {@code wrapAlgId == WRAP_ALG_CLOUD_KMS} the {@code wrappedRoot} is an
     * opaque KMS blob and the {@code nonce} is empty. Defensive-copies its arrays.
     */
    record KeyringEntry(int term, byte wrapAlgId, byte[] nonce, byte[] wrappedRoot) {
        KeyringEntry {
            if (term < 1) {
                throw new IntegrityException("keyring entry term must be >= 1, was " + term);
            }
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(wrappedRoot, "wrappedRoot");
            nonce = nonce.clone();
            wrappedRoot = wrappedRoot.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public byte[] wrappedRoot() {
            return wrappedRoot.clone();
        }
    }

    /** The keyring body model (entries still wrapped). */
    record Keyring(int keyringFormatVersion, long keyringSeq, int activeTerm, List<KeyringEntry> entries) {
        Keyring {
            entries = List.copyOf(entries);
        }
    }

    // ---- body serialization -----------------------------------------------------------------

    static byte[] encodeBody(Keyring k) {
        ByteBuffer head = ByteBuffer.allocate(2 + 8 + 4 + 4);
        head.putShort((short) k.keyringFormatVersion());
        head.putLong(k.keyringSeq());
        head.putInt(k.activeTerm());
        head.putInt(k.entries().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head.array());
        for (KeyringEntry e : k.entries()) {
            byte[] nonce = e.nonce();
            byte[] wrapped = e.wrappedRoot();
            if (nonce.length > 0xFF) {
                throw new IntegrityException("keyring entry nonce too long: " + nonce.length);
            }
            ByteBuffer eb = ByteBuffer.allocate(4 + 1 + 1 + nonce.length + 4 + wrapped.length);
            eb.putInt(e.term());
            eb.put(e.wrapAlgId());
            eb.put((byte) nonce.length);
            eb.put(nonce);
            eb.putInt(wrapped.length);
            eb.put(wrapped);
            out.writeBytes(eb.array());
        }
        return out.toByteArray();
    }

    static Keyring decodeBody(byte[] body) {
        if (body.length < 2 + 8 + 4 + 4) {
            throw new IntegrityException("keyring body truncated (length " + body.length + ")");
        }
        ByteBuffer buf = ByteBuffer.wrap(body);
        int fmt = buf.getShort() & 0xFFFF;
        if (fmt == 0 || fmt != KEYRING_FORMAT_VERSION) {
            throw new IntegrityException("unsupported keyringFormatVersion " + fmt
                    + " (expected " + KEYRING_FORMAT_VERSION + ") - fail closed");
        }
        long seq = buf.getLong();
        int activeTerm = buf.getInt();
        if (activeTerm < 1) {
            throw new IntegrityException("keyring activeTerm must be >= 1, was " + activeTerm);
        }
        int count = buf.getInt();
        if (count < 0 || count > buf.remaining()) {
            throw new IntegrityException("keyring entryCount out of range: " + count);
        }
        List<KeyringEntry> entries = new ArrayList<>(count);
        boolean activeTermPresent = false;
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 4 + 1 + 1) {
                throw new IntegrityException("keyring entry header truncated at index " + i);
            }
            int term = buf.getInt();
            if (term == 0) {
                // term 0 is the signing-key (K_keyringMac) domain - illegal as a keyring entry term.
                throw new IntegrityException("keyring entry term 0 is reserved (fail closed)");
            }
            byte wrapAlgId = buf.get();
            if (wrapAlgId != WRAP_ALG_LOCAL_GCM && wrapAlgId != WRAP_ALG_CLOUD_KMS) {
                throw new IntegrityException("unknown keyring wrapAlgId " + (wrapAlgId & 0xFF)
                        + " - fail closed");
            }
            int nonceLen = buf.get() & 0xFF;
            if (buf.remaining() < nonceLen + 4) {
                throw new IntegrityException("keyring entry nonce/length truncated at index " + i);
            }
            byte[] nonce = new byte[nonceLen];
            buf.get(nonce);
            int wrappedLen = buf.getInt();
            if (wrappedLen < 0 || wrappedLen > buf.remaining()) {
                throw new IntegrityException("bad keyring wrappedLen " + wrappedLen + " at index " + i);
            }
            byte[] wrapped = new byte[wrappedLen];
            buf.get(wrapped);
            entries.add(new KeyringEntry(term, wrapAlgId, nonce, wrapped));
            if (term == activeTerm) {
                activeTermPresent = true;
            }
        }
        if (!activeTermPresent) {
            throw new IntegrityException("keyring activeTerm " + activeTerm
                    + " has no matching entry - fail closed");
        }
        return new Keyring(fmt, seq, activeTerm, entries);
    }

    // ---- outer envelope (whole-body integrity under K_keyringMac) ---------------------------

    /** Seals the whole body in the outer HMAC envelope (algId=1) under {@code K_keyringMac}. */
    static byte[] seal(IntegrityEnvelope outerMac, Keyring k) {
        return outerMac.wrap(KEYRING_MAGIC, IntegrityEnvelope.NODE_SCOPE, encodeBody(k));
    }

    /** Verifies the outer MAC then parses the body. Any structural/version/alg failure fails closed. */
    static Keyring openSealed(IntegrityEnvelope outerMac, byte[] enveloped) {
        return decodeBody(outerMac.unwrap(KEYRING_MAGIC, IntegrityEnvelope.NODE_SCOPE, enveloped));
    }

    // ---- per-root wrap / unwrap (local GCM, AAD-bound) --------------------------------------

    private static byte[] entryAad(int keyringFormatVersion, int term, byte wrapAlgId, byte[] nodeKeyId) {
        ByteBuffer aad = ByteBuffer.allocate(4 + 2 + 4 + 1 + nodeKeyId.length + ROOT_LABEL.length);
        aad.putInt(KEYRING_MAGIC);
        aad.putShort((short) keyringFormatVersion);
        aad.putInt(term);
        aad.put(wrapAlgId);
        aad.put(nodeKeyId);
        aad.put(ROOT_LABEL);
        return aad.array();
    }

    /**
     * Wraps a raw 32-byte {@code root} for {@code term} under the local {@code kek} (AES-256-GCM). The
     * AAD binds the term, node id, and the {@code "root"} label so the ciphertext cannot be replayed
     * into another term slot or another node. Uses a fresh random nonce.
     */
    static KeyringEntry wrapRoot(SecretKey kek, byte[] nodeKeyId, int term, byte[] root,
                                 java.security.SecureRandom rng) {
        if (root.length != KEYRING_ROOT_LEN) {
            throw new IntegrityException("keyring root must be " + KEYRING_ROOT_LEN + " B, was " + root.length);
        }
        byte[] nonce = new byte[WRAP_NONCE_LEN_LOCAL];
        rng.nextBytes(nonce);
        byte[] aad = entryAad(KEYRING_FORMAT_VERSION, term, WRAP_ALG_LOCAL_GCM, nodeKeyId);
        try {
            Cipher c = Cipher.getInstance(GCM_TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            c.updateAAD(aad);
            return new KeyringEntry(term, WRAP_ALG_LOCAL_GCM, nonce, c.doFinal(root));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("keyring root wrap failed", e);
        }
    }

    /**
     * Unwraps a local entry's root under {@code kek}. A cloud entry (or any non-local {@code wrapAlgId})
     * cannot be unwrapped here - it is fail-closed (custody is the external KMS's). A bad tag means the
     * wrong KEK (a signing-key mismatch) OR a root replayed into a different term/node - both refused.
     */
    static byte[] unwrapRoot(SecretKey kek, byte[] nodeKeyId, int keyringFormatVersion, KeyringEntry e) {
        if (e.wrapAlgId() != WRAP_ALG_LOCAL_GCM) {
            throw new IntegrityException("cannot locally unwrap keyring wrapAlgId " + (e.wrapAlgId() & 0xFF)
                    + " (a cloud-KMS blob is unsealed only by its external provider) - fail closed");
        }
        byte[] aad = entryAad(keyringFormatVersion, e.term(), e.wrapAlgId(), nodeKeyId);
        try {
            Cipher c = Cipher.getInstance(GCM_TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, e.nonce()));
            c.updateAAD(aad);
            return c.doFinal(e.wrappedRoot());
        } catch (javax.crypto.AEADBadTagException tag) {
            throw new IntegrityException("keyring root unwrap tag failure for term " + e.term()
                    + " (wrong KEK - signing-key mismatch - or a root replayed into another term/node)", tag);
        } catch (GeneralSecurityException ex) {
            throw new IntegrityException("keyring root unwrap error for term " + e.term(), ex);
        }
    }

    /**
     * Boot: unwrap every LOCAL entry into a {@code term -> root} map. A cloud entry with no configured
     * cloud provider is fail-closed (an operator cannot silently drop to a partial keyring). The caller
     * MUST zero the returned byte arrays after copying them into {@code RootKey}s.
     */
    static Map<Integer, byte[]> unsealRoots(SecretKey kek, byte[] nodeKeyId, Keyring k) {
        Map<Integer, byte[]> roots = new LinkedHashMap<>();
        for (KeyringEntry e : k.entries()) {
            if (e.wrapAlgId() == WRAP_ALG_LOCAL_GCM) {
                roots.put(e.term(), unwrapRoot(kek, nodeKeyId, k.keyringFormatVersion(), e));
            } else {
                throw new IntegrityException("keyring contains a wrapAlgId=" + (e.wrapAlgId() & 0xFF)
                        + " (cloud-KMS) entry at term " + e.term() + " but no cloud KMS provider is"
                        + " configured to unseal it - refusing to boot on a partial keyring (fail closed)");
            }
        }
        return roots;
    }

    // ---- rotation pure-functions ------------------------------------------------------------

    /** First boot: a keyring with one random {@code root[1]}, {@code activeTerm=1}, {@code keyringSeq=1}. */
    static Keyring bootstrap(SecretKey kek, byte[] nodeKeyId, byte[] root1, java.security.SecureRandom rng) {
        return new Keyring(KEYRING_FORMAT_VERSION, 1L, 1,
                List.of(wrapRoot(kek, nodeKeyId, 1, root1, rng)));
    }

    /**
     * Term rotation: append {@code root[activeTerm+1]} and advance {@code activeTerm}; {@code keyringSeq}
     * bumps. Every old entry is retained untouched (old-term data still decrypts).
     */
    static Keyring appendTerm(SecretKey kek, byte[] nodeKeyId, Keyring old, byte[] newRoot,
                              java.security.SecureRandom rng) {
        int newTerm = old.activeTerm() + 1;
        List<KeyringEntry> entries = new ArrayList<>(old.entries());
        entries.add(wrapRoot(kek, nodeKeyId, newTerm, newRoot, rng));
        return new Keyring(old.keyringFormatVersion(), old.keyringSeq() + 1, newTerm, entries);
    }

    /**
     * Signing-key rotation: unwrap every LOCAL root under {@code oldKek} and rewrap it under
     * {@code newKek} (re-binding the AAD to {@code newNodeKeyId}). ROOTS ARE UNCHANGED - so every
     * {@code DEK}/{@code K_integrity[term]} is unchanged and all prior data still verifies; only the
     * wrapping KEK (and node-id AAD) change. {@code keyringSeq} bumps, {@code activeTerm} is unchanged.
     * Pure function; transient unwrapped roots are zeroed before return.
     */
    static Keyring rewrapUnderNewKek(SecretKey oldKek, byte[] oldNodeKeyId,
                                     SecretKey newKek, byte[] newNodeKeyId,
                                     Keyring old, java.security.SecureRandom rng) {
        List<KeyringEntry> rewrapped = new ArrayList<>(old.entries().size());
        for (KeyringEntry e : old.entries()) {
            if (e.wrapAlgId() == WRAP_ALG_LOCAL_GCM) {
                byte[] root = unwrapRoot(oldKek, oldNodeKeyId, old.keyringFormatVersion(), e);
                try {
                    rewrapped.add(wrapRoot(newKek, newNodeKeyId, e.term(), root, rng));
                } finally {
                    java.util.Arrays.fill(root, (byte) 0);
                }
            } else {
                // Cloud blobs are opaque and stay under the external KMS's custody: nothing to rewrap.
                rewrapped.add(e);
            }
        }
        return new Keyring(old.keyringFormatVersion(), old.keyringSeq() + 1, old.activeTerm(), rewrapped);
    }
}
