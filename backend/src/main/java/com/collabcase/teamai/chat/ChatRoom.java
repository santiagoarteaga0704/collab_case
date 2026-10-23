package com.collabcase.teamai.chat;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import tools.jackson.databind.ObjectMapper;

/** Sesiones conectadas al chat, agrupadas por proyecto, y envío de eventos a ellas. */
@Component
public class ChatRoom {

    private static final Logger log = LoggerFactory.getLogger(ChatRoom.class);

    private final ObjectMapper json;
    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();
    /** Orden de llegada de cada sesión, para elegir la pestaña más reciente. */
    private final ConcurrentHashMap<String, Long> joinOrder = new ConcurrentHashMap<>();
    private final AtomicLong joins = new AtomicLong();

    public ChatRoom(ObjectMapper json) {
        this.json = json;
    }

    public void join(UUID projectId, ChatSession s) {
        sessions.put(s.id(), s);
        joinOrder.put(s.id(), joins.incrementAndGet());
    }

    public void leave(String sessionId) {
        sessions.remove(sessionId);
        joinOrder.remove(sessionId);
    }

    /** Mensaje nuevo o actualizado: si es privado, solo llega a las sesiones de su dueño. */
    public void publish(ChatMessage m, Map<String, Object> payload) {
        UUID owner = m.getPrivateOwnerId();
        sendWhere(s -> s.projectId().equals(m.getProjectId()) && (owner == null || owner.equals(s.userId())), payload);
    }

    public void toAll(UUID projectId, Map<String, Object> payload) {
        sendWhere(s -> s.projectId().equals(projectId), payload);
    }

    /** A todas las sesiones de todos los proyectos (estado global de la IA). */
    public void toEveryone(Map<String, Object> payload) {
        sendWhere(s -> true, payload);
    }

    public void toUser(UUID projectId, UUID userId, Map<String, Object> payload) {
        sendWhere(s -> s.projectId().equals(projectId) && s.userId().equals(userId), payload);
    }

    /**
     * Envía a UNA sola sesión del usuario con ese diagrama abierto, la más reciente (si falla, la
     * anterior), para que el cambio no se aplique una vez por pestaña; devuelve si lo hizo.
     */
    public boolean toUserInDiagram(UUID projectId, UUID userId, UUID diagramId, Map<String, Object> payload) {
        if (diagramId == null) {
            return false;
        }
        String text = serialize(payload);
        if (text == null) {
            return false;
        }
        List<ChatSession> tabs = sessions.values().stream()
                .filter(s -> s.projectId().equals(projectId) && s.userId().equals(userId)
                        && diagramId.equals(s.diagramId()))
                .sorted(Comparator.comparingLong((ChatSession s) -> joinOrder.getOrDefault(s.id(), 0L)).reversed())
                .toList();
        for (ChatSession s : tabs) {
            if (trySend(s, text)) {
                return true;
            }
        }
        return false;
    }

    public void send(ChatSession s, Map<String, Object> payload) {
        sendWhere(x -> x.id().equals(s.id()), payload);
    }

    private int sendWhere(Predicate<ChatSession> filter, Map<String, Object> payload) {
        String text = serialize(payload);
        if (text == null) {
            return 0;
        }
        int sent = 0;
        for (ChatSession s : sessions.values()) {
            if (filter.test(s) && trySend(s, text)) {
                sent++;
            }
        }
        return sent;
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("No se pudo serializar un evento del chat", e);
            return null;
        }
    }

    /** Envía a una sesión; si está cerrada o falla, la saca de la sala y devuelve false. */
    private boolean trySend(ChatSession s, String text) {
        try {
            if (s.socket().isOpen()) {
                s.socket().sendMessage(new TextMessage(text));
                return true;
            }
            leave(s.id());
        } catch (IOException | RuntimeException e) {
            leave(s.id());
            try {
                s.socket().close();
            } catch (IOException | RuntimeException ignored) {
                // ya inutilizable
            }
        }
        return false;
    }
}
