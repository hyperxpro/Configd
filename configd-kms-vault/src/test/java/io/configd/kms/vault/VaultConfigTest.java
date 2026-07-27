package io.configd.kms.vault;

import io.configd.common.config.ConfigException;
import io.configd.common.kms.KmsBootContext;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultConfigTest {

    private static final KmsBootContext CTX = new KmsBootContext("node-7");

    private static Map<String, String> minimal() {
        return new java.util.HashMap<>(Map.of(
                "configd.kms.vault.address", "https://vault.internal:8200/",
                "configd.kms.vault.transitKeyName", "configd-root-kek",
                "configd.kms.vault.auth.approle.roleId", "role-abc",
                "configd.kms.vault.auth.approle.secretId", "secret-xyz"));
    }

    @Test
    void parsesDefaultsAndAppRole() {
        VaultConfig cfg = VaultConfig.parse(new MapConfig(minimal()), CTX);
        assertEquals("https://vault.internal:8200", cfg.address(), "trailing slash trimmed");
        assertEquals("transit", cfg.transitMount(), "default mount");
        assertEquals("configd-root-kek", cfg.keyName());
        assertNull(cfg.namespace());
        assertEquals("node-7", cfg.aadContext(), "AAD defaults to the boot nodeId");
        assertEquals(256, cfg.bits());
        assertEquals(VaultConfig.Auth.Method.APPROLE, cfg.auth().method());
        assertEquals("role-abc", cfg.auth().roleId());
        assertEquals("secret-xyz", cfg.auth().secretId());
    }

    @Test
    void honoursExplicitOverrides() {
        Map<String, String> m = minimal();
        m.put("configd.kms.vault.transitMount", "transit-2");
        m.put("configd.kms.vault.namespace", "team-a");
        m.put("configd.kms.vault.aadContext", "custom-aad");
        m.put("configd.kms.vault.bits", "512");
        m.put("configd.kms.vault.timeoutMs", "1500");
        VaultConfig cfg = VaultConfig.parse(new MapConfig(m), CTX);
        assertEquals("transit-2", cfg.transitMount());
        assertEquals("team-a", cfg.namespace());
        assertEquals("custom-aad", cfg.aadContext());
        assertEquals(512, cfg.bits());
        assertEquals(1500, cfg.timeout().toMillis());
    }

    @Test
    void tokenAuthMethod() {
        Map<String, String> m = new java.util.HashMap<>(Map.of(
                "configd.kms.vault.address", "http://localhost:8200",
                "configd.kms.vault.transitKeyName", "k",
                "configd.kms.vault.auth.method", "token",
                "configd.kms.vault.auth.token", "s.rootdev"));
        VaultConfig cfg = VaultConfig.parse(new MapConfig(m), CTX);
        assertEquals(VaultConfig.Auth.Method.TOKEN, cfg.auth().method());
        assertEquals("s.rootdev", cfg.auth().token());
    }

    @Test
    void missingRequiredAddressFailsLoud() {
        Map<String, String> m = minimal();
        m.remove("configd.kms.vault.address");
        ConfigException ex = assertThrows(ConfigException.class, () -> VaultConfig.parse(new MapConfig(m), CTX));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("address"));
    }

    @Test
    void missingKeyNameFailsLoud() {
        Map<String, String> m = minimal();
        m.remove("configd.kms.vault.transitKeyName");
        assertThrows(ConfigException.class, () -> VaultConfig.parse(new MapConfig(m), CTX));
    }

    @Test
    void appRoleWithoutSecretFailsLoud() {
        Map<String, String> m = minimal();
        m.remove("configd.kms.vault.auth.approle.secretId");
        assertThrows(ConfigException.class, () -> VaultConfig.parse(new MapConfig(m), CTX));
    }

    @Test
    void unsupportedAuthMethodFailsLoud() {
        Map<String, String> m = minimal();
        m.put("configd.kms.vault.auth.method", "kubernetes");
        ConfigException ex = assertThrows(ConfigException.class, () -> VaultConfig.parse(new MapConfig(m), CTX));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("kubernetes"));
    }

    @Test
    void invalidBitsFailsLoud() {
        Map<String, String> m = minimal();
        m.put("configd.kms.vault.bits", "300");
        assertThrows(ConfigException.class, () -> VaultConfig.parse(new MapConfig(m), CTX));
    }
}
