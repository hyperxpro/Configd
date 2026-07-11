package io.configd.common.kms;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the typed key-material carriers: {@link RootKey} (live, wipeable, redacted),
 * {@link WrappedKey} (sealed, persistable, redacted), {@link KeyId} (non-secret identity).
 */
class KmsKeyMaterialTest {

    private static byte[] material() {
        byte[] m = new byte[32];
        for (int i = 0; i < m.length; i++) {
            m[i] = (byte) (i + 1);
        }
        return m;
    }

    private static KeyId keyId() {
        return new KeyId("local", "test-ref", 1);
    }

    @Test
    void destroyZeroesBackingMaterialAndIsIdempotent() {
        byte[] captured = new byte[32];
        RootKey key = new RootKey(material(), keyId());
        assertFalse(key.isDestroyed());
        assertEquals(32, key.length());

        key.destroy();
        assertTrue(key.isDestroyed());
        // idempotent + never throws
        key.destroy();
        key.close();

        // material is gone: any live access now throws
        assertThrows(IllegalStateException.class, key::length);
        assertThrows(IllegalStateException.class, () -> key.withMaterial(m -> {
            System.arraycopy(m, 0, captured, 0, m.length);
            return null;
        }));
    }

    @Test
    void closeDelegatesToDestroy_tryWithResources() {
        RootKey ref;
        try (RootKey key = new RootKey(material(), keyId())) {
            ref = key;
            assertFalse(key.isDestroyed());
        }
        assertTrue(ref.isDestroyed());
    }

    @Test
    void withMaterialHandsACloneThatIsWipedAfterUse() {
        RootKey key = new RootKey(material(), keyId());
        byte[][] escaped = new byte[1][];
        byte[] observed = key.withMaterial(m -> {
            escaped[0] = m;                 // same array reference the finally-block wipes
            return m.clone();               // a snapshot of the contents during the call
        });
        assertArrayEquals(material(), observed, "consumer sees the real key bytes during the call");
        // the array handed to the consumer is zeroed after withMaterial returns
        assertArrayEquals(new byte[32], escaped[0], "the transient clone is wiped after use");
        // the RootKey itself is untouched (still usable)
        assertArrayEquals(material(), key.withMaterial(byte[]::clone));
    }

    @Test
    void toSecretKeyBridgesToJcaButIsIndependentCopy() {
        RootKey key = new RootKey(material(), keyId());
        SecretKey jca = key.toSecretKey("AES");
        assertEquals("AES", jca.getAlgorithm());
        assertArrayEquals(material(), jca.getEncoded());
        // destroying the RootKey does NOT wipe the JCA copy (JDK-8160206) - the documented residual
        key.destroy();
        assertArrayEquals(material(), jca.getEncoded(), "SecretKeySpec copy survives (must be transient)");
    }

    @Test
    void rootKeyToStringNeverLeaksBytes() {
        RootKey key = new RootKey(material(), keyId());
        String s = key.toString();
        assertTrue(s.contains("local:test-ref#1"), "renders the non-secret identity");
        assertTrue(s.contains("destroyed=false"));
        // must render neither the hex nor a Arrays.toString() of the key bytes
        assertFalse(s.contains("010203"), "must not render key bytes (hex)");
        assertFalse(s.contains("1, 2, 3"), "must not render key bytes (array form)");
    }

    @Test
    void wrappedKeyDefensiveCopiesInAndOut() {
        byte[] ct = {1, 2, 3, 4};
        WrappedKey w = new WrappedKey(keyId(), ct, Map.of("node", "1"));
        ct[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, w.ciphertext(), "copy-in isolates from caller");
        byte[] out = w.ciphertext();
        out[0] = 42;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, w.ciphertext(), "copy-out isolates internal state");
        assertNotSame(w.ciphertext(), w.ciphertext());
    }

    @Test
    void wrappedKeyValueEqualityAndRedactedToString() {
        WrappedKey a = new WrappedKey(keyId(), new byte[]{1, 2, 3}, Map.of("node", "1"));
        WrappedKey b = new WrappedKey(keyId(), new byte[]{1, 2, 3}, Map.of("node", "1"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        String s = a.toString();
        assertTrue(s.contains("ciphertextLen=3"));
        assertFalse(s.contains("[1, 2, 3]"), "must not render ciphertext bytes");
    }

    @Test
    void keyIdToStringIsLoggableIdentity() {
        assertEquals("aws-kms:arn:...#7", new KeyId("aws-kms", "arn:...", 7).toString());
    }

    @Test
    void keyIdRejectsTermBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new KeyId("local", "r", 0));
    }
}
