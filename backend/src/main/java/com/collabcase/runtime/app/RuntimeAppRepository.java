package com.collabcase.runtime.app;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeAppRepository extends JpaRepository<RuntimeApp,UUID> {
    List<RuntimeApp> findBySourceProjectIdOrderByCreatedAtDesc(UUID projectId);
}
