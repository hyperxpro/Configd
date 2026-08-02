package io.configd.client.http;

import io.configd.client.AuthFailedException;
import io.configd.client.BadRequestException;
import io.configd.client.ConfigdException;
import io.configd.client.ForbiddenException;
import io.configd.client.IndeterminateException;
import io.configd.client.Sanitize;
import io.configd.client.UnavailableException;
import io.configd.common.auth.Credential;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;

/**
 * Unary routing and retry engine: follows X-Leader-Hint (once, hop<2), backs off on hintless 503, honors Retry-After,
 * treats 504/mutation-timeout/other-5xx as indeterminate (retry-to-definite). Anti-SSRF: hint is bare NodeId
 * resolved only through operator NodeEndpoints map. Fresh replay stamp per attempt (load-bearing for retry).
 */
final class LeaderRouter {

    private static final int MAX_HOPS = 1;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final NodeEndpoints endpoints;
    private final io.configd.client.CredentialSource credentialSource; // nullable = mTLS / no-auth
    private final io.configd.client.RetryPolicy retryPolicy;
    private final ReplayGuardSigner replaySigner;                      // nullable = replay disabled
    private final Duration requestTimeout;

    LeaderRouter(HttpClient httpClient, NodeEndpoints endpoints,
                 io.configd.client.CredentialSource credentialSource, io.configd.client.RetryPolicy retryPolicy,
                 ReplayGuardSigner replaySigner, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.endpoints = endpoints;
        this.credentialSource = credentialSource;
        this.retryPolicy = retryPolicy;
        this.replaySigner = replaySigner;
        this.requestTimeout = requestTimeout;
    }

    record Request(String method, String pathAndQuery, byte[] body, boolean isMutation, boolean configMutation) {
    }

    HttpResponse<byte[]> execute(Request request) {
        int maxAttempts = retryPolicy.maxAttempts();
        int entryIdx = 0;
        URI base = endpoints.entries().get(0);
        int hop = 0;
        boolean sawIndeterminate = false;
        int lastStatus = -1;

        for (int attempt = 1; ; attempt++) {
            HttpResponse<byte[]> resp;
            try {
                resp = send(base, request);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (request.isMutation()) {
                    sawIndeterminate = true;
                }
                if (attempt >= maxAttempts) {
                    throw exhausted(sawIndeterminate, lastStatus, request, e);
                }
                sleep(retryPolicy.backoff(attempt));
                base = endpoints.entries().get(entryIdx = next(entryIdx));
                hop = 0;
                continue;
            }

            lastStatus = resp.statusCode();
            Function<String, Optional<String>> header = name -> resp.headers().firstValue(name);
            HttpStatusClassifier.Decision decision = HttpStatusClassifier.classify(
                    resp.statusCode(), header, request.isMutation(), request.configMutation(), replaySigner != null);

            switch (decision) {
                case SUCCESS, NOT_FOUND -> {
                    return resp;
                }
                case FORBIDDEN -> throw new ForbiddenException(
                        "forbidden (403) for " + request.method() + " " + request.pathAndQuery(), serverText(resp));
                case BAD_REQUEST -> throw new BadRequestException(
                        "bad request (" + resp.statusCode() + ") for " + request.method() + " "
                                + request.pathAndQuery(), serverText(resp));
                case REAUTH -> throw new AuthFailedException(
                        "authentication required (401) for " + request.method() + " " + request.pathAndQuery());
                case FOLLOW_HINT -> {
                    Optional<URI> hinted = resolveHint(resp);
                    if (hinted.isPresent() && hop < MAX_HOPS) {
                        base = hinted.get();
                        hop++; // follow immediately (no backoff), bounded to one hop
                        continue;
                    }
                    if (attempt >= maxAttempts) {
                        throw exhausted(sawIndeterminate, lastStatus, request, null);
                    }
                    sleep(retryPolicy.backoff(attempt));
                    base = endpoints.entries().get(entryIdx = next(entryIdx));
                    hop = 0;
                }
                case RETRY_SAME, FRESH_STAMP -> {
                    // Hintless 503 / transient read 5xx (RETRY_SAME): back off and rotate endpoints. 409 /
                    // replay-401 (FRESH_STAMP): just retry -- a fresh stamp is minted automatically on the
                    // next send.
                    if (attempt >= maxAttempts) {
                        throw exhausted(sawIndeterminate, lastStatus, request, null);
                    }
                    sleep(retryPolicy.backoff(attempt));
                    if (decision == HttpStatusClassifier.Decision.RETRY_SAME) {
                        base = endpoints.entries().get(entryIdx = next(entryIdx));
                    }
                    hop = 0;
                }
                case RETRY_AFTER -> {
                    if (attempt >= maxAttempts) {
                        throw exhausted(sawIndeterminate, lastStatus, request, null);
                    }
                    sleep(retryAfter(resp));
                    hop = 0;
                }
                case INDETERMINATE -> {
                    sawIndeterminate = true;
                    if (attempt >= maxAttempts) {
                        throw indeterminate(request, null);
                    }
                    sleep(retryPolicy.backoff(attempt));
                    base = endpoints.entries().get(entryIdx = next(entryIdx));
                    hop = 0;
                }
            }
        }
    }

