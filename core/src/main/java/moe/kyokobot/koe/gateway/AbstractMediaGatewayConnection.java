package moe.kyokobot.koe.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.EventExecutor;
import moe.kyokobot.koe.VoiceServerInfo;
import moe.kyokobot.koe.internal.MediaConnectionImpl;
import moe.kyokobot.koe.internal.NettyBootstrapFactory;
import moe.kyokobot.koe.internal.dto.Operation;
import moe.kyokobot.koe.internal.dto.operation.OperationData;
import moe.kyokobot.koe.internal.util.JacksonUtils;
import moe.kyokobot.koe.internal.util.NettyFutureWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.NotYetConnectedException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractMediaGatewayConnection implements MediaGatewayConnection {
    private static final Logger logger = LoggerFactory.getLogger(AbstractMediaGatewayConnection.class);

    protected final MediaConnectionImpl connection;
    protected final VoiceServerInfo voiceServerInfo;
    protected final URI websocketURI;
    protected final Bootstrap bootstrap;
    protected final SslContext sslContext;
    protected final CompletableFuture<Void> connectFuture;

    protected EventExecutor eventExecutor;
    protected Channel channel;
    private boolean open;
    private boolean closed = false;

    public AbstractMediaGatewayConnection(@NotNull MediaConnectionImpl connection,
                                          @NotNull VoiceServerInfo voiceServerInfo,
                                          int version) {
        try {
            this.connection = Objects.requireNonNull(connection);
            this.voiceServerInfo = Objects.requireNonNull(voiceServerInfo);
            this.websocketURI = new URI(String.format("wss://%s/?v=%d",
                    voiceServerInfo.getEndpoint().replace(":80", ""), version));
            this.bootstrap = NettyBootstrapFactory.socket(connection.getOptions())
                    .handler(new WebSocketInitializer());
            this.sslContext = SslContextBuilder.forClient().build();
            this.connectFuture = new CompletableFuture<>();
        } catch (SSLException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public CompletableFuture<Void> start() {
        if (connectFuture.isDone()) return connectFuture;

        CompletableFuture<Void> future = new CompletableFuture<>();
        logger.debug("Connecting to {}", websocketURI);

        ChannelFuture chFuture = bootstrap.connect(websocketURI.getHost(), websocketURI.getPort() == -1 ? 443 : websocketURI.getPort());
        chFuture.addListener(new NettyFutureWrapper<>(future));
        future.thenAccept(v -> this.channel = chFuture.channel());
        return connectFuture;
    }

    @Override
    public void close(int code, @Nullable String reason) {
        if (channel != null && channel.isOpen()) {
            // Code 1006 must never be sent, according to RFC 6455
            if (code != 1006) {
                channel.writeAndFlush(new CloseWebSocketFrame(code, reason));
            }
            channel.close();
        }

        if (!connectFuture.isDone()) {
            connectFuture.completeExceptionally(new NotYetConnectedException());
        }

        onClose(code, reason, false);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    protected abstract void identify();

    protected abstract void handlePayload(OperationData object);

    protected void onClose(int code, @Nullable String reason, boolean remote) {
        if (!closed) {
            closed = true;
            connection.getDispatcher().gatewayClosed(code, reason, remote);
        }
    }

    @Override
    public abstract void updateSpeaking(int mask);

    public void sendInternalPayload(Operation op) {
        try {
            sendRaw(op);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    protected void sendRaw(Operation object) throws JsonProcessingException {
        if (channel != null && channel.isOpen()) {
            String data = JacksonUtils.getObjectMapper().writeValueAsString(object);
            logger.trace("<- {}", data);
            channel.writeAndFlush(new TextWebSocketFrame(data));
        }
    }

    private class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;

        WebSocketClientHandler() {
            this.handshaker = WebSocketClientHandshakerFactory.newHandshaker(websocketURI, WebSocketVersion.V13,
                    null, false, EmptyHttpHeaders.INSTANCE, 1280000);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            eventExecutor = ctx.executor();
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void channelInactive(@NotNull ChannelHandlerContext ctx) {
            close(1006, "Abnormal closure");
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            Channel ch = ctx.channel();

            if (!handshaker.isHandshakeComplete()) {
                if (msg instanceof FullHttpResponse) {
                    try {
                        handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                        AbstractMediaGatewayConnection.this.open = true;
                        connectFuture.complete(null);
                        AbstractMediaGatewayConnection.this.identify();
                    } catch (WebSocketHandshakeException e) {
                        connectFuture.completeExceptionally(e);
                    }
                }
                return;
            }

            if (msg instanceof FullHttpResponse) {
                FullHttpResponse response = (FullHttpResponse) msg;
                throw new IllegalStateException(
                        "Unexpected FullHttpResponse (getStatus=" + response.status() +
                                ", content=" + response.content().toString(StandardCharsets.UTF_8) + ")");
            }

            if (msg instanceof TextWebSocketFrame) {
                TextWebSocketFrame frame = (TextWebSocketFrame) msg;
                Operation object = JacksonUtils.getObjectMapper().readValue((InputStream) new ByteBufInputStream(frame.content()), Operation.class);
                logger.trace("-> {}", object);
                frame.release();
                if (object instanceof OperationData) {
                    handlePayload((OperationData) object);
                }
            } else if (msg instanceof CloseWebSocketFrame) {
                CloseWebSocketFrame frame = (CloseWebSocketFrame) msg;
                if (logger.isDebugEnabled()) {
                    logger.debug("Websocket closed, code: {}, reason: {}", frame.statusCode(), frame.reasonText());
                }
                AbstractMediaGatewayConnection.this.open = false;
                onClose(frame.statusCode(), frame.reasonText(), true);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!connectFuture.isDone()) {
                connectFuture.completeExceptionally(cause);
            }

            close(4000, "Internal error");
            ctx.close();
        }
    }

    private class WebSocketInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) {
            ChannelPipeline pipeline = ch.pipeline();
            SSLEngine engine = sslContext.newEngine(ch.alloc());
            pipeline.addLast("ssl", new SslHandler(engine));
            pipeline.addLast("http-codec", new HttpClientCodec());
            pipeline.addLast("aggregator", new HttpObjectAggregator(65536));
            pipeline.addLast("handler", new WebSocketClientHandler());
        }
    }
}