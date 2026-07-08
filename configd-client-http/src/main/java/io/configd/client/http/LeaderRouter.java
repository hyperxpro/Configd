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
 * The unary control-plane routing + retry engine (§05). It sends one logical request, following the advisory
 * {@code X-Leader-Hint} (follow-once, {@code hop < 2}, then back off — R4-3), backing off + retrying a hintless
 * {@code 503} (the REQUIRED N = 1 election loop — R4-2), honoring {@code Retry-After} on a {@code 429}, and
 * classifying {@code 504}/mutation-timeout/other-mutation-5xx as <b>indeterminate</b> (retry-to-definite; on
 * budget exhaustion it surfaces {@link IndeterminateException}, never a false definite failure — R6-3, §04
 * D4-8). Anti-SSRF (R2-2/R8): a hint is a bare numeric {@code NodeId} resolved <b>only</b> through the operator
 * {@link NodeEndpoints} map; an unresolvable hint degrades to hintless (never a wire-supplied address). The
 * credential (and, when enabled, a <b>fresh</b> replay stamp per attempt — R6-4) is (re)applied on every send,
 * including a followed hop (R8-1), always over the same TLS/mTLS (R8-3).
 *
 * <p>Synchronous/blocking by design — the {@link ConfigdHttpClient} runs it on an executor for its
 * {@code CompletableFuture} surface and calls it directly for the blocking facade.
 */
final class LeaderRouter {

    private static final int MAX_HOPS = 1; // follow a hint at most once per logical attempt (R4-3)
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

    /**
     * One request spec. {@code isMutation} governs the indeterminate/5xx and replay-401 handling;
     * {@code configMutation} (a config PUT/DELETE, NOT the transfer route) governs the 409 branch — replayed
     * nonce vs the transfer route's precondition-conflict (§04 D2-2a).
     */
    record Request(String method, String pathAndQuery, byte[] body, boolean isMutation, boolean configMutation) {
    }

    /**
     * Executes the request under the §05 retry contract; returns the raw response for a {@code 200}/{@code 404}
     * (the caller parses it), or throws the typed §07 reaction ({@link AuthFailedException} /
     * {@link ForbiddenException} / {@link BadRequestException} / {@link IndeterminateException} /
     * {@link UnavailableException}).
     */
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
                // A transport failure (connect refusal, drop, timeout). On a MUTATION it is indeterminate (the
                // write MAY have landed, §04 D4-8); on a read it is a re-read. Either way retry within budget.
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
                    // Hintless 503 / transient read 5xx (RETRY_SAME): back off + rotate. 409 / replay-401
                    // (FRESH_STAMP): retry — a fresh stamp is minted on the next send automatically.
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

    // -----------------------------------------------------------------------

    private HttpResponse<byte[]> send(URI base, Request request) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(request.pathAndQuery()))
                .timeout(requestTimeout);
        byte[] body = request.body();
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        b.method(request.method(), publisher);
        applyAuthorization(b);
        if (replaySigner != null && request.isMutation()) {
            ReplayGuardSigner.Stamp stamp = replaySigner.stamp(); // FRESH per attempt (R6-4)
            b.header(ReplayGuardSigner.TIMESTAMP_HEADER, stamp.timestamp());
            b.header(ReplayGuardSigner.NONCE_HEADER, stamp.nonce());
        }
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Applies the credential as an {@code Authorization} header (mTLS presents its identity at the TLS layer). */
    private void applyAuthorization(HttpRequest.Builder b) {
        if (credentialSource == null) {
            return; // mTLS / no-auth
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
                // mTLS: the identity is the TLS client certificate, presented by the SSLContext — no header.
            }
        }
    }

    private Optional<URI> resolveHint(HttpResponse<byte[]> resp) {
        Optional<String> raw = resp.headers().firstValue(HttpStatusClassifier.LEADER_HINT_HEADER);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return endpoints.resolve(Integer.parseInt(raw.get().trim())); // anti-SSRF: map-only resolution (R2-2)
        } catch (NumberFormatException e) {
            return Optional.empty(); // a non-numeric hint is unusable → treat as hintless
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
            return retryPolicy.backoff(1); // an HTTP-date Retry-After is unsupported in v1; fall back to backoff
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
