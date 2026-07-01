package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuditLog;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.ReplayGuard;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.netty.NettyTransport;
import io.configd.observability.PrometheusExporter;
import io.configd.store.VersionedConfigStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Netty adapter for the Configd admin / control-plane HTTP API - the Netty transport
 * over the same transport-agnostic {@link AdminApiHandler} the JDK {@link HttpApiServer} delegates to.
 * Every security control is therefore re-proven on this pipeline by the identical contract
 * ({@code AbstractAdminApiServerContract} run on JDK + Netty + forced-NIO), not re-implemented.
 *
 * <p><b>Transport.</b> Tier selected at startup by {@link NettyTransport} (io_uring -> Epoll -> NIO,
 * runtime-detected; CI exercises the fallback). Same constructor shape as {@link HttpApiServer}, so the
 * {@code ConfigdServer} swap is a one-line change.
 *
 * <p><b>Why this differs from the edge read server.</b> The admin surface is the <em>write</em> path:
 * {@link AdminApiHandler#handle} can <b>block</b> (a PUT waits on Raft quorum commit; a strong/linearizable
 * read waits on ReadIndex). The JDK server runs each request on a virtual thread; this adapter does the
 * same - the event loop never blocks. Each request is decoded ({@link HttpObjectAggregator} assembles the
 * full request incl. the PUT body and emits 413 on oversize), copied to a transport-free carrier, and
 * dispatched to a per-server virtual-thread executor; the {@link AdminApiHandler.AdminResponse} is written
 * back <b>on the event loop</b>. Requests on one connection are processed strictly in arrival order (a
 * small per-connection FIFO), matching the JDK server's per-connection serialization even under HTTP/1.1
 * pipelining. The admin API is low-QPS control plane, so the aggregator +
 * virtual-thread hop are the right simplicity/correctness trade, not an allocation hot spot.
 *
 * <p><b>URI handling (load-bearing).</b> The request URI handed to {@link AdminApiHandler} is built with
 * {@code new URI(request.uri())} - the SAME {@code java.net.URI} decoder the JDK exchange uses - so the
 * strong-read key (from {@link URI#getPath()}, percent-decoded, not normalized/lowercased) is byte-identical
 * across transports. A request target that is not a valid URI is rejected with 400 before the handler runs.
 *
 * <p><b>Server-side TLS.</b> When an {@link SSLContext} is supplied (the same one the JDK
 * {@link javax.net.ssl.SSLContext}-backed {@code HttpsServer} would use), an {@link SslHandler} in server
 * mode is the first pipeline stage. Client identity remains the Bearer token (the JDK
 * {@code HttpsConfigurator} does not require client auth either); mTLS is a fan-out/consensus property,
 * not this surface.
 *
 * <p><b>Hardening</b> (the admin write port is exposed): bounded {@link HttpServerCodec}
 * (oversize line/header -> 400 + close); {@link HttpObjectAggregator} request-size ceiling (oversize body ->
 * 413 + close); a request-arrival completion deadline (slowloris incl. the dribble variant - the aggregator
 * holds a partial body so a dribble never flips to "processing", and the deadline reaps it);
 * {@link IdleStateHandler} idle reaping; and a leak-free {@code ByteBuf} lifecycle.
 */
public final class NettyHttpApiServer {

    private static final int MAX_INITIAL_LINE = 8192;
    private static final int MAX_HEADER_SIZE = 8192;
    private static final int MAX_CHUNK = 8192;

    private final int port;
    private final SSLContext sslContext; // nullable: plain HTTP when null
    private final AdminApiHandler handler;
    private final NettyTransport.Selection transport;
    private final int workerThreads;
    private final long requestTimeoutMillis;
    private final long requestTimeoutNanos;
    private final long idleTimeoutMillis;
    private final int maxRequestBytes;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;
    private ExecutorService blockingExecutor; // runs the (blocking) decision logic off-loop

    /** Same parameter shape as {@link HttpApiServer}'s full constructor (so the swap is one line). */
    public NettyHttpApiServer(int port,
                              SSLContext sslContext,
                              HealthService healthService,
                              PrometheusExporter prometheusExporter,
                              VersionedConfigStore configStore,
                              ConfigWriteService writeService,
                              ConfigReadService readService,
                              AuthInterceptor authInterceptor,
                              AclService aclService,
                              StrongReadPolicy strongReadPolicy,
                              BiFunction<ConfigScope, String, NodeId> leaderHintSupplier,
                              AuditLog auditLog,
                              ReplayGuard replayGuard) {
        this(port, sslContext, healthService, prometheusExporter, configStore, writeService, readService,
                authInterceptor, aclService, strongReadPolicy, leaderHintSupplier, auditLog, replayGuard, null);
    }

    /**
     * As the full constructor, plus the {@link AdminApiHandler.LeadershipAdmin} seam that backs the
     * ADMIN-gated leadership-transfer endpoint. A {@code null} seam leaves that endpoint unrouted.
     */
    public NettyHttpApiServer(int port,
                              SSLContext sslContext,
                              HealthService healthService,
                              PrometheusExporter prometheusExporter,
                              VersionedConfigStore configStore,
                              ConfigWriteService writeService,
                              ConfigReadService readService,
                              AuthInterceptor authInterceptor,
                              AclService aclService,
                              StrongReadPolicy strongReadPolicy,
                              BiFunction<ConfigScope, String, NodeId> leaderHintSupplier,
                              AuditLog auditLog,
                              ReplayGuard replayGuard,
                              AdminApiHandler.LeadershipAdmin leadershipAdmin) {
        this.port = port;
        this.sslContext = sslContext;
        this.handler = new AdminApiHandler(healthService, prometheusExporter, configStore, writeService,
                readService, authInterceptor, aclService, strongReadPolicy, leaderHintSupplier,
                auditLog, replayGuard, leadershipAdmin);
        this.workerThreads = Integer.getInteger("configd.server.netty.workerThreads",
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        this.requestTimeoutMillis = Long.getLong("configd.server.netty.requestTimeoutMillis", 30_000L);
        this.requestTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis);
        this.idleTimeoutMillis = Long.getLong("configd.server.netty.idleTimeoutMillis", 60_000L);
        this.maxRequestBytes = Integer.getInteger("configd.server.netty.maxRequestBytes", 1 << 20);
        this.transport = NettyTransport.select();
    }

    /** The active transport tier (io_uring / epoll / nio) - surfaced for logging + the CI proof. */
    public String transportTier() {
        return transport.tier();
    }

    public void start() throws InterruptedException {
        blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
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
                        if (sslContext != null) {
                            SSLEngine engine = sslContext.createSSLEngine();
                            engine.setUseClientMode(false);
                            // No setNeedClientAuth: server-side TLS only, matching the JDK HttpsConfigurator.
                            // Client identity is the Bearer token; mTLS is the fan-out/consensus surface.
                            ch.pipeline().addLast(new SslHandler(engine));
                        }
                        ch.pipeline().addLast(
                                new HttpServerCodec(MAX_INITIAL_LINE, MAX_HEADER_SIZE, MAX_CHUNK));
                        // Assembles the full request (incl. the PUT body); auto-responds 413 on oversize.
                        ch.pipeline().addLast(new HttpObjectAggregator(maxRequestBytes));
                        if (idleTimeoutMillis > 0) {
                            ch.pipeline().addLast(new IdleStateHandler(
                                    0, 0, idleTimeoutMillis, TimeUnit.MILLISECONDS));
                        }
                        ch.pipeline().addLast(new AdminHandler());
                    }
                });
        serverChannel = b.bind(new InetSocketAddress(port)).sync().channel();
    }

    /** The actual bound port (resolves an ephemeral port 0 after {@link #start()}). */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** Bounded graceful shutdown. */
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
        if (blockingExecutor != null) {
            blockingExecutor.shutdown();
        }
    }

    /** A transport-free snapshot of one request, safe to hand to a virtual thread after the {@link ByteBuf} is released. */
    private record NettyAdminRequest(String method, URI uri, HttpHeaders headers, byte[] body)
            implements AdminApiHandler.AdminRequest {
        @Override
        public String header(String name) {
            return headers.get(name);
        }
    }

    /** One queued request + the keep-alive disposition captured when it arrived. */
    private record Pending(NettyAdminRequest request, boolean keepAlive) {
    }

    /**
     * Per-connection inbound handler (a new instance per connection - holds per-connection state). All
     * methods run on the channel's single event-loop thread, so the mutable fields need no synchronization;
     * only the (blocking) {@link AdminApiHandler#handle} call is hopped to the virtual-thread executor.
     */
    private final class AdminHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private ChannelHandlerContext chCtx;
        private final ArrayDeque<Pending> pending = new ArrayDeque<>();
        private boolean processing;

        // Slowloris arrival deadline: a full request must ARRIVE by deadlineNanos. While a request is
        // being processed/queued (processing=true) the watcher does not close - the handler is bounded by
        // its own commit/read deadline. Re-armed when the connection goes idle waiting for the next request.
        private long deadlineNanos;
        private ScheduledFuture<?> deadlineWatcher;
        private Runnable deadlineCheck;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            chCtx = ctx;
            if (requestTimeoutMillis > 0) {
                deadlineCheck = this::checkDeadline;
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

        /** Close if idle-waiting past the arrival deadline; reschedule while a request is in flight. */
        private void checkDeadline() {
            if (processing) {
                deadlineWatcher = chCtx.executor().schedule(
                        deadlineCheck, requestTimeoutMillis, TimeUnit.MILLISECONDS);
                return;
            }
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
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            if (req.decoderResult().isFailure()) {
                // Oversize initial line / header block, or malformed framing.
                failAndClose(ctx, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            URI uri;
            try {
                uri = new URI(req.uri());
            } catch (URISyntaxException e) {
                failAndClose(ctx, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            // Copy everything off the (pooled, soon-released) request into a transport-free carrier.
            HttpHeaders headers = new DefaultHttpHeaders().add(req.headers());
            byte[] body = ByteBufUtil.getBytes(req.content());
            boolean keepAlive = HttpUtil.isKeepAlive(req);
            NettyAdminRequest request = new NettyAdminRequest(req.method().name(), uri, headers, body);

            pending.add(new Pending(request, keepAlive));
            if (!processing) {
                processing = true;
                dispatchNext(ctx);
            }
        }

        /** Pull the next queued request, run the (blocking) decision on a virtual thread, write back on-loop. */
        private void dispatchNext(ChannelHandlerContext ctx) {
            Pending p = pending.poll();
            if (p == null) {
                // Idle again: re-arm the arrival deadline for the next request on this kept-alive connection.
                processing = false;
                deadlineNanos = System.nanoTime() + requestTimeoutNanos;
                return;
            }
            blockingExecutor.execute(() -> {
                AdminApiHandler.AdminResponse response;
                try {
                    response = handler.handle(p.request());
                } catch (Throwable t) {
                    ctx.channel().eventLoop().execute(
                            () -> failAndClose(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR));
                    return;
                }
                ctx.channel().eventLoop().execute(() -> {
                    writeResponse(ctx, response, p.keepAlive());
                    if (p.keepAlive()) {
                        dispatchNext(ctx); // process the next queued request, or go idle
                    }
                });
            });
        }

        private void writeResponse(ChannelHandlerContext ctx, AdminApiHandler.AdminResponse response,
                                   boolean keepAlive) {
            if (!ctx.channel().isActive()) {
                return;
            }
            byte[] body = response.body();
            ByteBuf content = body.length == 0
                    ? Unpooled.EMPTY_BUFFER
                    : ctx.alloc().buffer(body.length).writeBytes(body);
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HTTP_1_1, HttpResponseStatus.valueOf(response.status()), content);
            for (Map.Entry<String, String> header : response.headers().entrySet()) {
                resp.headers().set(header.getKey(), header.getValue());
            }
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
            if (keepAlive) {
                resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                ctx.writeAndFlush(resp);
            } else {
                resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            }
        }

        /** Writes a bodyless error response and closes (oversize / decode-failed / malformed-target / internal error). */
        private void failAndClose(ChannelHandlerContext ctx, HttpResponseStatus status) {
            if (!ctx.channel().isActive()) {
                return;
            }
            FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                ctx.close(); // idle keep-alive reaping (defence in depth alongside the arrival deadline)
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cancelWatcher();
            ctx.close();
        }
    }
}
