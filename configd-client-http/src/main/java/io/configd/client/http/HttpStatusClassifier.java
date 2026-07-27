package io.configd.client.http;

import java.util.Optional;
import java.util.function.Function;

/**
 * Maps HTTP status + headers to driver reaction (Decision enum). Never branches on body (unescaped plaintext
 * under misleading application/json, may echo attacker input). Pure; LeaderRouter enacts decisions.
 */
final class HttpStatusClassifier {

    enum Decision {
        SUCCESS,
        NOT_FOUND,
        FOLLOW_HINT,
        RETRY_SAME,
        RETRY_AFTER,
        INDETERMINATE,
        FRESH_STAMP,
        REAUTH,
        FORBIDDEN,
        BAD_REQUEST
    }

    static final String LEADER_HINT_HEADER = "X-Leader-Hint";
    static final String FAIL_CLOSED_HEADER = "X-Fail-Closed";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private HttpStatusClassifier() {
    }

    static Decision classify(int status, Function<String, Optional<String>> header,
                             boolean isMutation, boolean configMutation, boolean replayEnabled) {
        if (status >= 200 && status < 300) {
            return Decision.SUCCESS;
        }
        return switch (status) {
            case 404 -> Decision.NOT_FOUND;
            case 400, 405 -> Decision.BAD_REQUEST;
            case 401 -> (replayEnabled && isMutation) ? Decision.FRESH_STAMP : Decision.REAUTH;
            case 403 -> Decision.FORBIDDEN;
            case 409 -> (replayEnabled && configMutation) ? Decision.FRESH_STAMP : Decision.BAD_REQUEST;
            case 429 -> Decision.RETRY_AFTER;
            case 503 -> header.apply(LEADER_HINT_HEADER).isPresent() ? Decision.FOLLOW_HINT : Decision.RETRY_SAME;
            case 504 -> Decision.INDETERMINATE;
            default -> {
                if (status >= 500) {
                    yield isMutation ? Decision.INDETERMINATE : Decision.RETRY_SAME;
                }
                yield Decision.BAD_REQUEST;
            }
        };
    }
}
