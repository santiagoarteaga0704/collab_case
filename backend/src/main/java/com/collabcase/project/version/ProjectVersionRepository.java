package com.collabcase.project.version;
import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;
public interface ProjectVersionRepository extends JpaRepository<ProjectVersion,UUID>{
 List<ProjectVersion> findByProjectIdOrderByMainVersionDescCreatedAtAsc(UUID projectId);
 Optional<ProjectVersion> findByProjectIdAndMainVersionTrue(UUID projectId);
 boolean existsByProjectIdAndNameIgnoreCase(UUID projectId,String name);
 List<ProjectVersion> findByCreatedFromId(UUID versionId);
}
