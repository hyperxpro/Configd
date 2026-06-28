package io.configd.kms;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * The <b>sealed</b> form of a per-node root key: opaque ciphertext (the root key
 * encrypted under the provider's KEK) plus the {@link KeyId} and the
 * encryption-context that produced it. A {@code WrappedKey} is <b>safe to persist
 * and safe to log</b> — being ciphertext + non-secret metadata, it carries no live
 * key material.
 *
 * <p>Design-research artifact (KMS-SPI). NOT production code.
 *
 * <p><b>The type distinction IS the safety property.</b> {@code WrappedKey}
 * (persistable, loggable, never wiped) is a <em>different Java type</em> from
 * {@link RootKey} (live, wiped, never logged). The compiler — not a code reviewer's
 * memory — now distinguishes "ciphertext blob you may write to disk" from "plaintext
 * key you must wipe ASAP". Passing a {@code RootKey} where a {@code WrappedKey} is
 * expected (e.g. to a persistence call) is a compile error, not a leak.
 *
 * <p><b>{@code byte[]} is acceptable HERE — and only here.</b> The §3 argument
 * against {@code byte[]} is about <em>live key material</em>. {@code ciphertext} is
 * not live key material; it is the sealed blob. It still gets a controlled
 * {@link #toString()} (length only, never the bytes) and defensive copies, but it
 * needs no wipe lifecycle.
 *
 * <p>For the {@code local} provider (re-derives the root key from the signing key)
 * the {@code ciphertext} is empty and the {@code WrappedKey} degenerates to a
 * <em>derivation descriptor</em> — the {@link KeyId} plus context is all the
 * provider needs to reconstruct the root key. The opacity contract still holds: the
 * core never interprets the bytes; only the owning provider does.
 *
 * @param keyId      which KEK + keyring term sealed this (self-describing for
 *                   zero-coordination key selection on read)
 * @param ciphertext the provider-opaque sealed bytes (KMS {@code CiphertextBlob};
 *                   empty for {@code local})
 * @param context    the encryption-context / AAD bound into the seal (e.g.
 *                   {@code {"node":"n3","purpose":"raft-at-rest-kek"}}); non-secret,
 *                   exact-match-to-unwrap — a relocated/renamed blob fails to unwrap
 */
public record WrappedKey(KeyId keyId, byte[] ciphertext, Map<String, String> context) {

    public WrappedKey {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(context, "context");
        ciphertext = ciphertext.clone();              // defensive copy in
        context = Map.copyOf(context);                // immutable copy in
    }

    /** Defensive copy out — callers cannot mutate the sealed bytes in place. */
    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /** Structural equality (records default to array <em>identity</em>; fix it). */
    @Override
    public boolean equals(Object o) {
        return o instanceof WrappedKey w
                && keyId.equals(w.keyId)
                && Arrays.equals(ciphertext, w.ciphertext)
                && context.equals(w.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, Arrays.hashCode(ciphertext), context);
    }

    /** Redacted: {@link KeyId} + ciphertext <em>length</em>, never the bytes. */
    @Override
    public String toString() {
        return "WrappedKey[keyId=" + keyId + ", ciphertext=" + ciphertext.length + "B, context="
                + context.keySet() + ']';
    }
}
