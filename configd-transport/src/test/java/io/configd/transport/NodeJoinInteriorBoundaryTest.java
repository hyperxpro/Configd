package io.configd.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Gate-4 interior boundary: the Raft interior wire admits <b>no credential-bearing frame</b>, so a client
 * bearer / HTTP-Basic / OIDC token has NO path to consensus. The interior transport is binary mTLS with
 * {@code setNeedClientAuth(true)}; the AUTH / REFRESH_AUTH frames of the auth arc live on the EDGE
 * ({@code EdgeFrameCodec}) plane, a different wire on a different transport.
 *
 * <p>This is why the OIDC node-claim (finding R5) and HTTP-Basic node-principal (R6) markers are
 * <b>dormant, fail-closed</b> today rather than deferred: they are unreachable-by-construction - there is
 * no interior frame that could carry the token to check the marker against. They activate only if/when a
 * token-bearing interior auth frame is added (a named RFC forward extension); this test guards that a new
 * interior message type is not silently introduced without that gate.
 */
class NodeJoinInteriorBoundaryTest {

    @Test
    void interiorWireHasNoCredentialBearingFrame() {
        for (MessageType type : MessageType.values()) {
            String name = type.name();
            assertFalse(
                    name.contains("AUTH") || name.contains("TOKEN") || name.contains("CREDENTIAL")
                            || name.contains("BEARER") || name.contains("BASIC") || name.contains("OIDC"),
                    "the Raft interior MessageType " + type + " must not carry a client credential: a "
                            + "bearer/Basic/OIDC token has no wire frame that reaches consensus (mTLS-only "
                            + "interior). If a token-bearing interior frame is added, the OIDC node-claim / "
                            + "Basic node-principal gate (finding R5/R6) must be wired at the same time.");
        }
    }
}