    private HttpResponse<byte[]> send(URI base, Request request) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(request.pathAndQuery()))
                .timeout(requestTimeout);
        byte[] body = request.body();
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        b.method(request.method(), publisher);
        applyAuthorization(b);
        if (replaySigner != null && request.isMutation()) {
            ReplayGuardSigner.Stamp stamp = replaySigner.stamp(); // fresh per attempt
            b.header(ReplayGuardSigner.TIMESTAMP_HEADER, stamp.timestamp());
            b.header(ReplayGuardSigner.NONCE_HEADER, stamp.nonce());
        }
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Applies the credential as an {@code Authorization} header (mTLS presents its identity at the TLS layer). */
    private void applyAuthorization(HttpRequest.Builder b) {
        if (credentialSource == null) {
            return;
        }
        Credential credential = credentialSource.provide().credential();
        switch (credential) {
            case Credential.BearerToken bt -> b.header("Authorization", "Bearer " + bt.token());
            case Credential.BasicCredential bc -> {
                String userPass = bc.username() + ":" + new String(bc.password());
                b.header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8)));
                bc.wipeSecret();
            }
            case Credential.ClientCertificate ignored -> {
                // mTLS: the identity is the TLS client certificate, presented by the SSLContext -- no header needed.
            }
        }
    }

    private Optional<URI> resolveHint(HttpResponse<byte[]> resp) {
        Optional<String> raw = resp.headers().firstValue(HttpStatusClassifier.LEADER_HINT_HEADER);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return endpoints.resolve(Integer.parseInt(raw.get().trim())); // anti-SSRF: resolved only through the operator map
        } catch (NumberFormatException e) {
            return Optional.empty(); // a non-numeric hint is unusable, so treat it as hintless
        }
    }

    private Duration retryAfter(HttpResponse<byte[]> resp) {
        Optional<String> raw = resp.headers().firstValue(HttpStatusClassifier.RETRY_AFTER_HEADER);
        if (raw.isEmpty()) {
            return retryPolicy.backoff(1);
        }
        try {
            long seconds = Long.parseLong(raw.get().trim());
            Duration d = Duration.ofSeconds(Math.max(0, seconds));
            return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
        } catch (NumberFormatException e) {
            return retryPolicy.backoff(1); // an HTTP-date Retry-After is not supported; fall back to the backoff policy
        }
    }

    private ConfigdException exhausted(boolean sawIndeterminate, int lastStatus, Request request, Throwable cause) {
        if (sawIndeterminate) {
            return indeterminate(request, cause);
        }
        if (lastStatus == 401) {
            return new AuthFailedException("authentication still failing after retries for " + request.method()
                    + " " + request.pathAndQuery());
        }
        return new UnavailableException("control-plane request did not complete within "
                + retryPolicy.maxAttempts() + " attempts (" + request.method() + " " + request.pathAndQuery()
                + (lastStatus > 0 ? ", last status " + lastStatus : "") + ")", cause);
    }

    private IndeterminateException indeterminate(Request request, Throwable cause) {
        return new IndeterminateException("write outcome unknown after retries (" + request.method() + " "
                + request.pathAndQuery() + "); the write MAY have committed — retry-to-definite, do NOT read-"
                + "modify-write across it", cause);
    }

    private static String serverText(HttpResponse<byte[]> resp) {
        return Sanitize.message(new String(resp.body(), StandardCharsets.UTF_8));
    }

    private int next(int idx) {
        return (idx + 1) % endpoints.entries().size();
    }

    private static void sleep(Duration d) {
        try {
            long ms = d.toMillis();
            if (ms > 0) {
                Thread.sleep(ms);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("interrupted during retry backoff", e);
        }
    }
}
