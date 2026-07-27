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
 * Netty adapter over {@link EdgeReadHandler}. Byte-identical to {@link EdgeHttpServer} by
 * construction, with less per-request allocation and selectable transport tier.
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
                stop();
            }
        }
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
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
    }

    private final class ReadHandler extends ChannelInboundHandlerAdapter implements EdgeReadHandler.Sink {

        private ChannelHandlerContext chCtx;
        private String method;
        private String uri;
        private String cursorHeader;
        private String authHeader;
        private boolean keepAlive = true;
        private long bodyBytes;
        private boolean rejected;

        // Single self-rescheduling watcher for request completion deadline (allocation-free).
        private long deadlineNanos;
        private ScheduledFuture<?> deadlineWatcher;
        private Runnable deadlineCheck;

        private HttpHeaders respHeaders;

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
                        fail(ctx, HttpResponseStatus.BAD_REQUEST);
                        return;
                    }
                }
                if (msg instanceof HttpContent hc) {
                    if (!rejected) {
                        bodyBytes += hc.content().readableBytes();
                        if (bodyBytes > maxRequestBytes) {
                            fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
                        }
                    }
                    if (msg instanceof LastHttpContent && !rejected) {
                        respond(ctx);
                        if (requestTimeoutMillis > 0) {
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
