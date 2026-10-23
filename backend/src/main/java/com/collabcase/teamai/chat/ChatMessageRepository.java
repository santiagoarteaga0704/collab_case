package com.collabcase.teamai.chat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    @Query("select m from ChatMessage m where m.projectId=:p and (m.privateOwnerId is null or m.privateOwnerId=:u) order by m.createdAt desc, m.id desc")
    List<ChatMessage> visibleTo(@Param("p") UUID p, @Param("u") UUID u, Pageable page);

    List<ChatMessage> findByKindAndProposalStateAndProposalJsonIsNotNullAndCreatedAtBefore(ChatMessageKind kind,
            ProposalState state, Instant createdBefore);

    List<ChatMessage> findByStatusIn(Collection<ChatMessageStatus> statuses);
}
