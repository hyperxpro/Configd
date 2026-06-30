package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.netty.NettyTransport;
import io.configd.observability.PrometheusExporter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Production Netty edge read-serving HTTP/1.1 server (ADR-0043, M1) — the Netty adapter over the
 * transport-agnostic {@link EdgeReadHandler}. Serves byte-identical responses to the JDK
 * {@link EdgeHttpServer} on the canonical request paths by construction (both delegate to the same
 * logic; DR-N2 / exact-match routing DR-N4), at 8.7× less server-side allocation (head-to-head
 * evidence, {@code docs/jdk-vs-netty/verdict.md}; re-proven on
 * this production pipeline by {@code docs/netty-migration/m1-edge-read-gc-proof.md}).
 *
 * <p><b>Transport.</b> Tier selected at startup by {@link NettyTransport} (io_uring → Epoll → NIO,
 * runtime-detected; CI exercises the fallback). {@code MultiThreadIoEventLoopGroup} +
 * {@code PooledByteBufAllocator.DEFAULT}; {@code HttpServerCodec} with a hand-rolled handler (no
 * {@code HttpObjectAggregator} on the hot path); pooled response buffer; keep-alive honoured;
 * {@code voidPromise} writes; flush on {@code channelReadComplete}.
 *
 * <p><b>Allocation discipline (hot path).</b> The handler IS the {@link EdgeReadHandler.Sink} (no
 * per-request sink object), allocates exactly the one {@link HttpHeaders} the response needs (handed
 * INTO the response), one pooled body {@link ByteBuf}, and one response object — matching the
 * head-to-head prototype. The slowloris deadline is enforced by a single self-rescheduling watcher
 * keyed off a {@code deadlineNanos} timestamp, so a completed request costs only one {@code long}
 * write (NOT a per-request {@code schedule()} — an earlier naive rearm cost ~104 B/req and broke the
 * 8.7× bar; see the gc-proof doc).
 *
 * <p><b>Hardening the head-to-head prototype lacked</b> (a public read port is hostile, charter §3):
 * bounded {@code HttpServerCodec} (oversize line/header → 400 + close); a request-size ceiling
 * (oversize body → 413 + close); a request-completion deadline (slowloris incl. the dribble variant);
 * {@link IdleStateHandler} idle reaping; and a leak-free {@code ByteBuf} lifecycle (M1.4).
 */
public final class NettyEdgeHttpServer {

    private static final int MAX_INITIAL_LINE = 8192;
    private static final int MAX_HEADER_SIZE = 8192;
    private static final int MAX_CHUNK = 8192;

    private final int port;
    private final EdgeReadHandler handler;
    private final NettyTransport.Selection transport;
    private final int workerThreads;
    private final long requestTimeoutMillis;
    private final long requestTimeoutNanos;
    private final long idleTimeoutMillis;
    private final int maxRequestBytes;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;

    /**
     * Same constructor shape as {@link EdgeHttpServer} (so the M1.7 swap in {@code EdgeNodeMain} is a
     * one-line change). The F-S7-TLS-2 {@code /metrics} scrape token is read from the same system
     * property both adapters observe.
     */
    public NettyEdgeHttpServer(int port, EdgeClientCore core,
                               StrongReadKeyClass strongReadKeyClass,
                               PrometheusExporter exporter, EdgeNodeMetrics metrics) {
        this.port = port;
        String metricsScrapeToken = System.getProperty("configd.edge.metricsScrapeToken");
        this.handler = new EdgeReadHandler(core, strongReadKeyClass, exporter, metrics,
                metricsScrapeToken);
        this.workerThreads = Integer.getInteger("configd.edge.netty.workerThreads",
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        this.requestTimeoutMillis = Long.getLong("configd.edge.netty.requestTimeoutMillis", 30_000L);
        this.requestTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis);
        this.idleTimeoutMillis = Long.getLong("configd.edge.netty.idleTimeoutMillis", 60_000L);
        this.maxRequestBytes = Integer.getInteger("configd.edge.netty.maxRequestBytes", 1 << 20);
        this.transport = NettyTransport.select();
    }

    /** The active transport tier (io_uring / epoll / nio) — surfaced for logging + the CI proof. */
    public String transportTier() {
        return transport.tier();
    }

