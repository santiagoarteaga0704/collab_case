package com.collabcase.teamai.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.teamai.chat.HelpLevel;

import jakarta.validation.constraints.NotNull;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record MessageResponse(UUID id, String authorName, UUID authorId, String kind, boolean isPrivate,
            UUID diagramId, String content, String status, List<AiSuggestion> proposal, String proposalState,
            String requestedByName, Instant createdAt) {
    }

    public record HelpLevelRequest(@NotNull HelpLevel level) {
    }

    /** aiSource: "local" (IA de la laptop), "nube" (IA de respaldo) o null; aiModel: el modelo que contesta. */
    public record StatusResponse(boolean aiConnected, int queued, String helpLevel, String aiSource, String aiModel) {
    }
}
