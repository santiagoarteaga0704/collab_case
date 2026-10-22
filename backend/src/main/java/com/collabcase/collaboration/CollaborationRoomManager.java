package com.collabcase.collaboration;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
public class CollaborationRoomManager {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 1_048_576;

    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public WebSocketSession join(UUID diagramId, WebSocketSession session) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        rooms.computeIfAbsent(diagramId, ignored -> ConcurrentHashMap.newKeySet()).add(safeSession);
        return safeSession;
    }

    public void leave(UUID diagramId, String sessionId) {
        rooms.computeIfPresent(diagramId, (ignored, sessions) -> {
            sessions.removeIf(session -> session.getId().equals(sessionId) || !session.isOpen());
            return sessions.isEmpty() ? null : sessions;
        });
    }

    public void broadcast(UUID diagramId, String senderId, String payload) {
        Set<WebSocketSession> sessions = rooms.get(diagramId);
        if (sessions == null) return;

        TextMessage message = new TextMessage(payload);
        sessions.removeIf(session -> {
            if (!session.isOpen()) return true;
            if (session.getId().equals(senderId)) return false;
            try {
                session.sendMessage(message);
                return false;
            } catch (IOException exception) {
                try {
                    session.close();
                } catch (IOException ignored) {
                    // The session is already unusable.
                }
                return true;
            }
        });
        if (sessions.isEmpty()) rooms.remove(diagramId, sessions);
    }

    public boolean hasActiveSessions(UUID diagramId) {
        Set<WebSocketSession> sessions = rooms.get(diagramId);
        if (sessions == null) return false;
        sessions.removeIf(session -> !session.isOpen());
        if (sessions.isEmpty()) rooms.remove(diagramId, sessions);
        return !sessions.isEmpty();
    }

    public int activeSessionCount(UUID diagramId) {
        hasActiveSessions(diagramId);
        Set<WebSocketSession> sessions = rooms.get(diagramId);
        return sessions == null ? 0 : sessions.size();
    }

    public void closeRoom(UUID diagramId) {
        Set<WebSocketSession> sessions = rooms.remove(diagramId);
        if (sessions == null) return;
        sessions.forEach(session -> { try { if (session.isOpen()) session.close(); } catch (IOException ignored) {} });
    }
}
