package io.configd.client.edge;

import io.configd.client.Configd;
import io.configd.client.edge.session.EdgeConnectionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@link Configd} entry facade vends a {@link ConfigdEdgeClient} that shares the facade's scheduler, and
 * closing the facade closes the client it vended.
 */
@Timeout(30)
class ConfigdFacadeTest {

    @Test
    void facadeVendsEdgeClientAndClosesIt() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(MockEdgeServer.Conn::parkUntilClosed)) {
            ConfigdEdgeClient edge;
            try (Configd configd = Configd.builder()
                    .endpoint("127.0.0.1", server.port())
                    .allowPlaintext(true)
                    .build()) {
                edge = configd.edge();
                assertEquals(AuthMode.NO_AUTH, edge.authMode());
                edge.connectAndAuthenticate().get(10, TimeUnit.SECONDS);
                assertEquals(EdgeConnectionState.AUTHENTICATED, edge.state());
            }
            // The facade's close() closed the vended edge client.
            assertEquals(EdgeConnectionState.CLOSED, edge.state());
        }
    }
}
