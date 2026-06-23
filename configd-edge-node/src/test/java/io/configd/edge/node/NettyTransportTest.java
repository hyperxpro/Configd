package io.configd.edge.node;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.uring.IoUring;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three-tier transport selector (M1.2, ADR-0043 / netty42-api.md §1). Pins: a coherent
 * (factory, channel-class) triple per tier; the {@code nio} floor is always available; an
 * unknown forced tier and a forced-but-unavailable tier both fail loud (no silent downgrade — the
 * property that keeps an "we ran on io_uring/epoll" claim honest). The CI gate additionally forces
 * the {@code nio} (and, where available, {@code epoll}) fallback through the whole edge test suite.
 */
class NettyTransportTest {

    private String saved;

    @BeforeEach
    void clearProp() {
        saved = System.getProperty(NettyTransport.PROP);
        System.clearProperty(NettyTransport.PROP);
    }

    @AfterEach
    void restoreProp() {
        if (saved == null) {
            System.clearProperty(NettyTransport.PROP);
        } else {
            System.setProperty(NettyTransport.PROP, saved);
        }
    }

    @Test
    void autoSelectResolvesACoherentTriple() {
        NettyTransport.Selection s = NettyTransport.select();
        assertNotNull(s.tier());
        assertNotNull(s.ioHandlerFactory());
        assertNotNull(s.serverChannelClass());
        assertTrue(channelMatchesTier(s),
                "channel " + s.serverChannelClass().getSimpleName() + " must pair with tier " + s.tier());
    }

    @Test
    void autoSelectPrefersBestAvailableTier() {
        // io_uring → epoll → nio: the auto pick must be the highest available tier.
        String expected = IoUring.isAvailable() ? "io_uring" : Epoll.isAvailable() ? "epoll" : "nio";
        assertEquals(expected, NettyTransport.select().tier());
    }

    @Test
    void forcedNioIsAlwaysAvailable() {
        System.setProperty(NettyTransport.PROP, "nio");
        NettyTransport.Selection s = NettyTransport.select();
        assertEquals("nio", s.tier());
        assertEquals("NioServerSocketChannel", s.serverChannelClass().getSimpleName());
    }

    @Test
    void forcedEpollResolvesWhenAvailable() {
        Assumptions.assumeTrue(Epoll.isAvailable(), "epoll unavailable on this host");
        System.setProperty(NettyTransport.PROP, "epoll");
        NettyTransport.Selection s = NettyTransport.select();
        assertEquals("epoll", s.tier());
        assertEquals("EpollServerSocketChannel", s.serverChannelClass().getSimpleName());
    }

    @Test
    void forcedIoUringResolvesWhenAvailable() {
        Assumptions.assumeTrue(IoUring.isAvailable(), "io_uring unavailable on this host");
        System.setProperty(NettyTransport.PROP, "io_uring");
        NettyTransport.Selection s = NettyTransport.select();
        assertEquals("io_uring", s.tier());
        assertEquals("IoUringServerSocketChannel", s.serverChannelClass().getSimpleName());
    }

    @Test
    void unknownForcedTierFailsLoud() {
        System.setProperty(NettyTransport.PROP, "kqueue");
        assertThrows(IllegalArgumentException.class, NettyTransport::select);
    }

    @Test
    void forcedIoUringFailsLoudWhenUnavailable() {
        Assumptions.assumeFalse(IoUring.isAvailable(),
                "io_uring available here — the unavailable-fail-loud path runs on CI runners without it");
        System.setProperty(NettyTransport.PROP, "io_uring");
        assertThrows(IllegalStateException.class, NettyTransport::select);
    }

    @Test
    void availabilityReportNamesAllTiers() {
        String r = NettyTransport.availabilityReport();
        assertTrue(r.contains("io_uring="), r);
        assertTrue(r.contains("epoll="), r);
        assertTrue(r.contains("nio=true"), r);
    }

    private static boolean channelMatchesTier(NettyTransport.Selection s) {
        String cn = s.serverChannelClass().getSimpleName().toLowerCase(Locale.ROOT);
        return switch (s.tier()) {
            case "io_uring" -> cn.contains("uring");
            case "epoll" -> cn.contains("epoll");
            case "nio" -> cn.contains("nio");
            default -> false;
        };
    }
}
