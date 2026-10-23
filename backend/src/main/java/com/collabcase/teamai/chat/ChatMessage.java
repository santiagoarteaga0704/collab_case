package com.collabcase.teamai.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_messages", indexes = @Index(name = "idx_chat_project_created", columnList = "project_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "author_name")
    private String authorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChatMessageKind kind;

    @Column(name = "private_owner_id")
    private UUID privateOwnerId;

    @Column(name = "diagram_id")
    private UUID diagramId;

    @Column(columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChatMessageStatus status = ChatMessageStatus.COMPLETE;

    @Column(name = "proposal_json", columnDefinition = "text")
    private String proposalJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "proposal_state", nullable = false, length = 16)
    private ProposalState proposalState = ProposalState.NONE;

    @Column(name = "requested_by_id")
    private UUID requestedById;

    @Column(name = "requested_by_name")
    private String requestedByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
