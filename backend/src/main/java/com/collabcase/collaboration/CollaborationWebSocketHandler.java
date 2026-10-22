package com.collabcase.collaboration;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.collabcase.auth.JwtService;
import com.collabcase.diagram.DiagramService;
import com.collabcase.diagram.DiagramRenamedEvent;
import com.collabcase.diagram.DiagramDeletedEvent;
import com.collabcase.project.ProjectAccessService.Access;
import com.collabcase.project.ProjectAccessService.EffectiveRole;
import com.collabcase.user.User;
import com.collabcase.user.UserService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CollaborationWebSocketHandler extends TextWebSocketHandler {
    private static final int MAX_MESSAGE_SIZE_BYTES = 1_048_576;
    private static final long HEARTBEAT_TIMEOUT_MS = 60_000;
    private static final CloseStatus AUTH_FAILED = new CloseStatus(4001, "Authentication failed");
    private static final CloseStatus ACCESS_REVOKED = new CloseStatus(4004, "Access revoked");

    private static final class ClientSession {
        final UUID diagramId;
        final UUID projectId;
        final UUID userId;
        final String userName;
        final WebSocketSession socket;
        final Map<Long, Long> awarenessClients = AwarenessProtocol.newClientMap();
        volatile EffectiveRole role;
        volatile long lastPongAt = System.currentTimeMillis();

        ClientSession(UUID diagramId, UUID projectId, User user, EffectiveRole role, WebSocketSession socket) {
            this.diagramId = diagramId;
            this.projectId = projectId;
            this.userId = user.getId();
            this.userName = user.getName();
            this.role = role;
            this.socket = socket;
        }
    }

    private final JwtService jwtService;
    private final UserService userService;
    private final DiagramService diagramService;
    private final CollaborationRoomManager rooms;
    private final ElementLockManager locks;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, ClientSession> clients = new ConcurrentHashMap<>();

    public CollaborationWebSocketHandler(JwtService jwtService, UserService userService, DiagramService diagramService,
            CollaborationRoomManager rooms, ElementLockManager locks, ObjectMapper json) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.diagramService = diagramService;
        this.rooms = rooms;
        this.locks = locks;
        this.json = json;
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
        } catch (RuntimeException exception) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        ClientSession client = clients.get(session.getId());
        if (client == null) {
            authenticate(session, envelope);
            return;
        }
        switch (envelope.path("kind").asText()) {
            case "yjs" -> handleYjs(client, envelope);
            case "lock_request" -> handleLockRequest(client, envelope);
            case "lock_renew" -> handleLockRenew(client, envelope);
            case "lock_release" -> handleLockRelease(client, envelope);
            default -> session.close(CloseStatus.BAD_DATA);
        }
    }

    private void authenticate(WebSocketSession session, JsonNode envelope) throws IOException {
        if (!"auth".equals(envelope.path("kind").asText()) || !envelope.path("token").isTextual()) {
            rejectAuthentication(session);
            return;
        }
        try {
            UUID diagramId = extractDiagramId(session.getUri());
            UUID userId = jwtService.extractUserId(envelope.path("token").asText());
            User user = userService.findById(userId).orElseThrow();
            Access access = diagramService.requireCollaborationReadAccess(diagramId, userId);
            WebSocketSession safeSocket = rooms.join(diagramId, session);
            ClientSession client = new ClientSession(diagramId, access.project().getId(), user, access.role(), safeSocket);
            clients.put(session.getId(), client);
            send(safeSocket, Map.of("kind", "auth_ok", "role", access.role().name()));
            send(safeSocket, Map.of("kind", "locks_snapshot", "locks", lockPayloads(locks.snapshot(diagramId))));
        } catch (Exception exception) {
            removeSession(session.getId());
            rejectAuthentication(session);
        }
    }

    private void handleYjs(ClientSession client, JsonNode envelope) throws IOException {
        if (!envelope.path("data").isTextual()) return;
        String data = envelope.path("data").asText();
        int frameType = AwarenessProtocol.frameType(data);
        EffectiveRole currentRole = refreshRole(client);
        if (currentRole == null) return;
        // VIEWER may request sync (0/2) and publish Awareness (3), but never a document update (1).
        if (currentRole == EffectiveRole.VIEWER && frameType == 1) return;
        if (frameType == 3) AwarenessProtocol.track(data, client.awarenessClients);
        rooms.broadcast(client.diagramId, client.socket.getId(),
                json.writeValueAsString(Map.of("kind", "yjs", "data", data)));
    }

    private void handleLockRequest(ClientSession client, JsonNode envelope) throws IOException {
        String elementId = validElementId(envelope);
        if (elementId == null || !canEdit(client)) return;
        ElementLockManager.LockResult result = locks.acquire(
                client.diagramId, elementId, client.userId, client.userName, client.socket.getId());
        ElementLockManager.ElementLock lock = result.lock();
        if (result.granted()) {
            send(client.socket, Map.of("kind", "lock_granted", "elementId", elementId,
                    "userId", client.userId.toString(), "userName", client.userName));
            rooms.broadcast(client.diagramId, client.socket.getId(), json.writeValueAsString(Map.of(
                    "kind", "lock_changed", "elementId", elementId, "locked", true,
                    "userId", client.userId.toString(), "userName", client.userName)));
        } else {
            send(client.socket, Map.of("kind", "lock_denied", "elementId", elementId,
                    "userId", lock.userId().toString(), "userName", lock.userName()));
        }
    }

    private void handleLockRenew(ClientSession client, JsonNode envelope) throws IOException {
        String elementId = validElementId(envelope);
        if (elementId != null && canEdit(client)) locks.renew(client.diagramId, elementId, client.socket.getId());
    }

    private void handleLockRelease(ClientSession client, JsonNode envelope) {
        String elementId = validElementId(envelope);
        if (elementId != null && locks.release(client.diagramId, elementId, client.socket.getId()) != null) {
            locks.broadcastUnlocked(client.diagramId, elementId);
        }
    }

    private EffectiveRole refreshRole(ClientSession client) throws IOException {
        try {
            EffectiveRole role = diagramService.requireCollaborationReadAccess(client.diagramId, client.userId).role();
            client.role = role;
            return role;
        } catch (ResponseStatusException exception) {
            revoke(client);
            return null;
        }
    }

    private boolean canEdit(ClientSession client) throws IOException {
        EffectiveRole role = refreshRole(client);
        return role == EffectiveRole.OWNER || role == EffectiveRole.EDITOR;
    }

    public void notifyRoleChanged(UUID projectId, UUID userId, EffectiveRole role) {
        clients.values().stream().filter(c -> c.projectId.equals(projectId) && c.userId.equals(userId)).forEach(client -> {
            client.role = role;
            if (role == EffectiveRole.VIEWER) releaseLocks(client.diagramId, client.socket.getId());
            try { send(client.socket, Map.of("kind", "role_changed", "role", role.name())); }
            catch (IOException ignored) { closeAndRemove(client); }
        });
    }

    public void notifyAccessRevoked(UUID projectId, UUID userId) {
        clients.values().stream().filter(c -> c.projectId.equals(projectId) && c.userId.equals(userId))
                .toList().forEach(this::revokeQuietly);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void diagramDeleted(DiagramDeletedEvent event) {
        clients.entrySet().removeIf(entry -> {
            if (!entry.getValue().diagramId.equals(event.diagramId())) return false;
            try { if (entry.getValue().socket.isOpen()) entry.getValue().socket.close(new CloseStatus(4004, "Diagram deleted")); } catch (IOException ignored) {}
            return true;
        });
        locks.clearDiagram(event.diagramId());
        rooms.closeRoom(event.diagramId());
    }

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT) public void notifyDiagramRenamed(DiagramRenamedEvent event){rooms.broadcast(event.diagramId(),"",json.writeValueAsString(Map.of("kind","diagram_renamed","diagramId",event.diagramId().toString(),"name",event.name())));}

    private void revoke(ClientSession client) throws IOException {
        send(client.socket, Map.of("kind", "access_revoked"));
        client.socket.close(ACCESS_REVOKED);
        removeSession(client.socket.getId());
    }

    private void revokeQuietly(ClientSession client) {
        try { revoke(client); } catch (IOException ignored) { removeSession(client.socket.getId()); }
    }

    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        long staleBefore = System.currentTimeMillis() - HEARTBEAT_TIMEOUT_MS;
        clients.values().forEach(client -> {
            try {
                if (!client.socket.isOpen() || client.lastPongAt < staleBefore) closeAndRemove(client);
                else client.socket.sendMessage(new PingMessage());
            } catch (IOException exception) { closeAndRemove(client); }
        });
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        ClientSession client = clients.get(session.getId());
        if (client != null) client.lastPongAt = System.currentTimeMillis();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { removeSession(session.getId()); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        removeSession(session.getId());
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    private void removeSession(String sessionId) {
        ClientSession client = clients.remove(sessionId);
        if (client == null) return;
        releaseLocks(client.diagramId, sessionId);
        String removal = AwarenessProtocol.removalFrame(client.awarenessClients);
        rooms.leave(client.diagramId, sessionId);
        if (removal != null) {
            try { rooms.broadcast(client.diagramId, sessionId,
                    json.writeValueAsString(Map.of("kind", "yjs", "data", removal))); }
            catch (RuntimeException ignored) { }
        }
    }

    private void releaseLocks(UUID diagramId, String sessionId) {
        locks.releaseAll(sessionId).forEach(lock -> locks.broadcastUnlocked(diagramId, lock.elementId()));
    }

    private void closeAndRemove(ClientSession client) {
        try { client.socket.close(); } catch (IOException ignored) { }
        removeSession(client.socket.getId());
    }

    private List<Map<String, String>> lockPayloads(List<ElementLockManager.ElementLock> values) {
        return values.stream().map(lock -> Map.of("elementId", lock.elementId(),
                "userId", lock.userId().toString(), "userName", lock.userName())).toList();
    }

    private String validElementId(JsonNode envelope) {
        String value = envelope.path("elementId").asText("");
        return value.isBlank() || value.length() > 256 ? null : value;
    }

    private UUID extractDiagramId(URI uri) {
        if (uri == null) throw new IllegalArgumentException("Missing diagram id");
        String path = uri.getPath();
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }

    private void rejectAuthentication(WebSocketSession session) throws IOException {
        send(session, Map.of("kind", "auth_error"));
        session.close(AUTH_FAILED);
    }

    private void send(WebSocketSession session, Map<String, ?> message) throws IOException {
        if (session.isOpen()) session.sendMessage(new TextMessage(json.writeValueAsString(message)));
    }
}
