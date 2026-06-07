package io.configd.linz.client;

import io.configd.linz.cluster.ClusterNode;
import io.configd.linz.history.Op;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Concurrent client + faithful outcome mapper. Uses the JDK {@link HttpClient}
 * over the real HTTP API and maps each observation to the checker-neutral
 * {@link Op.Status} exactly as the design's §7 table requires:
 *
 * <ul>
 *   <li>PUT/DELETE 200 Accepted -> {@code INFO} (ack != commit, may or may not have committed)</li>
 *   <li>PUT/DELETE timeout / conn-refused / 5xx-other -> {@code INFO} (may have committed)</li>
 *   <li>PUT/DELETE 503 NotLeader (no usable hint) / 400 / 403 / 429 -> {@code FAIL} (rejected, did not happen)</li>
 *   <li>linearizable GET 200 -> {@code OK} read of the body; 404 -> {@code OK} read of "" (absent)</li>
 *   <li>linearizable GET 503 (flaky / not leader) or timeout -> {@code INFO} (indeterminate read, dropped)</li>
 * </ul>
 *
 * <p>The captured {@code callNs}/{@code retNs} bracket the whole client-visible
 * operation (including any leader-hint hop), from one monotonic clock.
 */
public final class ConfigClient {

    private static final String CONFIG_PATH = "/v1/config/";

    private final HttpClient http;
    private final Duration requestTimeout;
    private volatile int suspectedLeaderId = -1;

    public ConfigClient() {
        this(Duration.ofSeconds(3));
    }

    public ConfigClient(Duration requestTimeout) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.requestTimeout = requestTimeout;
    }

    /** Result of one client operation, ready to hand to the recorder. */
    public record OpResult(Op.Status status, String value, long callNs, long retNs) {}

    public int suspectedLeaderId() {
        return suspectedLeaderId;
    }

    // ---- writes -----------------------------------------------------------

    /**
     * PUT {@code token} as the value of {@code key}. Tries {@code target}; on a
     * 503 with a leader hint, follows it once to the hinted node. Records the
     * token as the op value.
     */
    public OpResult put(ClusterNode target, List<ClusterNode> all, String key, String token) {
        return write(target, all, key, token, token);
    }

    /** DELETE {@code key} (a write of bottom). */
    public OpResult delete(ClusterNode target, List<ClusterNode> all, String key) {
        return write(target, all, key, null, "");
    }

    private OpResult write(ClusterNode target, List<ClusterNode> all, String key, String putBody, String value) {
        long call = System.nanoTime();
        ClusterNode node = target;
        for (int hop = 0; hop < 2; hop++) {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(node.apiBase() + CONFIG_PATH + key))
                    .timeout(requestTimeout);
            HttpRequest req = (putBody == null)
                    ? b.DELETE().build()
                    : b.PUT(HttpRequest.BodyPublishers.ofString(putBody, StandardCharsets.UTF_8)).build();
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) {
                    suspectedLeaderId = node.id();
                    return new OpResult(Op.Status.INFO, value, call, System.nanoTime()); // ack != commit
                }
                if (code == 503) {
                    Optional<Integer> hint = leaderHint(resp);
                    if (hint.isPresent()) {
                        suspectedLeaderId = hint.get();
                        ClusterNode next = byId(all, hint.get());
                        if (next != null && hop == 0) {
                            node = next; // follow the hint once
                            continue;
                        }
                    }
                    return new OpResult(Op.Status.FAIL, value, call, System.nanoTime()); // definite NotLeader
                }
                // 400 / 403 / 429 and other definite rejections
                return new OpResult(Op.Status.FAIL, value, call, System.nanoTime());
            } catch (Exception e) {
                // timeout / connection refused (node killed) / reset: may have committed
                return new OpResult(Op.Status.INFO, value, call, System.nanoTime());
            }
        }
        return new OpResult(Op.Status.FAIL, value, call, System.nanoTime());
    }

    // ---- linearizable read ------------------------------------------------

    /**
     * Linearizable GET of {@code key} against {@code target} (no hint-following — a
     * linearizable-read 503 has no leader hint). 200 -> OK read of the body;
     * 404 -> OK read of "" (absent); 503/timeout -> INFO (indeterminate, dropped).
     */
    public OpResult linRead(ClusterNode target, String key) {
        long call = System.nanoTime();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(target.apiBase() + CONFIG_PATH + key + "?consistency=linearizable"))
                .timeout(requestTimeout)
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            long ret = System.nanoTime();
            if (code == 200) {
                return new OpResult(Op.Status.OK, resp.body(), call, ret);
            }
            if (code == 404) {
                return new OpResult(Op.Status.OK, "", call, ret); // absent = bottom
            }
            return new OpResult(Op.Status.INFO, "", call, ret); // 503: indeterminate read
        } catch (Exception e) {
            return new OpResult(Op.Status.INFO, "", call, System.nanoTime());
        }
    }

    /**
     * Retrying linearizable read: the lin-read is flaky (~150 ms ReadIndex timeout),
     * so retry up to {@code attempts} times to get a definite OK; if none, the last
     * INFO is returned. Used by the discrimination scenarios that need a confirmed
     * read-back. The returned interval still brackets the whole effort.
     */
    public OpResult linReadConfirm(ClusterNode target, String key, int attempts) {
        long call = System.nanoTime();
        OpResult last = null;
        for (int i = 0; i < attempts; i++) {
            OpResult r = linRead(target, key);
            if (r.status() == Op.Status.OK) {
                return new OpResult(Op.Status.OK, r.value(), call, r.retNs());
            }
            last = r;
        }
        return last == null ? new OpResult(Op.Status.INFO, "", call, System.nanoTime())
                : new OpResult(Op.Status.INFO, "", call, last.retNs());
    }

    /**
     * Default (stale) GET against a specific node — reads that node's local applied
     * state directly (no ReadIndex). Returns the body, or {@code null} on 404/error.
     * Used only for harness warm-up / liveness checks, never recorded as a
     * linearizable observation.
     */
    public String defaultGet(ClusterNode node, String key) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(node.apiBase() + CONFIG_PATH + key))
                .timeout(requestTimeout)
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- leader discovery -------------------------------------------------

    /**
     * Discovers the current leader via a throwaway probe PUT (NOT recorded into any
     * history — a reserved key). Returns the leader id or -1.
     */
    public int probeLeader(List<ClusterNode> all) {
        for (ClusterNode n : all) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(n.apiBase() + CONFIG_PATH + "__leader_probe__"))
                    .timeout(requestTimeout)
                    .PUT(HttpRequest.BodyPublishers.ofString("probe"))
                    .build();
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    suspectedLeaderId = n.id();
                    return n.id();
                }
                Optional<Integer> hint = leaderHint(resp);
                if (hint.isPresent()) {
                    suspectedLeaderId = hint.get();
                    return hint.get();
                }
            } catch (Exception ignore) {
                // try next node
            }
        }
        return -1;
    }

    private static Optional<Integer> leaderHint(HttpResponse<?> resp) {
        return resp.headers().firstValue("X-Leader-Hint").map(s -> {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        });
    }

    private static ClusterNode byId(List<ClusterNode> all, int id) {
        for (ClusterNode n : all) {
            if (n.id() == id) {
                return n;
            }
        }
        return null;
    }
}
