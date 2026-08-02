package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuditLog;
import io.configd.api.AuthInterceptor;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.ReplayGuard;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.netty.NettyTransport;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
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
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
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


public final class NettyHttpApiServer {

    private static final int MAX_INITIAL_LINE = 8192;
    private static final int MAX_HEADER_SIZE = 8192;
    private static final int MAX_CHUNK = 8192;

    private final int port;
    // The interface to bind. null means the wildcard (all interfaces), matching the default of
    // `new InetSocketAddress(port)`. ConfigdServer passes ServerConfig.bindAddress() so the admin
    // read/write API honours the SAME bind as the Raft + edge planes - otherwise the no-silent-public-bind
    // guard would key on a value this, the most security-sensitive plane, ignores (the API would sit on
    // all interfaces regardless of what was configured).
    private final String bindAddress;
    private final SSLContext sslContext; // nullable: plain HTTP when null
    private final AdminApiHandler handler;
    // true when the auth chain includes mtls: the admin TLS then requests (optionally) a client cert so the
    // mtls authenticator can identify the caller. false = server-side TLS only (client cert not requested).
    private final boolean requestClientCert;
    private final NettyTransport.Selection transport;
    private final int workerThreads;
    private final long requestTimeoutMillis;
    private final long requestTimeoutNanos;
    private final long idleTimeoutMillis;
    private final int maxRequestBytes;
    // Ingress-reject counters (400 malformed / 413 oversize) on the shared metrics registry the
    // exporter reads, so a malformed/oversize-request storm is observable alongside the 429 write-overload shed.
    private final MetricsRegistry.Counter rejectedBadRequest;
    private final MetricsRegistry.Counter rejectedPayloadTooLarge;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;
    private ExecutorService blockingExecutor; // runs the (blocking) decision logic off-loop

    
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
        this(port, sslContext, healthService, prometheusExporter, configStore, writeService, readService,
                authInterceptor, aclService, strongReadPolicy, leaderHintSupplier, auditLog, replayGuard,
                leadershipAdmin, null);
    }

    
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
                              AdminApiHandler.LeadershipAdmin leadershipAdmin,
                              AuthenticatorChain chain) {
        this(null, port, sslContext, healthService, prometheusExporter, configStore, writeService,
                readService, authInterceptor, aclService, strongReadPolicy, leaderHintSupplier,
                auditLog, replayGuard, leadershipAdmin, chain, null, null);
    }

    
    public NettyHttpApiServer(String bindAddress,
                              int port,
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
                              AdminApiHandler.LeadershipAdmin leadershipAdmin,
                              AuthenticatorChain chain) {
        this(bindAddress, port, sslContext, healthService, prometheusExporter, configStore, writeService,
                readService, authInterceptor, aclService, strongReadPolicy, leaderHintSupplier,
                auditLog, replayGuard, leadershipAdmin, chain, null, null);
    }

    
    public NettyHttpApiServer(String bindAddress,
                              int port,
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
                              AdminApiHandler.LeadershipAdmin leadershipAdmin,
                              AuthenticatorChain chain,
                              AdminApiHandler.RaftClusterAdmin raftClusterAdmin,
                              AdminApiHandler.KeyringRotationAdmin keyringRotator) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.sslContext = sslContext;
        this.handler = new AdminApiHandler(healthService, prometheusExporter, configStore, writeService,
                readService, authInterceptor, aclService, strongReadPolicy, leaderHintSupplier,
                auditLog, replayGuard, leadershipAdmin, chain, raftClusterAdmin, keyringRotator);
        this.requestClientCert = chain != null && chain.providerTypes().contains("mtls");
        this.workerThreads = Integer.getInteger("configd.server.netty.workerThreads",
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        this.requestTimeoutMillis = Long.getLong("configd.server.netty.requestTimeoutMillis", 30_000L);
        this.requestTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis);
        this.idleTimeoutMillis = Long.getLong("configd.server.netty.idleTimeoutMillis", 60_000L);
        this.maxRequestBytes = Integer.getInteger("configd.server.netty.maxRequestBytes", 1 << 20);
        // Bind the reject counters to the exporter's shared registry (ConfigdMetrics eager-creates them,
        // so counter() returns the same instances; null exporter in a degenerate test falls back to a
        // throwaway registry so the increments never NPE).
        MetricsRegistry registry = prometheusExporter != null ? prometheusExporter.registry() : new MetricsRegistry();
        this.rejectedBadRequest = registry.counter(
                ConfigdMetrics.NAME_HTTP_REQUEST_REJECTED_BASE + "." + ConfigdMetrics.HTTP_REJECT_REASON_BAD_REQUEST);
        this.rejectedPayloadTooLarge = registry.counter(
                ConfigdMetrics.NAME_HTTP_REQUEST_REJECTED_BASE + "." + ConfigdMetrics.HTTP_REJECT_REASON_PAYLOAD_TOO_LARGE);
        this.transport = NettyTransport.select();
    }

    
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
                            // mTLS mode (chain includes mtls): request an OPTIONAL client cert so the mtls
                            // authenticator can identify the caller; wantClientAuth (not need) keeps
                            // bearer/basic clients working. Otherwise server-side TLS only (byte-identical).
                            if (requestClientCert) {
                                engine.setWantClientAuth(true);
                            }
                            ch.pipeline().addLast(new SslHandler(engine));
                        }
                        ch.pipeline().addLast(
                                new HttpServerCodec(MAX_INITIAL_LINE, MAX_HEADER_SIZE, MAX_CHUNK));
                        // Assembles the full request (incl. the PUT body); auto-responds 413 on oversize.
                        // The subclass counts the 413 before delegating to the identical default handling,
                        // so the reject is observable without changing the response behaviour.
                        ch.pipeline().addLast(new HttpObjectAggregator(maxRequestBytes) {
                            @Override
                            protected void handleOversizedMessage(ChannelHandlerContext ctx, HttpMessage oversized)
                                    throws Exception {
                                rejectedPayloadTooLarge.increment();
                                super.handleOversizedMessage(ctx, oversized);
                            }
                        });
                        if (idleTimeoutMillis > 0) {
                            ch.pipeline().addLast(new IdleStateHandler(
                                    0, 0, idleTimeoutMillis, TimeUnit.MILLISECONDS));
                        }
                        ch.pipeline().addLast(new AdminHandler());
                    }
                });
        boolean bound = false;
        try {
            serverChannel = b.bind(bindAddress == null
                    ? new InetSocketAddress(port)                       // wildcard (all interfaces)
                    : new InetSocketAddress(bindAddress, port)).sync().channel();
            bound = true;
        } finally {
            if (!bound) {
                // A mid-start failure (bind refused / port already in use) must not leak the non-daemon
                // boss/worker event loops - or the blocking executor - just created. stop() shuts them all
                // (it is idempotent and null-guarded), so a failed start() leaves nothing running for an
                // embedder or test; the original failure propagates.
                stop();
            }
        }
    }

    
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    
    String boundHost() {
        return ((InetSocketAddress) serverChannel.localAddress()).getAddress().getHostAddress();
    }

    
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

    
    private record NettyAdminRequest(String method, URI uri, HttpHeaders headers, byte[] body,
                                     List<X509Certificate> peerCertificates)
            implements AdminApiHandler.AdminRequest {
        @Override
        public String header(String name) {
            return headers.get(name);
        }
        // The record's peerCertificates() accessor satisfies AdminRequest.peerCertificates() - the verified
        // chain is captured on the event loop (below) so the off-loop decision logic needs no channel access.
    }

    
    private static List<X509Certificate> peerCertificates(ChannelHandlerContext ctx) {
        SslHandler ssl = ctx.pipeline().get(SslHandler.class);
        if (ssl == null) {
            return List.of();
        }
        try {
            Certificate[] certs = ssl.engine().getSession().getPeerCertificates();
            List<X509Certificate> chain = new ArrayList<>(certs.length);
            for (Certificate c : certs) {
                if (c instanceof X509Certificate x) {
                    chain.add(x);
                }
            }
            return chain;
        } catch (SSLPeerUnverifiedException e) {
            return List.of();
        }
    }

    
    private record Pending(NettyAdminRequest request, boolean keepAlive) {
    }

    
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
                rejectedBadRequest.increment();
                failAndClose(ctx, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            URI uri;
            try {
                uri = new URI(req.uri());
            } catch (URISyntaxException e) {
                rejectedBadRequest.increment();
                failAndClose(ctx, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            // Copy everything off the (pooled, soon-released) request into a transport-free carrier.
            HttpHeaders headers = new DefaultHttpHeaders().add(req.headers());
            byte[] body = ByteBufUtil.getBytes(req.content());
            boolean keepAlive = HttpUtil.isKeepAlive(req);
            // Capture the verified client cert (if any) here on the event loop; the decision runs off-loop.
            NettyAdminRequest request =
                    new NettyAdminRequest(req.method().name(), uri, headers, body, peerCertificates(ctx));

            pending.add(new Pending(request, keepAlive));
            if (!processing) {
                processing = true;
                dispatchNext(ctx);
            }
        }

        
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
                        dispatchNext(ctx);
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
