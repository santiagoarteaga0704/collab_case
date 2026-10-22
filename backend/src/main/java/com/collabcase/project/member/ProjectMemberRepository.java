package com.collabcase.project.member;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {
    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    List<ProjectMember> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    List<ProjectMember> findByUserId(UUID userId);

    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

    void deleteByProjectIdAndUserId(UUID projectId, UUID userId);
    void deleteByProjectId(UUID projectId);
}
