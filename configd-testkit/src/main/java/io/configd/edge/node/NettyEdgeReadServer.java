package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.StalenessTracker;
import io.configd.edge.StrongReadKeyClass;
import io.configd.edge.VersionCursor;
import io.configd.store.ReadResult;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * BEST-NETTY edge read-serving HTTP/1.1 server - the "Netty done properly" side of the
 * edge-read head-to-head (surface 2). It serves the <b>same response</b> as the production
 * {@link EdgeHttpServer} 200/404 read path - same {@link EdgeClientCore} read, same
 * {@code X-Configd-Cursor}/{@code X-Configd-Version}/{@code Content-Type} headers, same
 * {@link EdgeNodeMetrics} accounting - so the <em>only</em> difference between the two servers
 * is the HTTP transport shell (JDK {@code com.sun.net.httpserver} vs this Netty pipeline). That
 * isolates exactly the contested transport-shell allocation.
 *
 * <p>Strongest-Netty build per {@code docs/jdk-vs-netty/netty42-api.md}: 4.2
 * {@code MultiThreadIoEventLoopGroup} + {@code Epoll}/{@code Nio} {@code IoHandler} factory,
 * {@code PooledByteBufAllocator.DEFAULT}, {@code HttpServerCodec} with a hand-rolled handler
 * (NO {@code HttpObjectAggregator} on the hot path), pooled response buffer, keep-alive honored,
 * {@code voidPromise} writes, flush on {@code channelReadComplete}. Worker threads pinned to 2
 * (the 2-vCPU box).
 */
public final class NettyEdgeReadServer {

    private static final String CONFIG_PREFIX = "/v1/config/";

    private final EdgeClientCore core;
    private final StrongReadKeyClass strongReadKeyClass;
    private final EdgeNodeMetrics metrics;
    private final int port;
    private final boolean epoll;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;

    public NettyEdgeReadServer(int port, EdgeClientCore core,
                               StrongReadKeyClass strongReadKeyClass, EdgeNodeMetrics metrics) {
        this.port = port;
        this.core = core;
        this.strongReadKeyClass = strongReadKeyClass;
        this.metrics = metrics;
        this.epoll = Epoll.isAvailable();
    }

    /** @return true if the native Epoll transport is in use; false = NIO fallback. */
    public boolean usingEpoll() {
        return epoll;
    }

