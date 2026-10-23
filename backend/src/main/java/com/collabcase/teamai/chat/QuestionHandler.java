package com.collabcase.teamai.chat;

import java.util.List;
import java.util.UUID;

/** Atiende las preguntas dirigidas a la IA que llegan por el chat en vivo. */
public interface QuestionHandler {

    record Failure(String name, String reason) {
    }

    void handle(ChatMessage question, ChatSession asker);

    default void applied(ChatSession who, UUID messageId, List<String> applied, List<Failure> failed) {
    }

    default void applyProposal(ChatSession who, UUID messageId) {
    }
}
