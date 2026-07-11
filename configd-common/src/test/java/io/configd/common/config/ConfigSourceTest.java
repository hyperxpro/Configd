package io.configd.common.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ConfigSource} SPI: each source in isolation, the layered precedence, the fail-closed typed
 * accessors, key enumeration, the systematic + legacy-alias environment mapping, and the OR-across-layers
 * {@link ConfigSource#anyLayerTrue} that reproduces the legacy "system-property OR env-alias" flags.
 */
class ConfigSourceTest {

    /** A tiny in-memory source, used to stand in for a YAML layer / a controlled lower layer in tests. */
    private static final class MapConfigSource implements ConfigSource {
        private final Map<String, String> map;

        MapConfigSource(Map<String, String> map) {
            this.map = Map.copyOf(map);
        }

        @Override
        public Optional<String> getString(String key) {
            return Optional.ofNullable(map.get(key));
        }

        @Override
        public Set<String> keysWithPrefix(String prefix) {
            return map.keySet().stream().filter(k -> k.startsWith(prefix)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    @Nested
    class SystemProperties {
        @Test
        void presentAbsentAndFalseAreDistinct() {
            String key = "configd.test.spflag." + System.nanoTime();
            SystemPropertyConfigSource src = new SystemPropertyConfigSource();
            try {
                assertTrue(src.getString(key).isEmpty(), "unset property is empty, NOT false");
                System.setProperty(key, "false");
                assertEquals(Optional.of("false"), src.getString(key),
                        "an explicit 'false' is present and distinguishable from unset");
            } finally {
                System.clearProperty(key);
            }
        }

        @Test
        void readsLiveSoALaterSetIsSeen() {
            String key = "configd.test.live." + System.nanoTime();
            SystemPropertyConfigSource src = new SystemPropertyConfigSource();
            try {
                assertTrue(src.getString(key).isEmpty());
                System.setProperty(key, "9");
                assertEquals(9, src.getInt(key, 1), "a property set after construction is observed live");
            } finally {
                System.clearProperty(key);
            }
        }
    }

    @Nested
    class Environment {
        @Test
        void systematicMappingLowercasesAndDots() {
            EnvConfigSource env = new EnvConfigSource(Map.of("CONFIGD_RAFT_ENCRYPTION_ENABLED", "true"));
            assertEquals(Optional.of("true"), env.getString("configd.raft.encryption.enabled"));
        }

        @Test
        void nonConfigdVarsAreIgnored() {
            EnvConfigSource env = new EnvConfigSource(Map.of("PATH", "/usr/bin", "HOME", "/root"));
            assertTrue(env.getString("path").isEmpty());
            assertTrue(env.keysWithPrefix("").isEmpty());
        }

        @Test
        void legacyAliasesMapToExactCanonicalKeys() {
            EnvConfigSource env = new EnvConfigSource(Map.of(
                    "CONFIGD_ALLOW_COLOCATED_SIGNING_KEY", "true",
                    "CONFIGD_ENCRYPTION_AT_REST", "true",
                    "CONFIGD_ENCRYPTION_REQUIRE_ENCRYPTED", "true",
                    "CONFIGD_ENCRYPTION_KMS_PROVIDER", "vault"));
            assertEquals(Optional.of("true"), env.getString("configd.security.allowColocatedSigningKey"));
            assertEquals(Optional.of("true"), env.getString("configd.raft.encryption.enabled"));
            assertEquals(Optional.of("true"), env.getString("configd.raft.encryption.requireEncrypted"));
            assertEquals(Optional.of("vault"), env.getString("configd.raft.encryption.kms.provider"));
        }

        @Test
        void legacyAliasOverridesSystematicOnCollision() {
            // CONFIGD_ENCRYPTION_AT_REST (alias) and CONFIGD_RAFT_ENCRYPTION_ENABLED (systematic) both
            // target configd.raft.encryption.enabled; the explicit alias wins.
            EnvConfigSource env = new EnvConfigSource(Map.of(
                    "CONFIGD_RAFT_ENCRYPTION_ENABLED", "false",
                    "CONFIGD_ENCRYPTION_AT_REST", "true"));
            assertEquals(Optional.of("true"), env.getString("configd.raft.encryption.enabled"));
        }
    }

    @Nested
    class Precedence {
        @Test
        void firstPresentSourceWinsPerKey() {
            ConfigSource top = new MapConfigSource(Map.of("k", "top"));
            ConfigSource mid = new MapConfigSource(Map.of("k", "mid", "only.mid", "m"));
            ConfigSource low = new MapConfigSource(Map.of("k", "low", "only.low", "l"));
            LayeredConfigSource layered = LayeredConfigSource.of(top, mid, low);
            assertEquals(Optional.of("top"), layered.getString("k"), "highest layer that defines the key wins");
            assertEquals(Optional.of("m"), layered.getString("only.mid"), "a lower layer still supplies keys the top omits");
            assertEquals(Optional.of("l"), layered.getString("only.low"));
            assertTrue(layered.getString("absent").isEmpty());
        }

        @Test
        void systemPropertyBeatsEnvironmentBeatsYaml() {
            // The concrete production ordering: -D over env over the YAML layer. A -D override still wins
            // over a YAML file, so a deployment's existing overrides keep sitting on top.
            String key = "configd.test.prec." + System.nanoTime();
            EnvConfigSource env = new EnvConfigSource(Map.of("CONFIGD_TEST_PREC", "env"));
            ConfigSource yaml = new MapConfigSource(Map.of("configd.test.prec", "yaml", key, "yaml"));
            try {
                System.setProperty(key, "sysprop");
                // Map the env systematic key onto our synthetic key for the env-beats-yaml leg.
                LayeredConfigSource full = LayeredConfigSource.of(new SystemPropertyConfigSource(), env, yaml);
                assertEquals(Optional.of("sysprop"), full.getString(key), "system property beats the YAML layer");
                assertEquals(Optional.of("env"), full.getString("configd.test.prec"),
                        "environment beats the YAML layer when no system property is set");
            } finally {
                System.clearProperty(key);
            }
        }

        @Test
        void keysWithPrefixUnionsAllLayers() {
            ConfigSource a = new MapConfigSource(Map.of("configd.a.one", "1", "configd.b.x", "x"));
            ConfigSource b = new MapConfigSource(Map.of("configd.a.two", "2"));
            LayeredConfigSource layered = LayeredConfigSource.of(a, b);
            assertEquals(Set.of("configd.a.one", "configd.a.two"), layered.keysWithPrefix("configd.a."));
        }
    }

    @Nested
    class TypedAccessors {
        private ConfigSource of(Map<String, String> m) {
            return new MapConfigSource(m);
        }

        @Test
        void getIntParsesDefaultsAndFailsClosedOnGarbage() {
            assertEquals(42, of(Map.of("k", "42")).getInt("k", 7));
            assertEquals(7, of(Map.of()).getInt("k", 7), "absent -> default");
            assertThrows(ConfigException.class, () -> of(Map.of("k", "abc")).getInt("k", 7),
                    "present-but-unparseable is a ConfigException, NOT a silent fallback to the default");
        }

        @Test
        void getLongParsesAndFailsClosed() {
            assertEquals(9_000_000_000L, of(Map.of("k", "9000000000")).getLong("k", 1L));
            assertEquals(1L, of(Map.of()).getLong("k", 1L));
            assertThrows(ConfigException.class, () -> of(Map.of("k", "x")).getLong("k", 1L));
        }

        @Test
        void getBooleanIsStrict() {
            assertTrue(of(Map.of("k", "true")).getBoolean("k", false));
            assertTrue(of(Map.of("k", "TRUE")).getBoolean("k", false));
            assertFalse(of(Map.of("k", "false")).getBoolean("k", true));
            assertFalse(of(Map.of()).getBoolean("k", false), "absent -> default");
            assertThrows(ConfigException.class, () -> of(Map.of("k", "yes")).getBoolean("k", false),
                    "a non-boolean value fails closed under the strict accessor");
        }

        @Test
        void getRequiredStringThrowsOnAbsentOrBlank() {
            assertEquals("v", of(Map.of("k", "v")).getRequiredString("k"));
            assertThrows(ConfigException.class, () -> of(Map.of()).getRequiredString("k"));
            assertThrows(ConfigException.class, () -> of(Map.of("k", "   ")).getRequiredString("k"));
        }

        @Test
        void getListSplitsTrimsAndHandlesEmpty() {
            assertEquals(java.util.List.of("a", "b", "c"), of(Map.of("k", "a, b ,c")).getList("k"));
            assertEquals(java.util.List.of(), of(Map.of()).getList("k"), "absent -> empty list");
            assertEquals(java.util.List.of(), of(Map.of("k", "")).getList("k"), "present-but-empty -> empty list");
            assertEquals(java.util.List.of("only"), of(Map.of("k", "only")).getList("k"));
        }
    }

    @Nested
    class AnyLayerTrue {
        @Test
        void singleSourceIsJustTrueTest() {
            assertTrue(new MapConfigSource(Map.of("k", "true")).anyLayerTrue("k"));
            assertTrue(new MapConfigSource(Map.of("k", "TRUE")).anyLayerTrue("k"));
            assertFalse(new MapConfigSource(Map.of("k", "false")).anyLayerTrue("k"));
            assertFalse(new MapConfigSource(Map.of()).anyLayerTrue("k"));
        }

        /**
         * The byte-identity proof for the four OR-semantics flags: {@code anyLayerTrue} across
         * [system-property, environment] must equal the original expression
         * {@code Boolean.getBoolean(prop) || "true".equalsIgnoreCase(getenv(ALIAS))} for every
         * combination - INCLUDING the landmine {@code -Dx=false} with the env alias {@code =true}, which a
         * naive precedence resolution would flip to false.
         */
        @Test
        void orAcrossSystemPropertyAndEnvMatchesLegacyExpression() {
            String prop = "configd.security.allowColocatedSigningKey";
            String[] spValues = {null, "true", "false", "TRUE"};
            String[] envValues = {null, "true", "false"};
            try {
                for (String sp : spValues) {
                    for (String ev : envValues) {
                        if (sp == null) {
                            System.clearProperty(prop);
                        } else {
                            System.setProperty(prop, sp);
                        }
                        EnvConfigSource env = ev == null
                                ? new EnvConfigSource(Map.of())
                                : new EnvConfigSource(Map.of("CONFIGD_ALLOW_COLOCATED_SIGNING_KEY", ev));
                        LayeredConfigSource layered = LayeredConfigSource.of(new SystemPropertyConfigSource(), env);

                        boolean legacy = Boolean.parseBoolean(sp) || "true".equalsIgnoreCase(ev);
                        assertEquals(legacy, layered.anyLayerTrue(prop),
                                () -> "anyLayerTrue must equal the legacy OR expression for sp=" + sp + " env=" + ev);
                    }
                }
                // Spotlight the landmine explicitly.
                System.setProperty(prop, "false");
                LayeredConfigSource landmine = LayeredConfigSource.of(new SystemPropertyConfigSource(),
                        new EnvConfigSource(Map.of("CONFIGD_ALLOW_COLOCATED_SIGNING_KEY", "true")));
                assertTrue(landmine.anyLayerTrue(prop),
                        "-Dx=false with env alias =true is TRUE (OR), which precedence would have flipped to false");
            } finally {
                System.clearProperty(prop);
            }
        }
    }

    @Test
    void systemAmbientReadsPropertiesLive() {
        String key = "configd.test.ambient." + System.nanoTime();
        try {
            System.setProperty(key, "5");
            assertEquals(5, ConfigSource.system().getInt(key, 0), "the ambient system() source reads properties live");
        } finally {
            System.clearProperty(key);
        }
    }
}
