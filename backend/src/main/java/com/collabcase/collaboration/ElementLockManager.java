package com.collabcase.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class ElementLockManager {

    public record ElementLock(String elementId, UUID userId, String userName, String sessionId,
            Instant acquiredAt, Instant expiresAt) {
    }

    public record LockResult(boolean granted, ElementLock lock) {
    }

    private static final long LOCK_TTL_MS = 30_000;
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, ElementLock>> locksByDiagram =
            new ConcurrentHashMap<>();
    private final CollaborationRoomManager roomManager;
    private final ObjectMapper objectMapper;

    public ElementLockManager(CollaborationRoomManager roomManager, ObjectMapper objectMapper) {
        this.roomManager = roomManager;
        this.objectMapper = objectMapper;
    }

    public LockResult acquire(UUID diagramId, String elementId, UUID userId, String userName, String sessionId) {
        Instant now = Instant.now();
        ElementLock requested = new ElementLock(elementId, userId, userName, sessionId,
                now, now.plusMillis(LOCK_TTL_MS));
        ConcurrentHashMap<String, ElementLock> locks =
                locksByDiagram.computeIfAbsent(diagramId, ignored -> new ConcurrentHashMap<>());
        ElementLock result = locks.compute(elementId, (ignored, existing) -> {
            if (existing == null || existing.expiresAt().isBefore(now) || existing.sessionId().equals(sessionId)) {
                return existing != null && existing.sessionId().equals(sessionId)
                        ? new ElementLock(elementId, userId, userName, sessionId, existing.acquiredAt(),
                                now.plusMillis(LOCK_TTL_MS))
                        : requested;
            }
            return existing;
        });
        return new LockResult(result.sessionId().equals(sessionId), result);
    }

    public boolean renew(UUID diagramId, String elementId, String sessionId) {
        ConcurrentHashMap<String, ElementLock> locks = locksByDiagram.get(diagramId);
        if (locks == null) return false;
        final boolean[] renewed = {false};
        locks.computeIfPresent(elementId, (ignored, lock) -> {
            if (!lock.sessionId().equals(sessionId)) return lock;
            renewed[0] = true;
            return new ElementLock(lock.elementId(), lock.userId(), lock.userName(), lock.sessionId(),
                    lock.acquiredAt(), Instant.now().plusMillis(LOCK_TTL_MS));
        });
        return renewed[0];
    }

    public ElementLock release(UUID diagramId, String elementId, String sessionId) {
        ConcurrentHashMap<String, ElementLock> locks = locksByDiagram.get(diagramId);
        if (locks == null) return null;
        final ElementLock[] released = {null};
        locks.computeIfPresent(elementId, (ignored, lock) -> {
            if (!lock.sessionId().equals(sessionId)) return lock;
            released[0] = lock;
            return null;
        });
        removeEmptyRoom(diagramId, locks);
        return released[0];
    }

    public List<ElementLock> releaseAll(String sessionId) {
        List<ElementLock> released = new ArrayList<>();
        locksByDiagram.forEach((diagramId, locks) -> {
            locks.forEach((elementId, lock) -> {
                if (lock.sessionId().equals(sessionId) && locks.remove(elementId, lock)) released.add(lock);
            });
            removeEmptyRoom(diagramId, locks);
        });
        return released;
    }

    public List<ElementLock> snapshot(UUID diagramId) {
        ConcurrentHashMap<String, ElementLock> locks = locksByDiagram.get(diagramId);
        if (locks == null) return List.of();
        Instant now = Instant.now();
        return locks.values().stream().filter(lock -> lock.expiresAt().isAfter(now)).toList();
    }

    @Scheduled(fixedRate = 5_000)
    public void expireLocks() {
        Instant now = Instant.now();
        locksByDiagram.forEach((diagramId, locks) -> {
            locks.forEach((elementId, lock) -> {
                if (lock.expiresAt().isBefore(now) && locks.remove(elementId, lock)) {
                    broadcastUnlocked(diagramId, elementId);
                }
            });
            removeEmptyRoom(diagramId, locks);
        });
    }

    public void broadcastUnlocked(UUID diagramId, String elementId) {
        try {
            roomManager.broadcast(diagramId, null, objectMapper.writeValueAsString(
                    java.util.Map.of("kind", "lock_changed", "elementId", elementId, "locked", false)));
        } catch (RuntimeException ignored) {
            // A failed notification must not retain an expired lock.
        }
    }

    public void clearDiagram(UUID diagramId) { locksByDiagram.remove(diagramId); }

    private void removeEmptyRoom(UUID diagramId, ConcurrentHashMap<String, ElementLock> locks) {
        if (locks.isEmpty()) locksByDiagram.remove(diagramId, locks);
    }
}
