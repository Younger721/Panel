package com.panel.interview.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册语音输入的 WebSocket 端点。
 *
 * <p>协议是原生 WebSocket（不是 STOMP），因为只需要一条双向流，STOMP 的订阅模型反而多余。</p>
 *
 * <p>origin 放开是刻意的：开发期前端在 5173、后端在 8080，直连时 origin 不同。
 * 真正的门在 {@link AsrHandshakeInterceptor} 的一次性 ticket 上，不在 origin。</p>
 */
@Configuration
@EnableWebSocket
public class AsrWebSocketConfig implements WebSocketConfigurer {

    private final AsrWebSocketHandler handler;
    private final AsrHandshakeInterceptor interceptor;

    public AsrWebSocketConfig(AsrWebSocketHandler handler, AsrHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/interview/asr")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
