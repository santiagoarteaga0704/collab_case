package com.collabcase.teamai.chat;

import java.util.UUID;

import org.springframework.web.socket.WebSocketSession;

import com.collabcase.project.ProjectAccessService.EffectiveRole;

/** Una conexión del chat en vivo. {@code role} es el rol que tenía al autenticarse. */
public record ChatSession(String id, UUID projectId, UUID userId, String userName, UUID diagramId,
        EffectiveRole role, WebSocketSession socket) {
}
