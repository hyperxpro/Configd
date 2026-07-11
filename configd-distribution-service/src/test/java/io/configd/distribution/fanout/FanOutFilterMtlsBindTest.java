package io.configd.distribution.fanout;

import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filtering must activate through the {@link FanOutConnectionDriver}'s mTLS identity rebind, the
 * production path. {@code bindIdentity} rebuilds the SUBSCRIBE with the verified cert principal
 * over mTLS (identity != {@code "plaintext"}); it must carry {@code acceptsFiltered} through, or
 * server-side filtering is silently inert on every real connection - the exact gap the core-level
 * tests miss because they drive {@code onSubscribe} / plaintext directly.
 */
class FanOutFilterMtlsBindTest {

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink out = new RecordingTransportSink();
    private final List<ErrorCode> teardowns = new ArrayList<>();

    private FanOutConnectionDriver driver(String identity) {
        FanOutConfig cfg = FanOutConfig.defaults().withServerSidePrefixFilter(true, Set.of("secure/"));
        ConfigSnapshot snap = new ConfigSnapshot(HamtMap.<String, VersionedValue>empty(), 0L, 0L);
        ReplaySource replay = new SnapshotReplaySource(() -> snap);
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        BiConsumer<ErrorCode, String> teardown = (c, m) -> teardowns.add(c);
        // A null authorizer admits the legacy SUBSCRIBE (auth-off), so the mTLS bindIdentity path runs.
        return new FanOutConnectionDriver(new FanOutBuffer(64), replay, out, cfg,
                FanOutSessionMetrics.NOOP, clock, gov, identity, teardown, null);
    }

    private EdgeFrame.SubscribeOk subscribeAndReadOk(FanOutConnectionDriver driver, boolean acceptsFiltered) {
        driver.onInboundFrame(new EdgeFrame.Subscribe(false, List.of("svc/"), 0L, -1L, "wire-id", acceptsFiltered));
        driver.drainInboundCommands(); // runs bindIdentity -> onSubscribe on the session thread
        return out.sentOfType(EdgeFrame.SubscribeOk.class).get(0);
    }

    @Test
    void mtlsIdentityRebindPreservesAcceptsFiltered() {
        // A verified (non-plaintext) cert identity: bindIdentity rebuilds the SUBSCRIBE. filtering must
        // still activate (SUBSCRIBE_OK.filtered == true == filterActive).
        EdgeFrame.SubscribeOk ok = subscribeAndReadOk(driver("cn=edge,ou=fleet"), true);
        assertTrue(ok.filtered(),
                "the mTLS identity rebind must carry acceptsFiltered so filtering activates in production");
        assertTrue(teardowns.isEmpty());
    }

    @Test
    void mtlsOptOutStaysUnfiltered() {
        // The same mTLS path with acceptsFiltered=false stays the byte-identical full-chain session.
        EdgeFrame.SubscribeOk ok = subscribeAndReadOk(driver("cn=edge,ou=fleet"), false);
        assertFalse(ok.filtered(), "a non-opting mTLS edge is not filtered");
    }

    @Test
    void plaintextIdentityAlsoActivatesFiltering() {
        // Over plaintext bindIdentity returns the wire frame unchanged; acceptsFiltered survives that path too.
        EdgeFrame.SubscribeOk ok = subscribeAndReadOk(driver("plaintext"), true);
        assertTrue(ok.filtered(), "the plaintext path also preserves acceptsFiltered");
    }
}
