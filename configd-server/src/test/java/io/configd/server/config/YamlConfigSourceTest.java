package io.configd.server.config;

import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;
import io.configd.common.config.EnvConfigSource;
import io.configd.common.config.LayeredConfigSource;
import io.configd.common.config.SystemPropertyConfigSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlConfigSourceTest {

    @Test
    void flattensNestedMapsToDottedKeys() {
        ConfigSource cfg = YamlConfigSource.fromYaml("""
                configd:
                  raft:
                    shardCount: 4
                    encryption:
                      enabled: true
                """, "test");
        assertEquals(4, cfg.getInt("configd.raft.shardCount", 1));
        assertTrue(cfg.getBoolean("configd.raft.encryption.enabled", false));
        assertEquals(Optional.of("4"), cfg.getString("configd.raft.shardCount"));
    }

    @Test
    void sequenceFlattensToCommaJoinedListReadableByGetList() {
        ConfigSource cfg = YamlConfigSource.fromYaml("""
                configd:
                  strongReadPrefixes:
                    - secure/
                    - keys/
                    - certs/
                """, "test");
        assertEquals(List.of("secure/", "keys/", "certs/"), cfg.getList("configd.strongReadPrefixes"));
        assertEquals(Optional.of("secure/,keys/,certs/"), cfg.getString("configd.strongReadPrefixes"));
    }

    @Test
    void scalarsBecomeStrings() {
        ConfigSource cfg = YamlConfigSource.fromYaml("""
                a: hello
                n: 7
                b: false
                """, "test");
        assertEquals("hello", cfg.getRequiredString("a"));
        assertEquals(7, cfg.getInt("n", 0));
        assertFalse(cfg.getBoolean("b", true));
    }

    @Test
    void emptyDocumentIsAValidEmptyConfig() {
        ConfigSource cfg = YamlConfigSource.fromYaml("", "empty");
        assertTrue(cfg.getString("anything").isEmpty());
        assertEquals(Set.of(), cfg.keysWithPrefix("configd."));
    }

    @Test
    void keysWithPrefixEnumerates() {
        ConfigSource cfg = YamlConfigSource.fromYaml("""
                configd:
                  a:
                    x: 1
                    y: 2
                  b:
                    z: 3
                """, "test");
        assertEquals(Set.of("configd.a.x", "configd.a.y"), cfg.keysWithPrefix("configd.a."));
    }

    @Test
    void fromFileLoadsAndFailsClosedOnMissing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("configd.yaml");
        Files.writeString(file, "configd:\n  raft:\n    shardCount: 8\n", StandardCharsets.UTF_8);
        assertEquals(8, YamlConfigSource.fromFile(file).getInt("configd.raft.shardCount", 1));

        assertThrows(ConfigException.class, () -> YamlConfigSource.fromFile(dir.resolve("nope.yaml")),
                "an unreadable / missing config file fails the boot rather than starting on no config");
    }


    @Test
    void malformedYamlIsAConfigException() {
        assertThrows(ConfigException.class, () -> YamlConfigSource.fromYaml("a: [1, 2", "bad"));
    }

    @Test
    void nonMappingTopLevelIsAConfigException() {
        assertThrows(ConfigException.class, () -> YamlConfigSource.fromYaml("42", "scalar-top"));
        assertThrows(ConfigException.class, () -> YamlConfigSource.fromYaml("- a\n- b\n", "seq-top"));
    }

    @Test
    void arbitraryJavaTypesAreRejectedBySafeConstructor() {
        // SafeConstructor builds only plain Map/List/scalar - an unknown global tag has no constructor,
        // so no arbitrary class is ever instantiated from the document.
        assertThrows(ConfigException.class, () -> YamlConfigSource.fromYaml("evil: !!com.example.Gadget {}", "tag"));
    }

    @Test
    void aliasBombIsRejectedNotExpanded() {
        // A billion-laughs style alias bomb: 60 aliases to a collection anchor blow past
        // maxAliasesForCollections(50) and are rejected during load rather than exploding into memory.
        String aliases = "*a,".repeat(60);
        String bomb = "a: &a [1, 2]\nb: [" + aliases.substring(0, aliases.length() - 1) + "]\n";
        assertThrows(ConfigException.class, () -> YamlConfigSource.fromYaml(bomb, "bomb"));
    }

    @Test
    void deeplyNestedDocumentIsRejected() {
        // 60 levels of nesting exceeds nestingDepthLimit(50).
        String deep = "root: " + "[".repeat(60) + "]".repeat(60) + "\n";
        assertThrows(ConfigException.class, () -> YamlConfigSource.fromYaml(deep, "deep"));
    }


    @Test
    void systemPropertyOverridesYamlFile() {
        String key = "configd.test.yamlprec." + System.nanoTime();
        ConfigSource yaml = YamlConfigSource.fromYaml(key + ": from-yaml\n", "test");
        try {
            LayeredConfigSource layered = LayeredConfigSource.of(
                    new SystemPropertyConfigSource(), new EnvConfigSource(), yaml);
            assertEquals(Optional.of("from-yaml"), layered.getString(key), "YAML supplies the value when no -D is set");
            System.setProperty(key, "from-sysprop");
            assertEquals(Optional.of("from-sysprop"), layered.getString(key), "a -D override still wins over the YAML layer");
        } finally {
            System.clearProperty(key);
        }
    }
}
