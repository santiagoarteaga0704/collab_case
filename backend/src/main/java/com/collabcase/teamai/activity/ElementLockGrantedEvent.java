package com.collabcase.teamai.activity;

import java.util.UUID;

public record ElementLockGrantedEvent(UUID diagramId, UUID userId, String userName, String elementId) {
}
