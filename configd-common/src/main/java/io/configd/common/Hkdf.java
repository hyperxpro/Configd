package io.configd.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Dependency-free HKDF-SHA256 (RFC 5869) over {@code javax.crypto.Mac}
 * {@code "HmacSHA256"}.
 * <p>
 * HKDF is the extract-then-expand key derivation function. We use it to derive
 * the at-rest integrity HMAC key from the existing cluster Ed25519 signing key,
 * so no new key file or distribution channel is introduced. Both the persist side
 * and the verify side run the same derivation.
 * <p>
 * The JDK ships no public HKDF before {@code javax.crypto.KDF} (JEP 478, still
 * preview); this ~RFC-faithful implementation is intentionally small and is unit
 * tested against an RFC 5869 test vector ({@code HkdfTest}).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5869">RFC 5869</a>
 */
public final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    /**
     * HKDF-Extract (RFC 5869 section 2.2): {@code PRK = HMAC-Hash(salt, IKM)}.
     * A null/empty salt is replaced with a string of {@code HashLen} zero bytes,
     * per the RFC.
     *
     * @param salt optional salt (may be null/empty)
     * @param ikm  the input keying material (non-null)
     * @return the {@code HashLen}-byte pseudorandom key
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        return hmac(effectiveSalt, ikm);
    }

    /**
     * HKDF-Expand (RFC 5869 section 2.3): expands {@code prk} to {@code length} bytes of
     * output keying material bound to {@code info}.
     *
     * @param prk    a pseudorandom key of at least {@code HashLen} bytes (non-null)
     * @param info   optional context/application-specific info (may be null, treated as empty)
     * @param length desired output length in bytes; {@code 1 <= length <= 255*HashLen}
     * @return the derived {@code length}-byte output keying material
     */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length < 1 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException(
                    "HKDF expand length out of range [1, " + (255 * HASH_LEN) + "]: " + length);
        }
        byte[] ctx = (info == null) ? new byte[0] : info;
        int n = (length + HASH_LEN - 1) / HASH_LEN; // ceil(length / HashLen)
        byte[] okm = new byte[n * HASH_LEN];
        byte[] t = new byte[0]; // T(0) = empty
        for (int i = 1; i <= n; i++) {
            // T(i) = HMAC(PRK, T(i-1) || info || byte(i))
            byte[] input = new byte[t.length + ctx.length + 1];
            System.arraycopy(t, 0, input, 0, t.length);
            System.arraycopy(ctx, 0, input, t.length, ctx.length);
            input[input.length - 1] = (byte) i;
            t = hmac(prk, input);
            System.arraycopy(t, 0, okm, (i - 1) * HASH_LEN, HASH_LEN);
        }
        return Arrays.copyOf(okm, length);
    }

    public static byte[] deriveKey(byte[] ikm, byte[] salt, byte[] info, int length) {
        return expand(extract(salt, ikm), info, length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandated by the JCA spec on every conformant JRE.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
