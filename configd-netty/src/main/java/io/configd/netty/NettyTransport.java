package io.configd.netty;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;

import java.util.Locale;

/**
 * The production three-tier Netty transport selector (ADR-0043 / charter §3.2;
 * {@code docs/netty-migration/netty42-api.md} §1).
 *
 * <p>Runtime-detects the best available transport in the order <b>io_uring → Epoll → NIO</b> and
 * returns a <em>coherent triple</em> — the {@link IoHandlerFactory} <b>and</b> the matching
 * {@link ServerChannel} class. In Netty 4.2 the event-loop group no longer implies the channel type
 * (one generic {@code MultiThreadIoEventLoopGroup} takes any {@code IoHandlerFactory}); pairing a
 * factory with the wrong channel class is the #1 4.2 migration bug. Resolving both together here is
 * the single place that pairing is decided.
 *
 * <p><b>io_uring is a performance tier, never a correctness dependency</b> (charter §3.2): its win
 * is syscall batching (validated in Phase V), and Epoll/NIO are the always-correct fallback that CI
 * runners — which frequently lack io_uring, and sometimes epoll — exercise. NIO (tier 3) is
 * pure-Java and available on every JVM/OS.
 *
 * <p><b>Override (CI-fallback proof, fail-loud).</b> {@code -Dconfigd.netty.transport=io_uring|epoll|nio}
 * forces a tier so CI can exercise the NIO and Epoll paths deterministically (and not depend on the
 * runner's kernel). Forcing a tier that is <em>unavailable</em> on the host is a startup error, not a
 * silent downgrade — a silent downgrade is how a "we ran on io_uring/epoll" claim becomes fiction.
 */
public final class NettyTransport {

    /** System property to force a transport tier (fail-loud if unavailable). */
    public static final String PROP = "configd.netty.transport";

    private NettyTransport() {
    }

    /**
     * The resolved transport: a tier name plus the coherent ({@link IoHandlerFactory},
     * server-channel class) pair to bootstrap with. The same factory instance is shared by the boss
     * and worker groups (it is a factory of per-thread handlers).
     */
    public record Selection(String tier,
                            IoHandlerFactory ioHandlerFactory,
                            Class<? extends ServerChannel> serverChannelClass) {
    }

    /** Resolves the transport per the override / io_uring→Epoll→NIO order. */
    public static Selection select() {
        String forced = System.getProperty(PROP);
        if (forced != null) {
            return forced(forced);
        }
        if (IoUring.isAvailable()) {
            return ioUring();
        }
        if (Epoll.isAvailable()) {
            return epoll();
        }
        return nio();
    }

    private static Selection forced(String forced) {
        return switch (forced.toLowerCase(Locale.ROOT)) {
            case "io_uring", "iouring", "uring" -> {
                if (!IoUring.isAvailable()) {
                    throw unavailable("io_uring", IoUring.unavailabilityCause());
                }
                yield ioUring();
            }
            case "epoll" -> {
                if (!Epoll.isAvailable()) {
                    throw unavailable("epoll", Epoll.unavailabilityCause());
                }
                yield epoll();
            }
            case "nio" -> nio();
            default -> throw new IllegalArgumentException(
                    PROP + "=" + forced + " is not a known tier (expected io_uring|epoll|nio)");
        };
    }

    private static Selection ioUring() {
        return new Selection("io_uring", IoUringIoHandler.newFactory(),
                IoUringServerSocketChannel.class);
    }

    private static Selection epoll() {
        return new Selection("epoll", EpollIoHandler.newFactory(), EpollServerSocketChannel.class);
    }

    private static Selection nio() {
        return new Selection("nio", NioIoHandler.newFactory(), NioServerSocketChannel.class);
    }

    private static IllegalStateException unavailable(String tier, Throwable cause) {
        return new IllegalStateException(PROP + "=" + tier + " was forced, but the " + tier
                + " transport is unavailable on this host (kernel/native lib). Refusing to silently "
                + "downgrade — unset " + PROP + " to auto-select, or pick an available tier.", cause);
    }

    /**
     * A human-readable availability report for the startup log (which tiers this host supports and,
     * for any that are unavailable, why). Does not allocate any event-loop resources.
     */
    public static String availabilityReport() {
        StringBuilder sb = new StringBuilder("netty transport availability: io_uring=")
                .append(IoUring.isAvailable());
        if (!IoUring.isAvailable() && IoUring.unavailabilityCause() != null) {
            sb.append(" (").append(IoUring.unavailabilityCause()).append(')');
        }
        sb.append(", epoll=").append(Epoll.isAvailable());
        if (!Epoll.isAvailable() && Epoll.unavailabilityCause() != null) {
            sb.append(" (").append(Epoll.unavailabilityCause()).append(')');
        }
        sb.append(", nio=true");
        return sb.toString();
    }
}
