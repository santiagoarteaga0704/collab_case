package com.collabcase.diagram;

import java.util.UUID;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagramRepository extends JpaRepository<Diagram, UUID> {
    List<Diagram> findByProjectIdOrderByUpdatedAtDesc(UUID projectId);
    List<Diagram> findByVersionIdOrderByUpdatedAtDesc(UUID versionId);
    List<Diagram> findByVersionId(UUID versionId);
}
