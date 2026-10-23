package com.collabcase.teamai.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.collabcase.auth.JwtService;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.project.ProjectAccessService.Access;
import com.collabcase.teamai.chat.QuestionHandler.Failure;
import com.collabcase.teamai.connector.AiJobQueue;
import com.collabcase.user.User;
import com.collabcase.user.UserService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final int MAX_MESSAGE_SIZE_BYTES = 65_536;
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 1_048_576;
    private static final CloseStatus AUTH_FAILED = new CloseStatus(4001, "Authentication failed");

    private final JwtService jwtService;
    private final UserService userService;
    private final ProjectAccessService access;
    private final ChatService chat;
    private final ChatRoom room;
    private final AiJobQueue queue;
    private final ObjectProvider<QuestionHandler> questionHandler;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, ChatSession> clients = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(JwtService jwtService, UserService userService, ProjectAccessService access,
            ChatService chat, ChatRoom room, AiJobQueue queue, ObjectProvider<QuestionHandler> questionHandler,
            ObjectMapper json) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.access = access;
        this.chat = chat;
        this.room = room;
        this.queue = queue;
        this.questionHandler = questionHandler;
        this.json = json;
        queue.onChange(() -> room.toEveryone(aiStatus()));
    }

    private Map<String, Object> aiStatus() {
        return Map.of("kind", "ai_status", "connected", queue.connected(), "queued", queue.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > MAX_MESSAGE_SIZE_BYTES) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        ChatSession client = clients.get(session.getId());
        JsonNode envelope;
        try {
            envelope = json.readTree(message.getPayload());
        } catch (RuntimeException e) {
            if (client == null) {
                reject(session);
            } else {
                error(client, "Mensaje inválido");
            }
            return;
        }
        if (client == null) {
            authenticate(session, envelope);
            return;
        }
        try {
            switch (envelope.path("kind").asText()) {
                case "send" -> handleSend(client, envelope);
                case "changes_applied" -> handleApplied(client, envelope);
                case "apply_proposal" -> handleApplyProposal(client, envelope);
                default -> error(client, "Tipo de mensaje desconocido");
            }
        } catch (ResponseStatusException e) {
            error(client, e.getReason() == null ? "No se pudo procesar el mensaje" : e.getReason());
        } catch (IllegalArgumentException e) {
            error(client, "Mensaje inválido");
        } catch (RuntimeException e) {
            log.warn("Falló el procesamiento de un mensaje del chat", e);
            error(client, "Algo salió mal al procesar tu mensaje. Inténtalo de nuevo");
        }
    }

    private void authenticate(WebSocketSession session, JsonNode envelope) throws IOException {
        if (!"auth".equals(envelope.path("kind").asText()) || !envelope.path("token").isTextual()) {
            reject(session);
            return;
        }
        try {
            UUID projectId = projectIdOf(session);
            UUID userId = jwtService.extractUserId(envelope.path("token").asText());
            User user = userService.findById(userId).orElseThrow();
            Access granted = access.requireRead(projectId, userId);
            UUID diagramId = envelope.path("diagramId").isTextual()
                    ? UUID.fromString(envelope.path("diagramId").asText()) : null;
            WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(
                    session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
            ChatSession client = new ChatSession(session.getId(), projectId, userId, user.getName(), diagramId,
                    granted.role(), safe);
            clients.put(session.getId(), client);
            room.join(projectId, client);
            room.send(client, Map.of("kind", "auth_ok", "role", granted.role().name()));
            room.send(client, aiStatus());
        } catch (Exception e) {
            remove(session.getId());
            reject(session);
        }
    }

    private void handleSend(ChatSession client, JsonNode envelope) {
        boolean isPrivate = envelope.path("private").asBoolean(false);
        UUID diagramId = envelope.path("diagramId").isTextual()
                ? UUID.fromString(envelope.path("diagramId").asText()) : client.diagramId();
        ChatMessage saved = chat.postFromPerson(client.projectId(), client.userId(),
                envelope.path("content").asText(""), isPrivate, diagramId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "message");
        payload.put("message", chat.toResponse(saved));
        room.publish(saved, payload);
        if (isPrivate || ChatService.mentionsAi(saved.getContent())) {
            QuestionHandler handler = questionHandler.getIfAvailable();
            if (handler != null) {
                handler.handle(saved, client);
            }
        }
    }

    private void handleApplied(ChatSession client, JsonNode envelope) {
        QuestionHandler handler = questionHandler.getIfAvailable();
        if (handler == null) {
            return;
        }
        UUID messageId = UUID.fromString(envelope.path("messageId").asText());
        List<String> applied = new ArrayList<>();
        envelope.path("applied").forEach(n -> applied.add(n.asText()));
        List<Failure> failed = new ArrayList<>();
        envelope.path("failed")
                .forEach(n -> failed.add(new Failure(n.path("name").asText(), n.path("reason").asText())));
        handler.applied(client, messageId, applied, failed);
    }

    private void handleApplyProposal(ChatSession client, JsonNode envelope) {
        QuestionHandler handler = questionHandler.getIfAvailable();
        if (handler == null) {
            return;
        }
        handler.applyProposal(client, UUID.fromString(envelope.path("messageId").asText()));
    }

    private void error(ChatSession client, String message) {
        room.send(client, Map.of("kind", "error", "message", message));
    }

    private void reject(WebSocketSession session) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(json.writeValueAsString(Map.of("kind", "auth_error"))));
        }
        session.close(AUTH_FAILED);
    }

    private UUID projectIdOf(WebSocketSession session) {
        if (session.getUri() == null) {
            throw new IllegalArgumentException("Missing project id");
        }
        String path = session.getUri().getPath();
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        remove(session.getId());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void remove(String sessionId) {
        clients.remove(sessionId);
        room.leave(sessionId);
    }
}
