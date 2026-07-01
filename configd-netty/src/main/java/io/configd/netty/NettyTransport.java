package io.configd.netty;

import io.netty.channel.Channel;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.channel.uring.IoUringSocketChannel;

import java.util.Locale;

/**
 * Netty transport tier selector.
 *
 * <p>Auto-selects in the order <b>Epoll -&gt; NIO</b> and returns a <em>coherent triple</em> - the
 * {@link IoHandlerFactory} <b>and</b> the matching {@link ServerChannel} class. In Netty 4.2 the
 * event-loop group no longer implies the channel type (one generic {@code MultiThreadIoEventLoopGroup}
 * takes any {@code IoHandlerFactory}); pairing a factory with the wrong channel class is the #1 4.2
 * migration bug. Resolving both together here is the single place that pairing is decided.
 *
 * <p><b>io_uring is OPT-IN, not auto-selected.</b> It is a performance tier, never a correctness
 * dependency. Measured at Configd's workload it delivers no throughput/tail benefit and a ~2x
 * throughput <b>regression</b> at high fan-out (1024 subscriber streams), with Epoll the
 * proven-faster transport. io_uring's syscall reduction is real but batches per event loop (one loop
 * per core), so at Configd's connection scale the per-loop density is too low to help. io_uring
 * remains available for operators via the override below. NIO is pure-Java and available on every
 * JVM/OS.
 *
 * <p><b>Override (opt-in, fail-loud).</b>
 * {@code -Dconfigd.netty.transport=io_uring|epoll|nio} forces a tier. Forcing a tier that is
 * unavailable on the host is a startup error, not a silent downgrade - a silent downgrade is how
 * a "we ran on io_uring/epoll" claim becomes fiction.
 */
public final class NettyTransport {

    /** System property to force a transport tier (fail-loud if unavailable). */
    public static final String PROP = "configd.netty.transport";

    private NettyTransport() {
    }

    /**
     * The resolved transport: a tier name plus the coherent ({@link IoHandlerFactory}, server-channel,
     * client-channel) triple to bootstrap with. The same factory instance is shared by the boss and
     * worker groups (it is a factory of per-thread handlers).
     *
     * <p>{@code serverChannelClass} is used by surfaces that accept connections; {@code clientChannelClass}
     * is the matching outbound {@code SocketChannel} for the consensus transport, which connects out to
     * peers. Both are paired with the same {@link IoHandlerFactory} - pairing a factory with a channel
     * from a different tier is the #1 Netty 4.2 migration bug, so the coherent set is decided here.
     */
    public record Selection(String tier,
                            IoHandlerFactory ioHandlerFactory,
                            Class<? extends ServerChannel> serverChannelClass,
                            Class<? extends Channel> clientChannelClass) {
    }

    /**
     * Resolves the transport per the override, else auto-selects <b>Epoll -&gt; NIO</b>.
     *
     * <p>io_uring is NOT auto-selected. Measured at Configd's workload, io_uring delivers no
     * throughput/tail benefit and a ~2x throughput regression at high fan-out (1024 subscriber
     * streams) vs Epoll. io_uring's syscall reduction is real but batches per event loop (one loop
     * per core), so at Configd's connection scale the per-loop density is too low to help.
     * io_uring is opt-in via {@code -Dconfigd.netty.transport=io_uring}.
     */
    public static Selection select() {
        String forced = System.getProperty(PROP);
        if (forced != null) {
            return forced(forced); // io_uring reachable here (opt-in), Epoll, or NIO
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
                IoUringServerSocketChannel.class, IoUringSocketChannel.class);
    }

    private static Selection epoll() {
        return new Selection("epoll", EpollIoHandler.newFactory(),
                EpollServerSocketChannel.class, EpollSocketChannel.class);
    }

    private static Selection nio() {
        return new Selection("nio", NioIoHandler.newFactory(),
                NioServerSocketChannel.class, NioSocketChannel.class);
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
