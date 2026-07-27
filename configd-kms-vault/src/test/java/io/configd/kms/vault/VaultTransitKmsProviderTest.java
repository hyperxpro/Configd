package io.configd.kms.vault;

import io.configd.common.kms.KeyId;
import io.configd.common.kms.KmsBootContext;
import io.configd.common.kms.KmsProvider;
import io.configd.common.kms.KmsUnavailableException;
import io.configd.common.kms.RootKey;
import io.configd.common.kms.WrappedKey;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link VaultTransitKmsProvider} against the in-process {@link FakeVaultTransit} (real HTTP, no
 * Docker): the seal/unseal round-trip, AAD-mismatch rejection, KEK re-seal, and fail-closed
 * ({@link KmsUnavailableException}) when Vault is unreachable. The real-Vault cryptographic proof is
 * {@code VaultTransitKmsIT}.
 */
class VaultTransitKmsProviderTest {

    private static final String MOUNT = "transit";
    private static final String KEY = "configd-root-kek";

    private static VaultConfig config(String baseUrl, String aad) {
        Map<String, String> m = new HashMap<>();
        m.put("configd.kms.vault.address", baseUrl);
        m.put("configd.kms.vault.transitMount", MOUNT);
        m.put("configd.kms.vault.transitKeyName", KEY);
        m.put("configd.kms.vault.auth.approle.roleId", "role-1");
        m.put("configd.kms.vault.auth.approle.secretId", "secret-1");
        m.put("configd.kms.vault.timeoutMs", "2000");
        return VaultConfig.parse(new MapConfig(m), new KmsBootContext(aad));
    }

    @Test
    void provisionThenUnsealRoundTripsTheSecret() throws Exception {
        try (FakeVaultTransit vault = new FakeVaultTransit(MOUNT)) {
            VaultTransitKmsProvider provider = new VaultTransitKmsProvider(config(vault.baseUrl(), "node-A"));
            assertEquals("vault-transit", provider.type());
            provider.healthCheck();

            KmsProvider.Provisioned prov = provider.generateRootKey();
            byte[] original = prov.rootKey().withMaterial(byte[]::clone);
            assertEquals(32, original.length, "256-bit custody secret");
            WrappedKey wrapped = prov.wrapped();
            assertTrue(new String(wrapped.ciphertext(), StandardCharsets.UTF_8).startsWith("vault:v"),
                    "carrier is the self-describing vault:vN: blob");
            assertEquals(new KeyId("vault-transit", MOUNT + "/" + KEY, 1), wrapped.keyId());

            // A fresh provider unseals the persisted carrier to the identical bytes (restart semantics).
            RootKey unsealed = new VaultTransitKmsProvider(config(vault.baseUrl(), "node-A")).unwrap(wrapped);
            assertArrayEquals(original, unsealed.withMaterial(byte[]::clone),
                    "unwrap returns the exact provisioned secret");
        }
    }

    @Test
    void aadMismatchFailsToUnseal() throws Exception {
        try (FakeVaultTransit vault = new FakeVaultTransit(MOUNT)) {
            WrappedKey wrapped = new VaultTransitKmsProvider(config(vault.baseUrl(), "node-A"))
                    .generateRootKey().wrapped();
            // A different node identity (AAD) must NOT be able to unseal the carrier (relocation defence).
            VaultTransitKmsProvider wrongNode = new VaultTransitKmsProvider(config(vault.baseUrl(), "node-B"));
            KmsUnavailableException ex = assertThrows(KmsUnavailableException.class, () -> wrongNode.unwrap(wrapped));
            assertTrue(ex.getMessage().contains("unseal"), ex.getMessage());
        }
    }

    @Test
    void reSealUnderRotatedKekStillUnseals() throws Exception {
        try (FakeVaultTransit vault = new FakeVaultTransit(MOUNT)) {
            VaultTransitKmsProvider provider = new VaultTransitKmsProvider(config(vault.baseUrl(), "node-A"));
            KmsProvider.Provisioned prov = provider.generateRootKey();
            byte[] original = prov.rootKey().withMaterial(byte[]::clone);

            provider.rotateKek();
            RootKey live = provider.unwrap(prov.wrapped()); // old carrier still decrypts post-rotate
            assertArrayEquals(original, live.withMaterial(byte[]::clone));

            WrappedKey resealed = provider.wrap(live);       // re-seal under the new version
            assertFalse(java.util.Arrays.equals(prov.wrapped().ciphertext(), resealed.ciphertext()),
                    "a re-seal yields a fresh carrier");
            assertArrayEquals(original, provider.unwrap(resealed).withMaterial(byte[]::clone),
                    "the re-sealed carrier unseals to the same secret");
        }
    }

    @Test
    void unreachableVaultFailsClosed() {
        VaultTransitKmsProvider provider = new VaultTransitKmsProvider(config("http://127.0.0.1:1", "node-A"));
        assertThrows(KmsUnavailableException.class, provider::healthCheck);
        WrappedKey bogus = new WrappedKey(provider.currentKeyId(),
                "vault:v1:AAAA".getBytes(StandardCharsets.UTF_8), Map.of());
        assertThrows(KmsUnavailableException.class, () -> provider.unwrap(bogus));
    }
}
