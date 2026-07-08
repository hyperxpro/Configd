package io.configd.common.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password hashing and constant-time verification for the HTTP Basic credential store, using PBKDF2 with
 * HMAC-SHA-256 - a salted, iterated KDF from the JDK ({@link SecretKeyFactory}), so no new dependency and
 * no hand-rolled crypto. Passwords in config are stored HASHED, never plaintext:
 *
 * <pre>pbkdf2-sha256$&lt;iterations&gt;$&lt;saltBase64&gt;$&lt;hashBase64&gt;</pre>
 *
 * <p>{@link #hash} mints that string for a new password (operators run it once); {@link #verify} re-derives
 * with the stored salt/iterations and compares the derived hash to the stored one in CONSTANT TIME
 * ({@link MessageDigest#isEqual}) so a byte-by-byte timing oracle cannot recover the hash. The Base64
 * alphabet avoids {@code :} and {@code ,}, so a hash embeds cleanly in the {@code user:hash:roles}
 * comma-separated store entry.
 */
public final class BasicAuthPasswords {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    /** OWASP-range default work factor for PBKDF2-HMAC-SHA-256; the stored value is authoritative on verify. */
    private static final int DEFAULT_ITERATIONS = 210_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENC = Base64.getEncoder();
    private static final Base64.Decoder DEC = Base64.getDecoder();

    private BasicAuthPasswords() {
    }

    /** Hashes {@code password} with a fresh random salt at the default work factor. The array is not modified. */
    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, DEFAULT_ITERATIONS, HASH_BITS);
        return PREFIX + "$" + DEFAULT_ITERATIONS + "$" + ENC.encodeToString(salt) + "$" + ENC.encodeToString(derived);
    }

    /**
     * True iff {@code password} matches the {@code stored} hash. Fails closed (returns {@code false}) on a
     * malformed stored string rather than throwing, so a corrupt store entry denies rather than crashing a
     * request - the store is validated at boot ({@link #isValidHash}) so this should not happen in practice.
     */
    public static boolean verify(String stored, char[] password) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = DEC.decode(parts[2]);
            expected = DEC.decode(parts[3]);
        } catch (RuntimeException e) {
            return false;
        }
        if (iterations < 1) {
            return false;
        }
        byte[] derived = pbkdf2(password, salt, iterations, expected.length * 8);
        return MessageDigest.isEqual(derived, expected);
    }

    /** True if {@code stored} parses as a well-formed hash of this scheme - used to validate the store at boot. */
    public static boolean isValidHash(String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            return Integer.parseInt(parts[1]) >= 1 && DEC.decode(parts[2]).length > 0 && DEC.decode(parts[3]).length > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 key derivation failed", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            // PBKDF2WithHmacSHA256 is a mandated JCA algorithm on every conformant JDK.
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
