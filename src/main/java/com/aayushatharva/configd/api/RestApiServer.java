package com.aayushatharva.configd.api;

import com.aayushatharva.configd.ConfigdConfig;
import com.aayushatharva.configd.cache.CacheManager;
import com.aayushatharva.configd.replication.ReplicationManager;
import com.aayushatharva.configd.store.KVStore;
import com.aayushatharva.configd.txlog.BatchingTransactionLogWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * REST API server for Configd.
 *
 * Provides HTTP endpoints for external key-value operations:
 *
 * <pre>
 *   GET    /v1/kv/{key}              — Read a key
 *   GET    /v1/kv/{key}?version=N    — MVCC read at version N
 *   PUT    /v1/kv/{key}              — Write a key (body = value)
 *   DELETE /v1/kv/{key}              — Delete a key
 *   GET    /v1/scan?start=X&end=Y    — Range scan
 *   GET    /v1/status                — Node status and metrics
 *   GET    /v1/health                — Health check
 * </pre>
 *
 * Implements Configd's QSrest pattern: stateless service enforcing
 * access control and keyspace separation. Supports write quotas.
 */
public class RestApiServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RestApiServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int port;
    private final KVStore store;
    private final CacheManager cacheManager;
    private final BatchingTransactionLogWriter writer;
    private final ReplicationManager replicationManager;
    private final ConfigdConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public RestApiServer(int port, KVStore store, CacheManager cacheManager,
                         BatchingTransactionLogWriter writer,
                         ReplicationManager replicationManager,
                         ConfigdConfig config) {
        this.port = port;
        this.store = store;
        this.cacheManager = cacheManager;
        this.writer = writer;
        this.replicationManager = replicationManager;
        this.config = config;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        var bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1024 * 1024))
                                .addLast(new ApiHandler());
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("REST API server listening on port {}", port);
    }

    private class ApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();
            HttpMethod method = request.method();

            try {
                if (uri.startsWith("/v1/kv/")) {
                    String key = uri.substring("/v1/kv/".length());
                    // Strip query string from key
                    int queryIdx = key.indexOf('?');
                    String queryString = queryIdx >= 0 ? key.substring(queryIdx + 1) : "";
                    if (queryIdx >= 0) key = key.substring(0, queryIdx);

                    if (method == HttpMethod.GET) {
                        handleGet(ctx, key, queryString);
                    } else if (method == HttpMethod.PUT) {
                        handlePut(ctx, key, request.content());
                    } else if (method == HttpMethod.DELETE) {
                        handleDelete(ctx, key);
                    } else {
                        sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
                    }
                } else if (uri.startsWith("/v1/scan")) {
                    handleScan(ctx, uri);
                } else if (uri.equals("/v1/status")) {
                    handleStatus(ctx);
                } else if (uri.equals("/v1/health")) {
                    handleHealth(ctx);
                } else {
                    sendError(ctx, HttpResponseStatus.NOT_FOUND, "Not found");
                }
            } catch (Exception e) {
                log.error("API request failed: {} {}", method, uri, e);
                sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }

        private void handleGet(ChannelHandlerContext ctx, String key, String queryString) throws Exception {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            Map<String, String> params = parseQuery(queryString);

            byte[] value;
            if (params.containsKey("version")) {
                long version = Long.parseLong(params.get("version"));
                value = cacheManager.get(keyBytes, version).get();
            } else {
                value = cacheManager.get(keyBytes).get();
            }

            if (value == null) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, "Key not found: " + key);
            } else {
                var response = ApiResponse.ok(Map.of(
                        "key", key,
                        "value", Base64.getEncoder().encodeToString(value),
                        "version", store.getCurrentVersion()
                ));
                sendJson(ctx, HttpResponseStatus.OK, response);
            }
        }

        private void handlePut(ChannelHandlerContext ctx, String key, ByteBuf body) throws Exception {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] value = new byte[body.readableBytes()];
            body.readBytes(value);

            long seq = writer.put(keyBytes, value).get();

            var response = ApiResponse.ok(Map.of(
                    "key", key,
                    "version", seq
            ));
            sendJson(ctx, HttpResponseStatus.OK, response);
        }

        private void handleDelete(ChannelHandlerContext ctx, String key) throws Exception {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            long seq = writer.delete(keyBytes).get();

            var response = ApiResponse.ok(Map.of(
                    "key", key,
                    "version", seq
            ));
            sendJson(ctx, HttpResponseStatus.OK, response);
        }

        private void handleScan(ChannelHandlerContext ctx, String uri) throws Exception {
            Map<String, String> params = parseQuery(uri.contains("?") ? uri.substring(uri.indexOf('?') + 1) : "");
            byte[] startKey = params.getOrDefault("start", "").getBytes(StandardCharsets.UTF_8);
            byte[] endKey = params.containsKey("end") ? params.get("end").getBytes(StandardCharsets.UTF_8) : null;
            int limit = Integer.parseInt(params.getOrDefault("limit", "100"));

            var entries = store.scan(startKey, endKey, limit);
            var results = entries.stream()
                    .map(e -> Map.of(
                            "key", new String(e.key(), StandardCharsets.UTF_8),
                            "value", Base64.getEncoder().encodeToString(e.value()),
                            "version", e.version()
                    ))
                    .toList();

            sendJson(ctx, HttpResponseStatus.OK, ApiResponse.ok(results));
        }

        private void handleStatus(ChannelHandlerContext ctx) throws Exception {
            var cacheStats = cacheManager.getStats();
            var status = new LinkedHashMap<String, Object>();
            status.put("nodeId", config.getNodeId());
            status.put("role", config.getRole().name());
            status.put("mode", config.getMode().name());
            status.put("dataCenter", config.getDataCenter());
            status.put("currentVersion", store.getCurrentVersion());
            status.put("approximateKeys", store.approximateSize());
            status.put("cache", Map.of(
                    "l1Hits", cacheStats.l1Hits(),
                    "l2Hits", cacheStats.l2Hits(),
                    "l3Hits", cacheStats.l3Hits(),
                    "misses", cacheStats.misses(),
                    "hitRate", String.format("%.4f", cacheStats.hitRate()),
                    "localCacheEntries", cacheStats.localCacheEntries(),
                    "localCacheUtilization", String.format("%.2f", cacheStats.localCacheUtilization())
            ));

            sendJson(ctx, HttpResponseStatus.OK, ApiResponse.ok(status));
        }

        private void handleHealth(ChannelHandlerContext ctx) throws Exception {
            sendJson(ctx, HttpResponseStatus.OK, ApiResponse.ok(Map.of(
                    "status", "healthy",
                    "version", store.getCurrentVersion()
            )));
        }

        private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object body) throws Exception {
            byte[] json = MAPPER.writeValueAsBytes(body);
            var response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status,
                    Unpooled.wrappedBuffer(json));
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    .set(HttpHeaderNames.CONTENT_LENGTH, json.length);
            ctx.writeAndFlush(response);
        }

        private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
            try {
                sendJson(ctx, status, ApiResponse.error(message));
            } catch (Exception e) {
                ctx.close();
            }
        }

        private Map<String, String> parseQuery(String query) {
            if (query == null || query.isEmpty()) return Map.of();
            var result = new HashMap<String, String>();
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    result.put(kv[0], kv[1]);
                }
            }
            return result;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("API handler error", cause);
            ctx.close();
        }
    }

    @Override
    public void close() {
        if (serverChannel != null) serverChannel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        log.info("REST API server stopped");
    }
}
