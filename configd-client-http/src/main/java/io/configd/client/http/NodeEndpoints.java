package io.configd.client.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The operator-provided {@code NodeId → HTTP api-endpoint} map (§05 R3). There is <b>no</b> wire topology
 * discovery: a driver is configured with the nodes it may be redirected to, resolving each hinted numeric
 * {@code NodeId} to that node's control-plane HTTP base URI (the {@code --api-port}, default 8080 — NOT the
 * Raft {@code --bind-port}, R3-1). This map is the redirect <b>trust boundary</b> (§05 R8): it must contain
 * only same-trust-domain nodes, and it is the <b>anti-SSRF</b> invariant — a {@code X-Leader-Hint} is a bare
 * {@code NodeId} resolved only through this map, so a forged hint can never steer the client to an
 * attacker-chosen address (R2-2). A hint the map does not contain degrades to a <b>hintless</b> {@code 503}
 * (R3-3): back off and retry the known entries, never chase an unknown id.
 *
 * <p>Two shapes: {@link #of} (bare entry URIs, no id map — every hint is unresolvable ⇒ hintless; correct for
 * a single-node / single-entry client at N=1) and {@link #ofMap} (a full {@code NodeId → URI} map, so hints
 * resolve and the client follows leaders at N &gt; 1). Base URIs are normalized to end at the origin (scheme
 * + authority); the {@code /v1/config/...} path is appended per request.
 */
public final class NodeEndpoints {

    private final Map<Integer, URI> byNodeId; // may be empty (bare-entries shape)
    private final List<URI> entries;          // always non-empty (round-robin entry points)

    private NodeEndpoints(Map<Integer, URI> byNodeId, List<URI> entries) {
        this.byNodeId = byNodeId;
        this.entries = entries;
    }

    /** Entry base URIs with no id map: hints never resolve (⇒ hintless), the correct shape for a single node. */
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

    /** A full {@code NodeId → base URI} map: hints resolve, so the client follows per-shard leaders (R5). */
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

    /** Resolves a hinted {@code NodeId} to its base URI, or empty when the map does not contain it (⇒ hintless). */
    public Optional<URI> resolve(int nodeId) {
        return Optional.ofNullable(byNodeId.get(nodeId));
    }

    /** The configured entry base URIs (round-robin start points; a hintless {@code 503} retries among these). */
    public List<URI> entries() {
        return entries;
    }

    /** Normalizes a URI to its origin (scheme://authority), dropping any path/query/fragment. */
    private static URI origin(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("endpoint URI must be absolute with a host: " + uri);
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }
}
