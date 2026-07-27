package io.configd.kms.vault;

import io.configd.common.config.ConfigException;
import io.configd.common.kms.KmsBootContext;
import io.configd.common.kms.KmsProvider;
import io.configd.common.kms.KmsProviderFactory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultKmsProviderFactoryTest {

    private static final KmsBootContext CTX = new KmsBootContext("node-1");

    @Test
    void factoryAdvertisesVaultTransitType() {
        assertEquals("vault-transit", new VaultKmsProviderFactory().type());
    }

    @Test
    void discoveredViaServiceLoader() {
        Map<String, KmsProviderFactory> registry = KmsProviderFactory.discover();
        KmsProviderFactory factory = registry.get("vault-transit");
        assertNotNull(factory, "vault-transit must be discovered via META-INF/services when this module is present");
        assertInstanceOf(VaultKmsProviderFactory.class, factory);
    }

    @Test
    void createBuildsProviderFromValidConfig() {
        Map<String, String> m = Map.of(
                "configd.kms.vault.address", "http://localhost:8200",
                "configd.kms.vault.transitKeyName", "configd-root-kek",
                "configd.kms.vault.auth.method", "token",
                "configd.kms.vault.auth.token", "s.dev");
        KmsProvider provider = new VaultKmsProviderFactory().create(new MapConfig(m), CTX);
        assertInstanceOf(VaultTransitKmsProvider.class, provider);
        assertEquals("vault-transit", provider.type());
    }

    @Test
    void createFailsLoudOnMissingRequiredConfig() {
        // no address / keyName -> ConfigException at construction (before any KMS I/O)
        ConfigException ex = assertThrows(ConfigException.class,
                () -> new VaultKmsProviderFactory().create(new MapConfig(Map.of()), CTX));
        assertTrue(ex.getMessage().contains("configd.kms.vault"));
    }
}