    public void start() throws InterruptedException {
        IoHandlerFactory ioFactory = epoll ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();
        boss = new MultiThreadIoEventLoopGroup(1, ioFactory);
        worker = new MultiThreadIoEventLoopGroup(2, ioFactory); // pinned to the 2-vCPU box
        Class<? extends ServerChannel> channelType =
                epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;

        ServerBootstrap b = new ServerBootstrap()
                .group(boss, worker)
                .channel(channelType)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new ReadHandler());
                    }
                });
        serverChannel = b.bind(new InetSocketAddress(port)).sync().channel();
    }

    /** The actual bound port (resolves an ephemeral port 0 after {@link #start()}). */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (boss != null) {
            boss.shutdownGracefully();
        }
        if (worker != null) {
            worker.shutdownGracefully();
        }
    }

    /** Sentinel for "no cursor header supplied" (mirrors EdgeHttpServer). */
    private static final long NO_CURSOR = Long.MIN_VALUE;

    /**
     * Per-channel inbound handler. Mirrors {@link EdgeHttpServer}'s read path so both servers do
     * equivalent business work; only the transport shell differs.
     */
    private final class ReadHandler extends ChannelInboundHandlerAdapter {

        private boolean keepAlive = true;
        private String uri;
        private String cursorHeader;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest req) {
                keepAlive = HttpUtil.isKeepAlive(req);
                uri = req.uri();
                cursorHeader = req.headers().get(EdgeHttpServer.HDR_CURSOR);
            }
            if (msg instanceof HttpContent) {
                ((HttpContent) msg).release(); // read server ignores the request body
                if (msg instanceof LastHttpContent) {
                    respond(ctx);
                }
            }
        }

        private void respond(ChannelHandlerContext ctx) {
            long readStart = System.nanoTime();
            try {
                String path = stripQuery(uri);
                if (!path.startsWith(CONFIG_PREFIX) || path.length() <= CONFIG_PREFIX.length()) {
                    sendText(ctx, HttpResponseStatus.BAD_REQUEST, "Missing config key in path");
                    return;
                }
                String key = path.substring(CONFIG_PREFIX.length());
                metrics.onRead();

                boolean stale = core.stalenessState().ordinal()
                        >= StalenessTracker.State.STALE.ordinal();

                if (strongReadKeyClass.isStrongReadKey(key)) {
                    metrics.onReadRefused(EdgeNodeMetrics.REASON_STRONG_READ);
                    FullHttpResponse r = text(HttpResponseStatus.SERVICE_UNAVAILABLE, "Fail-closed: strong-read");
                    r.headers().set(EdgeHttpServer.HDR_FAIL_CLOSED, "strong-read");
                    finish(ctx, r);
                    return;
                }
                if (!core.servesKey(key)) {
                    metrics.onReadRefused(EdgeNodeMetrics.REASON_NOT_SUBSCRIBED);
                    FullHttpResponse r = text(HttpResponseStatus.NOT_FOUND, "Refused: not-subscribed");
                    r.headers().set(EdgeHttpServer.HDR_REFUSED, "not-subscribed");
                    finish(ctx, r);
                    return;
                }

                long cursorVersion = parseCursor(cursorHeader);
                if (cursorVersion < 0 && cursorVersion != NO_CURSOR) {
                    sendText(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid " + EdgeHttpServer.HDR_CURSOR);
                    return;
                }
                long localVersion = core.currentVersion();
                ReadResult result = (cursorVersion == NO_CURSOR)
                        ? core.get(key)
                        : core.get(key, new VersionCursor(cursorVersion, 0L));

                if (result.found()) {
                    byte[] value = result.value();
                    ByteBuf body = ctx.alloc().buffer(value.length);
                    body.writeBytes(value);
                    FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.OK, body);
                    if (stale) {
                        resp.headers().set(EdgeHttpServer.HDR_STALE, "true");
                    }
                    resp.headers().set(EdgeHttpServer.HDR_CURSOR, Long.toString(core.currentVersion()));
                    resp.headers().set(EdgeHttpServer.HDR_VERSION, Long.toString(result.version()));
                    resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
                    resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, value.length);
                    finish(ctx, resp);
                    return;
                }

                FullHttpResponse r;
                if (cursorVersion != NO_CURSOR && cursorVersion > localVersion) {
                    metrics.onReadRefused(EdgeNodeMetrics.REASON_CURSOR_BEHIND);
                    r = text(HttpResponseStatus.NOT_FOUND, "Refused: cursor-behind");
                    r.headers().set(EdgeHttpServer.HDR_REFUSED, "cursor-behind");
                } else {
                    r = text(HttpResponseStatus.NOT_FOUND, "Not Found");
                }
                r.headers().set(EdgeHttpServer.HDR_CURSOR, Long.toString(localVersion));
                finish(ctx, r);
            } finally {
                metrics.recordReadLatency(System.nanoTime() - readStart);
            }
        }

        private FullHttpResponse text(HttpResponseStatus status, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            return resp;
        }

        private void sendText(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
            finish(ctx, text(status, body));
        }

        private void finish(ChannelHandlerContext ctx, FullHttpResponse resp) {
            if (keepAlive) {
                resp.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
                ctx.write(resp, ctx.voidPromise());
            } else {
                resp.headers().set(HttpHeaderNames.CONNECTION, "close");
                ctx.write(resp).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private static String stripQuery(String uri) {
        int q = uri.indexOf('?');
        return q < 0 ? uri : uri.substring(0, q);
    }

    private static long parseCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return NO_CURSOR;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
