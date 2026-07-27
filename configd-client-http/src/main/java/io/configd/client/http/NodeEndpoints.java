package io.configd.client.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Operator-provided NodeId -> HTTP api-endpoint map (no wire topology discovery). No wire topology discovery.
 * This is the anti-SSRF trust boundary: X-Leader-Hint is bare NodeId resolved only through this map,
 * so a forged hint cannot steer the client to attacker-chosen address. Unresolvable hint = hintless 503.
 */
public final class NodeEndpoints {

    private final Map<Integer, URI> byNodeId; // may be empty (bare-entries shape)
    private final List<URI> entries;          // always non-empty (round-robin entry points)

    private NodeEndpoints(Map<Integer, URI> byNodeId, List<URI> entries) {
        this.byNodeId = byNodeId;
        this.entries = entries;
    }

    public static NodeEndpoints of(URI... entries) {
        if (entries.length == 0) {
            throw new IllegalArgumentException("at least one endpoint is required");
        }
        List<URI> normalized = new ArrayList<>(entries.length);
        for (URI e : entries) {
            normalized.add(origin(e));
        }
        return new NodeEndpoints(Map.of(), List.copyOf(normalized));
    }

    public static NodeEndpoints ofMap(Map<Integer, URI> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("at least one node is required");
        }
        Map<Integer, URI> copy = new LinkedHashMap<>();
        List<URI> entryList = new ArrayList<>(nodes.size());
        nodes.forEach((id, uri) -> {
            URI o = origin(Objects.requireNonNull(uri, "uri for node " + id));
            copy.put(id, o);
            entryList.add(o);
        });
        return new NodeEndpoints(Map.copyOf(copy), List.copyOf(entryList));
    }

    public Optional<URI> resolve(int nodeId) {
        return Optional.ofNullable(byNodeId.get(nodeId));
    }

    public List<URI> entries() {
        return entries;
    }

    private static URI origin(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("endpoint URI must be absolute with a host: " + uri);
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }
}
