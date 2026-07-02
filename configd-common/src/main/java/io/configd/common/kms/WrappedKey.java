package io.configd.common.kms;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * The opaque, sealed carrier for a per-node root key: ciphertext plus its
 * {@link KeyId} and AAD context. This is what the core persists between boots and
 * hands back to {@link KmsProvider#unwrap(WrappedKey)} at the next start.
 * <p>
 * The <b>type distinction from {@link RootKey} is itself the safety property</b>:
 * the compiler stops you handing a live key to a persist/log call, or a sealed
 * blob to a {@code Cipher}. {@code byte[]} is acceptable <em>here</em> because the
 * ciphertext is not live key material - a {@code WrappedKey} is safe to persist
 * and safe to log (redacted). Its bytes are opaque to the core; only the owning
 * provider interprets them (a re-derivation descriptor for {@code local}, a KMS
 * {@code CiphertextBlob} for a cloud provider).
 * <p>
 * Records default to array <em>identity</em> equals/hashCode and would expose the
 * ciphertext via the generated {@code toString()}, so all three are overridden:
 * value-semantic equality, and a redacted {@code toString()} that shows the
 * ciphertext <em>length</em> only.
 *
 * @param keyId      the non-secret KEK identity + keyring term that sealed this key
 * @param ciphertext the sealed root-key bytes (opaque; defensively copied in/out)
 * @param context    the AAD / encryption-context bound into the seal (e.g. node id)
 */
public record WrappedKey(KeyId keyId, byte[] ciphertext, Map<String, String> context) {

    public WrappedKey {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(ciphertext, "ciphertext");
        context = (context == null) ? Map.of() : Map.copyOf(context);
        ciphertext = ciphertext.clone(); // copy-in: the caller cannot mutate our state afterwards
    }

    /** A defensive copy of the sealed bytes; callers cannot mutate the record's state. */
    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof WrappedKey other
                && keyId.equals(other.keyId)
                && Arrays.equals(ciphertext, other.ciphertext)
                && context.equals(other.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, Arrays.hashCode(ciphertext), context);
    }

    @Override
    public String toString() {
        // Redacted: identity + ciphertext LENGTH only, never the sealed bytes.
        return "WrappedKey[keyId=" + keyId + ", ciphertextLen=" + ciphertext.length
                + ", context=" + context.keySet() + "]";
    }
}
