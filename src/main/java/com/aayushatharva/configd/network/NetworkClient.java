package com.aayushatharva.configd.network;

import com.aayushatharva.configd.network.protocol.Message;
import com.aayushatharva.configd.network.protocol.MessageCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Netty client for connecting to other Configd nodes.
 * Supports request/response correlation via request IDs.
 */
public class NetworkClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(NetworkClient.class);
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final EventLoopGroup group;
    private final ConcurrentHashMap<Long, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong requestIdGenerator = new AtomicLong(0);
    private volatile Channel channel;
    private final String host;
    private final int port;

    public NetworkClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.group = new NioEventLoopGroup(1);
    }

    public void connect() throws InterruptedException {
        var bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("codec", new MessageCodec())
                                .addLast("handler", new SimpleChannelInboundHandler<Message>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                        var future = pendingRequests.remove(msg.requestId());
                                        if (future != null) {
                                            future.complete(msg);
                                        } else {
                                            log.debug("Received response for unknown request {}", msg.requestId());
                                        }
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                        log.error("Client channel error to {}:{}", host, port, cause);
                                        ctx.close();
                                    }

                                    @Override
                                    public void channelInactive(ChannelHandlerContext ctx) {
                                        pendingRequests.forEach((id, f) ->
                                                f.completeExceptionally(new RuntimeException("Connection lost")));
                                        pendingRequests.clear();
                                    }
                                });
                    }
                });

        channel = bootstrap.connect(host, port).sync().channel();
        log.info("Connected to {}:{}", host, port);
    }

    /** Send a request and return a future for the response. */
    public CompletableFuture<Message> send(Message request) {
        if (channel == null || !channel.isActive()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("Not connected to " + host + ":" + port));
        }

        long requestId = request.requestId();
        var future = new CompletableFuture<Message>();
        pendingRequests.put(requestId, future);

        channel.writeAndFlush(request).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                pendingRequests.remove(requestId);
                future.completeExceptionally(f.cause());
            }
        });

        // Timeout
        future.orTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((r, ex) -> pendingRequests.remove(requestId));

        return future;
    }

    /** Send a message without waiting for a response (fire-and-forget). */
    public void sendOneWay(Message message) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(message);
        }
    }

    public long nextRequestId() {
        return requestIdGenerator.incrementAndGet();
    }

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    public String getHost() { return host; }
    public int getPort() { return port; }

    @Override
    public void close() {
        if (channel != null) channel.close();
        group.shutdownGracefully();
    }
}
