import io.configd.kms.*;
import java.util.Map;
import java.util.Optional;

/**
 * Behavioural smoke test for the KMS-SPI design sketch: demonstrates the
 * key-material lifecycle (wipe-on-close), the redacted toString contracts, and the
 * fail-loud provider selection. No crypto is exercised. Not a unit test — a
 * design-validation probe.
 */
public class SketchSmokeTest {
    static int checks = 0;
    static void check(String what, boolean ok) {
        checks++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        KeyId localId = new KeyId("local", "sign-9f3a", 1);

        // --- RootKey: wipe-on-close, use-after-wipe guard, redaction ---
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 0x5A);
        RootKey root = new RootKey(secret, localId);            // takes ownership of `secret`
        check("RootKey.toString() redacts material",
                !root.toString().contains("5a") && root.toString().contains("destroyed=false"));
        check("RootKey live before close", !root.isDestroyed() && root.length() == 32);
        check("withMaterial sees the bytes", root.withMaterial(m -> m[0]) == (byte) 0x5A);
        root.close();                                            // try-with-resources would do this
        check("RootKey destroyed after close", root.isDestroyed());
        check("backing array actually zeroed", secret[0] == 0 && secret[31] == 0);  // same array
        boolean threw = false;
        try { root.length(); } catch (IllegalStateException e) { threw = true; }
        check("use-after-wipe throws", threw);

        // --- WrappedKey: persistable, redacted, defensive-copy, structural equals ---
        byte[] ct = {1, 2, 3, 4};
        WrappedKey w = new WrappedKey(localId, ct, Map.of("node", "n3"));
        ct[0] = 99;                                              // mutate caller's array
        check("WrappedKey defensively copied ciphertext in", w.ciphertext()[0] == 1);
        check("WrappedKey.toString() shows length not bytes",
                w.toString().contains("4B") && !w.toString().contains("[1, 2, 3, 4]"));
        check("WrappedKey structural equals",
                w.equals(new WrappedKey(localId, new byte[]{1, 2, 3, 4}, Map.of("node", "n3"))));

        // --- Provider selection: default=local, unknown=fail-loud (never silent fallback) ---
        KmsConfig empty = key -> Optional.empty();
        KmsProvider local = KmsProviders.select(empty, () -> new byte[64], localId);
        check("default selects the built-in local provider", "local".equals(local.type()));

        KmsConfig forcedAws = key -> KmsProviders.PROVIDER_KEY.equals(key)
                ? Optional.of("aws-kms") : Optional.empty();
        boolean failLoud = false;
        String msg = "";
        try {
            KmsProviders.select(forcedAws, () -> new byte[64], localId);
        } catch (IllegalStateException e) {
            failLoud = true;
            msg = e.getMessage();
        }
        check("forcing an absent provider FAILS LOUD", failLoud);
        check("...and refuses to silently fall back to local",
                msg.contains("Refusing to silently fall back"));

        // --- local provider never fails closed (key is always derivable) ---
        check("local.currentKeyId() is loggable", local.currentKeyId().toString().equals("local:sign-9f3a#1"));

        System.out.println("\nAll " + checks + " design-contract checks passed.");
    }
}