    public void start() throws InterruptedException {
        IoHandlerFactory ioFactory = transport.ioHandlerFactory();
        boss = new MultiThreadIoEventLoopGroup(1, ioFactory);
        worker = new MultiThreadIoEventLoopGroup(workerThreads, ioFactory);
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, worker)
                .channel(transport.serverChannelClass())
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(
                                new HttpServerCodec(MAX_INITIAL_LINE, MAX_HEADER_SIZE, MAX_CHUNK));
                        if (idleTimeoutMillis > 0) {
                            ch.pipeline().addLast(new IdleStateHandler(
                                    0, 0, idleTimeoutMillis, TimeUnit.MILLISECONDS));
                        }
                        ch.pipeline().addLast(new ReadHandler());
                    }
                });
        boolean started = false;
        try {
            serverChannel = b.bind(new InetSocketAddress(port)).sync().channel();
            started = true;
        } finally {
            if (!started) {
                // bind/sync failed (e.g. port in use) or was interrupted after the event-loop
                // groups were created — release them so a failed start() leaks no threads/FDs.
                stop();
            }
        }
    }

    /** The actual bound port (resolves an ephemeral port 0 after {@link #start()}). */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** Bounded graceful shutdown (the JDK-25 io_uring shutdown-slowness mitigation, netty42-api.md §2). */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (worker != null) {
            worker.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }

    /**
     * Per-channel inbound handler (a new instance per connection — holds per-request state) AND the
     * {@link EdgeReadHandler.Sink} for its responses (so there is no per-request sink object). All
     * methods run on the channel's single event-loop thread, so the mutable fields need no
     * synchronization.
     */
    private final class ReadHandler extends ChannelInboundHandlerAdapter implements EdgeReadHandler.Sink {

        private ChannelHandlerContext chCtx;   // set on channelActive; used by the sink + deadline

        // Per-request request state.
        private String method;
        private String uri;
        private String cursorHeader;
        private String authHeader;
        private boolean keepAlive = true;
        private long bodyBytes;
        private boolean rejected;

        // Slowloris deadline (allocation-free hot path): a single self-rescheduling watcher enforces
        // "a request must complete by deadlineNanos". Each completed request just writes deadlineNanos;
        // the watcher reschedules itself only when it fires (≈ once per timeout window, not per request).
        private long deadlineNanos;
        private ScheduledFuture<?> deadlineWatcher;
        private Runnable deadlineCheck;

        // Per-request response headers — the ONE HttpHeaders the response needs, created fresh per
        // request and handed INTO the response (not in addition to it).
        private HttpHeaders respHeaders;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            chCtx = ctx;
            // Arm at connection open — NOT on the decoded HttpRequest: a partial header block never
            // decodes to an HttpRequest, so a header-slowloris would never arm it.
            if (requestTimeoutMillis > 0) {
                deadlineCheck = this::checkDeadline;   // cached once; reused on every reschedule
                deadlineNanos = System.nanoTime() + requestTimeoutNanos;
                deadlineWatcher = ctx.executor().schedule(
                        deadlineCheck, requestTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            cancelWatcher();
            ctx.fireChannelInactive();
        }

        /** Close if the completion deadline has passed; else reschedule to the (possibly pushed) deadline. */
        private void checkDeadline() {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                chCtx.close();
            } else {
                deadlineWatcher = chCtx.executor().schedule(deadlineCheck,
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
            }
        }

        private void cancelWatcher() {
            if (deadlineWatcher != null) {
                deadlineWatcher.cancel(false);
                deadlineWatcher = null;
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof HttpRequest req) {
                    method = req.method().name();
                    uri = req.uri();
                    keepAlive = HttpUtil.isKeepAlive(req);
                    cursorHeader = req.headers().get(EdgeHttpServer.HDR_CURSOR);
                    authHeader = req.headers().get(HttpHeaderNames.AUTHORIZATION);
                    bodyBytes = 0;
                    rejected = false;
                    if (req.decoderResult().isFailure()) {
                        // Oversize initial line / header block, or malformed framing.
                        fail(ctx, HttpResponseStatus.BAD_REQUEST);
                        return;
                    }
                }
                if (msg instanceof HttpContent hc) {
                    if (!rejected) {
                        bodyBytes += hc.content().readableBytes();
                        if (bodyBytes > maxRequestBytes) {
                            fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE); // 413
                        }
                    }
                    if (msg instanceof LastHttpContent && !rejected) {
                        respond(ctx);
                        if (requestTimeoutMillis > 0) {
                            // Reset the completion clock for the next request — a long write, no alloc.
                            deadlineNanos = System.nanoTime() + requestTimeoutNanos;
                        }
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg); // HttpRequest is not ref-counted; HttpContent is
            }
        }

        private void respond(ChannelHandlerContext ctx) {
            respHeaders = new DefaultHttpHeaders();
            handler.handle(method, EdgeReadHandler.stripQuery(uri), cursorHeader, authHeader, this);
        }

        // ---- EdgeReadHandler.Sink (this handler renders its own responses) ----

        @Override
        public void header(CharSequence name, CharSequence value) {
            respHeaders.set(name, value);
        }

        @Override
        public void commit(int status, CharSequence contentType, byte[] body) {
            ByteBuf buf = body.length == 0
                    ? Unpooled.EMPTY_BUFFER
                    : chCtx.alloc().buffer(body.length).writeBytes(body);
            respHeaders.set(HttpHeaderNames.CONTENT_TYPE, contentType)
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HTTP_1_1, HttpResponseStatus.valueOf(status), buf, respHeaders,
                    EmptyHttpHeaders.INSTANCE);
            if (keepAlive) {
                respHeaders.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                chCtx.write(resp, chCtx.voidPromise());
            } else {
                respHeaders.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                chCtx.write(resp).addListener(ChannelFutureListener.CLOSE);
            }
        }

        /** Writes a bodyless error response and closes (used for oversize / decode-failed requests). */
        private void fail(ChannelHandlerContext ctx, HttpResponseStatus status) {
            rejected = true;
            FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                ctx.close(); // idle keep-alive reaping (defence in depth alongside the deadline)
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cancelWatcher();
            ctx.close();
        }
    }
}
