package com.collabcase.teamai.connector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Extremo servidor del conector que corre en la laptop con la IA local. */
@Component
public class AiConnectorHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AiConnectorHandler.class);
    private static final CloseStatus BAD_SECRET = new CloseStatus(4001, "Bad secret");
    private static final int MAX_MESSAGE_SIZE_BYTES = 1_048_576;
    /** Un conector que no contesta en este tiempo (tres pings) se da por caído. */
    static final Duration SILENCE_LIMIT = Duration.ofSeconds(60);

    private final class SessionLink implements ConnectorLink {
        final WebSocketSession socket;
        /** Última vez que el conector mandó algo (pong, chunk, done...). */
        volatile Instant lastHeard;

        SessionLink(WebSocketSession raw) {
            this.socket = new ConcurrentWebSocketSessionDecorator(raw, 10_000, MAX_MESSAGE_SIZE_BYTES);
            this.lastHeard = clock.instant();
        }

        @Override
        public void send(Map<String, Object> payload) {
            try {
                socket.sendMessage(new TextMessage(json.writeValueAsString(payload)));
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo escribir al conector", e);
            }
        }
    }

    private final AiJobQueue queue;
    private final ObjectMapper json;
    private final byte[] secret;
    private final ConcurrentHashMap<String, SessionLink> links = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public AiConnectorHandler(AiJobQueue queue, ObjectMapper json,
            @Value("${teamai.connector.secret:}") String secret) {
        this(queue, json, secret, Clock.systemUTC());
    }

    AiConnectorHandler(AiJobQueue queue, ObjectMapper json, String secret, Clock clock) {
        this.queue = queue;
        this.json = json;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }


    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > MAX_MESSAGE_SIZE_BYTES) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        JsonNode envelope;
        try {
            envelope = json.readTree(message.getPayload());
        } catch (RuntimeException e) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        String kind = envelope.path("kind").asText();
        SessionLink link = links.get(session.getId());
        if (link == null) {
            if ("hello".equals(kind)) {
                hello(session, envelope);
            } else {
                session.close(BAD_SECRET);
            }
            return;
        }
        link.lastHeard = clock.instant();
        switch (kind) {
            case "chunk" -> withId(envelope, id -> queue.onChunk(id, envelope.path("text").asText("")));
            case "done" -> withId(envelope, id -> queue.onFinished(id, envelope.path("content").asText("")));
            case "error" -> withId(envelope, id -> queue.onFailed(id, envelope.path("detail").asText("Error de la IA")));
            default -> { } // pong y desconocidos: nada que hacer
        }
    }

    private void hello(WebSocketSession session, JsonNode envelope) throws IOException {
        byte[] given = envelope.path("secret").asText("").getBytes(StandardCharsets.UTF_8);
        if (secret.length == 0 || !MessageDigest.isEqual(secret, given)) {
            session.close(BAD_SECRET);
            return;
        }
        SessionLink link = new SessionLink(session);
        links.put(session.getId(), link);
        queue.attach(link, envelope.path("model").asText(""));
        link.send(Map.of("kind", "ready"));
    }

    private void withId(JsonNode envelope, java.util.function.Consumer<UUID> action) {
        UUID id;
        try {
            id = UUID.fromString(envelope.path("id").asText(""));
        } catch (IllegalArgumentException e) {
            return;
        }
        action.accept(id);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Error de transporte en el conector de IA", exception);
        detach(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        detach(session);
    }

    private void detach(WebSocketSession session) {
        SessionLink link = links.remove(session.getId());
        if (link != null) {
            queue.detach(link);
        }
    }

    /**
     * Cada 20 s: un ping a cada conector (el conector se reconecta si pasa 50 s sin uno) y fuera los
     * que llevan {@link #SILENCE_LIMIT} sin decir nada, que si no recibirían trabajos al vacío.
     */
    @Scheduled(fixedRate = 20_000)
    void ping() {
        Instant now = clock.instant();
        for (Map.Entry<String, SessionLink> entry : links.entrySet()) {
            SessionLink link = entry.getValue();
            if (link.lastHeard.plus(SILENCE_LIMIT).isBefore(now)) {
                if (links.remove(entry.getKey(), link)) {
                    log.warn("El conector de IA lleva {} s sin responder: se desconecta", SILENCE_LIMIT.toSeconds());
                    queue.detach(link);
                    try {
                        link.socket.close(CloseStatus.SESSION_NOT_RELIABLE);
                    } catch (IOException | RuntimeException e) {
                        log.debug("No se pudo cerrar el conector mudo", e);
                    }
                }
                continue;
            }
            try {
                link.send(Map.of("kind", "ping"));
            } catch (RuntimeException e) {
                log.debug("Ping fallido al conector", e);
            }
        }
    }
}
