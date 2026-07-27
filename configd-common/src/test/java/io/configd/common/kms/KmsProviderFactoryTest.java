package io.configd.common.kms;

import io.configd.common.config.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link KmsProviderFactory} discovery: the core classpath advertises NO external factory (so
 * {@code local} is never a ServiceLoader entry and the core stays vendor-free), and the fail-loud
 * shadow/collision guards reject a factory that would displace the built-in {@code local} or a duplicate
 * type - the same posture the authenticator SPI uses.
 */
class KmsProviderFactoryTest {

    /** A minimal fake factory advertising a given type; {@code create} is never invoked in these tests. */
    private static KmsProviderFactory factory(String type) {
        return new KmsProviderFactory() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public KmsProvider create(ConfigSource cfg, KmsBootContext ctx) {
                throw new UnsupportedOperationException("not used in discovery tests");
            }
        };
    }

    @Test
    void coreClasspathDiscoversNoExternalFactory() {
        assertTrue(KmsProviderFactory.discover().isEmpty(),
                "the core classpath must advertise no external KMS provider factory");
    }

    @Test
    void indexesDistinctTypesByName() {
        KmsProviderFactory vault = factory("vault-transit");
        KmsProviderFactory aws = factory("aws-kms");
        Map<String, KmsProviderFactory> registry = KmsProviderFactory.index(List.of(vault, aws));
        assertEquals(2, registry.size());
        assertSame(vault, registry.get("vault-transit"));
        assertSame(aws, registry.get("aws-kms"));
    }

    @Test
    void rejectsFactoryShadowingBuiltinLocal() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> KmsProviderFactory.index(List.of(factory("local"))));
        assertTrue(ex.getMessage().contains("local") && ex.getMessage().contains("shadow"),
                "shadowing the built-in local provider must fail loud: " + ex.getMessage());
    }

    @Test
    void rejectsDuplicateType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> KmsProviderFactory.index(List.of(factory("vault-transit"), factory("vault-transit"))));
        assertTrue(ex.getMessage().contains("same type"),
                "two factories with the same type must fail loud: " + ex.getMessage());
    }

    @Test
    void rejectsBlankType() {
        assertThrows(IllegalStateException.class,
                () -> KmsProviderFactory.index(List.of(factory("  "))));
    }
}
