package io.configd.server.fanout;

import io.configd.common.auth.Principal;

import java.util.Objects;


record EdgeAuthenticated(Principal principal) {

    EdgeAuthenticated {
        Objects.requireNonNull(principal, "principal");
    }
}
