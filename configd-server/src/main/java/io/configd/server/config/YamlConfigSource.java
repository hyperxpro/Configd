package io.configd.server.config;

import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


public final class YamlConfigSource implements ConfigSource {

    
    private static final int MAX_ALIASES_FOR_COLLECTIONS = 50;
    private static final int NESTING_DEPTH_LIMIT = 50;
    private static final int CODE_POINT_LIMIT = 4 * 1024 * 1024;

    private final Map<String, String> flat;

    private YamlConfigSource(Map<String, String> flat) {
        this.flat = Map.copyOf(flat);
    }

    
    public static YamlConfigSource fromFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in, path.toString());
        } catch (IOException e) {
            throw new ConfigException("cannot read config file: " + path, e);
        }
    }

    
    public static YamlConfigSource fromYaml(String content, String sourceLabel) {
        return parse(new StringReader(content), sourceLabel);
    }

    private static YamlConfigSource parse(InputStream in, String label) {
        return parse(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), label);
    }

    private static YamlConfigSource parse(Reader reader, String label) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(MAX_ALIASES_FOR_COLLECTIONS);
        options.setNestingDepthLimit(NESTING_DEPTH_LIMIT);
        options.setCodePointLimit(CODE_POINT_LIMIT);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        Object root;
        try {
            root = yaml.load(reader);
        } catch (YAMLException e) {
            // Covers malformed YAML and every resource-exhaustion guard (alias/nesting/size limits).
            throw new ConfigException("malformed or oversized YAML config: " + label + " (" + e.getMessage() + ")", e);
        }

        Map<String, String> flat = new HashMap<>();
        if (root == null) {
            // An empty document is a valid, empty config (no keys) - not an error.
            return new YamlConfigSource(flat);
        }
        if (!(root instanceof Map<?, ?> topLevel)) {
            throw new ConfigException(
                    "YAML config " + label + " must be a mapping of keys at the top level, got: "
                            + root.getClass().getSimpleName());
        }
        flatten("", topLevel, flat);
        return new YamlConfigSource(flat);
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                flatten(key, e.getValue(), out);
            }
        } else if (node instanceof List<?> list) {
            // A sequence collapses to a comma-joined scalar, the form getList() splits back apart.
            out.put(prefix, list.stream().map(String::valueOf).collect(Collectors.joining(",")));
        } else {
            out.put(prefix, node == null ? "" : String.valueOf(node));
        }
    }

    @Override
    public Optional<String> getString(String key) {
        return Optional.ofNullable(flat.get(key));
    }

    @Override
    public Set<String> keysWithPrefix(String prefix) {
        return flat.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .collect(Collectors.toUnmodifiableSet());
    }
}
