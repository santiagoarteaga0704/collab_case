package com.collabcase.teamai.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<ActivityEntry, UUID> {

    List<ActivityEntry> findTop40ByProjectIdOrderByAtDesc(UUID projectId);

    boolean existsByProjectIdAndUserIdAndElementNameAndActionAndAtAfter(
            UUID projectId, UUID userId, String elementName, String action, Instant after);
}
