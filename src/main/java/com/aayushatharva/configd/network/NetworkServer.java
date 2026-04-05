package com.aayushatharva.configd.network;

import com.aayushatharva.configd.network.protocol.Message;
import com.aayushatharva.configd.network.protocol.MessageCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.function.BiConsumer;

/**
 * Netty-based internal server for inter-node communication.
 * Handles replication, cache lookups, gossip, and relay requests.
 */
public class NetworkServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(NetworkServer.class);

    private final int port;
    private final BiConsumer<ChannelHandlerContext, Message> messageHandler;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NetworkServer(int port, BiConsumer<ChannelHandlerContext, Message> messageHandler) {
        this.port = port;
        this.messageHandler = messageHandler;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        var bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 256)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("codec", new MessageCodec())
                                .addLast("handler", new SimpleChannelInboundHandler<Message>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                        messageHandler.accept(ctx, msg);
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                        log.error("Server channel error", cause);
                                        ctx.close();
                                    }
                                });
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("Network server listening on port {}", port);
    }

    @Override
    public void close() {
        if (serverChannel != null) serverChannel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        log.info("Network server stopped");
    }
}
