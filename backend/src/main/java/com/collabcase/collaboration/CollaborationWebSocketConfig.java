package com.collabcase.collaboration;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableWebSocket
@EnableScheduling
public class CollaborationWebSocketConfig implements WebSocketConfigurer {

    private static final int MAX_MESSAGE_SIZE_BYTES = 1_048_576;
    private final CollaborationWebSocketHandler handler;
    private final com.collabcase.teamai.connector.AiConnectorHandler connectorHandler;
    private final String[] allowedOrigins;

    public CollaborationWebSocketConfig(
            CollaborationWebSocketHandler handler,
            com.collabcase.teamai.connector.AiConnectorHandler connectorHandler,
            @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.handler = handler;
        this.connectorHandler = connectorHandler;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/collaboration/{diagramId}")
                .setAllowedOrigins(allowedOrigins);
        registry.addHandler(connectorHandler, "/ws/ai-connector")
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_SIZE_BYTES);
        return container;
    }
}
