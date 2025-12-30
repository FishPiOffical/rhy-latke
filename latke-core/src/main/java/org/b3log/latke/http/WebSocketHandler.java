/*
 * Latke - 一款以 JSON 为主的 Java Web 框架
 * Copyright (c) 2009-present, b3log.org
 *
 * Latke is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *         http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */
package org.b3log.latke.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.lang.StringUtils;
import org.b3log.latke.Latkes;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Websocket handler.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.0.0.0, Nov 6, 2019
 * @since 3.0.2
 */
final class WebSocketHandler extends SimpleChannelInboundHandler<Object> {

    private WebSocketServerHandshaker handshaker;
    private WebSocketSession webSocketSession;
    private WebSocketChannel webSocketChannel;
    /**
     * Offload websocket callbacks to virtual threads (or fallback executor).
     */
    private static final Executor WS_EXECUTOR = Latkes.EXECUTOR_SERVICE;

    /**
     * 最大 WebSocket 帧大小 (10MB)，支持长消息传输
     */
    private static final int MAX_FRAME_SIZE = 10 * 1024 * 1024;

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof HttpRequest) {
            handleHttpRequest(ctx, (HttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        } else {
            ReferenceCountUtil.retain(msg);
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) {
        ctx.flush();
    }

    private void handleHttpRequest(final ChannelHandlerContext ctx, final HttpRequest req) {
        //获取请求的IP地址
        String ip = req.headers().get("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        } else {
            // 可能有多个IP，用逗号分隔，取第一个
            ip = ip.split(",")[0].trim();
        }
        if (isWebSocketRequest(req)) {
            final WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    req.uri(), null, true, MAX_FRAME_SIZE);
            handshaker = wsFactory.newHandshaker(req);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                handshaker.handshake(ctx.channel(), req);
                // 添加帧聚合器，支持分片消息的自动合并
                ctx.pipeline().addBefore(ctx.name(), "wsFrameAggregator", new WebSocketFrameAggregator(MAX_FRAME_SIZE));
                webSocketSession = new WebSocketSession(ctx);
                webSocketSession.params.put("ip", ip);

                // 解析查询字符串
                final QueryStringDecoder queryStringDecoder = new QueryStringDecoder(req.uri());
                final Map<String, List<String>> params = queryStringDecoder.parameters();
                for (final Map.Entry<String, List<String>> p : params.entrySet()) {
                    final String key = p.getKey();
                    final List<String> vals = p.getValue();
                    for (final String val : vals) {
                        webSocketSession.params.put(key, val);
                    }
                }

                // 处理 HTTP 会话和 Cookie
                handleCookie(req, webSocketSession);

                webSocketSession.webSocketChannel = webSocketChannel;
                CompletableFuture.completedFuture(webSocketSession).thenAcceptAsync(webSocketChannel::onConnect, WS_EXECUTOR);
            }
        } else {
            ReferenceCountUtil.retain(req);
            ctx.fireChannelRead(req);
        }
    }

    private void handleCookie(final HttpRequest req, final WebSocketSession webSocketSession) {
        final boolean secure = StringUtils.equalsIgnoreCase(Latkes.getServerScheme(), "https");
        Session session = null;
        final boolean enabledSession = Latkes.isEnabledSession();
        final String cookieStr = req.headers().get(HttpHeaderNames.COOKIE.toString());
        if (StringUtils.isNotBlank(cookieStr)) {
            final Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieStr);
            for (final Cookie cookie : cookies) {
                if (!enabledSession) {
                    webSocketSession.addCookie(new org.b3log.latke.http.Cookie(cookie));
                    continue;
                }

                if (cookie.name().equals(Session.LATKE_SESSION_ID)) {
                    final String cookieSessionId = cookie.value();
                    if (!Sessions.contains(cookieSessionId)) {
                        session = createSessionCookie(webSocketSession, secure);
                    } else {
                        session = Sessions.get(cookieSessionId);
                        final org.b3log.latke.http.Cookie c = new org.b3log.latke.http.Cookie(Session.LATKE_SESSION_ID, session.getId());
                        c.setHttpOnly(true);
                        c.setSecure(secure);
                        webSocketSession.addCookie(c);
                    }
                } else {
                    webSocketSession.addCookie(new org.b3log.latke.http.Cookie(cookie));
                }
            }
        } else {
            if (enabledSession) {
                session = createSessionCookie(webSocketSession, secure);
            }
        }

        if (null == session && enabledSession) {
            session = createSessionCookie(webSocketSession, secure);
        }
        webSocketSession.session = session;
    }

    private Session createSessionCookie(final WebSocketSession webSocketSession, final boolean secure) {
        final Session ret = Sessions.add();
        final org.b3log.latke.http.Cookie c = new org.b3log.latke.http.Cookie(Session.LATKE_SESSION_ID, ret.getId());
        c.setHttpOnly(true);
        c.setSecure(secure);
        webSocketSession.addCookie(c);

        return ret;
    }

    private void handleWebSocketFrame(final ChannelHandlerContext ctx, final WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            CompletableFuture.completedFuture(webSocketSession).thenAcceptAsync(webSocketChannel::onClose, WS_EXECUTOR);
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (!(frame instanceof TextWebSocketFrame)) {
            final RuntimeException throwable = new UnsupportedOperationException("Unsupported frame type [" + frame.getClass().getName() + "]");
            handshaker.close(ctx.channel(), new CloseWebSocketFrame());
            CompletableFuture.completedFuture(new WebSocketChannel.Error(throwable, webSocketSession))
                    .thenAcceptAsync(webSocketChannel::onError, WS_EXECUTOR);
            return;
        }

        CompletableFuture.completedFuture(new WebSocketChannel.Message(((TextWebSocketFrame) frame).text(), webSocketSession))
                .thenAcceptAsync(webSocketChannel::onMessage, WS_EXECUTOR);
    }

    private boolean isWebSocketRequest(final HttpRequest req) {
        final String uri = StringUtils.substringBefore(req.uri(), "?");

        return (webSocketChannel = Dispatcher.webSocketChannels.get(uri)) != null
                && "Upgrade".equalsIgnoreCase(req.headers().get(HttpHeaderNames.CONNECTION))
                && "WebSocket".equalsIgnoreCase(req.headers().get(HttpHeaderNames.UPGRADE));
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        if (null != webSocketSession && null != webSocketChannel) {
            CompletableFuture.completedFuture(new WebSocketChannel.Error(cause, webSocketSession))
                    .thenAcceptAsync(webSocketChannel::onError, WS_EXECUTOR);
        }

        ctx.close();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        if (null != webSocketSession && null != webSocketChannel) {
            CompletableFuture.completedFuture(webSocketSession).thenAcceptAsync(webSocketChannel::onClose, WS_EXECUTOR);
        }
        ctx.fireChannelInactive();
    }
}
