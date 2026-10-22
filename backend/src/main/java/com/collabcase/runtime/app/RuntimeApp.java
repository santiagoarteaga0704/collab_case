package com.collabcase.runtime.app;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "runtime_apps", indexes = @Index(name = "idx_runtime_app_project", columnList = "source_project_id"))
@Getter @Setter @NoArgsConstructor
public class RuntimeApp {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name="source_diagram_id",nullable=false) private UUID sourceDiagramId;
    @Column(name="source_project_id",nullable=false) private UUID sourceProjectId;
    @Column(nullable=false) private String name;
    @Column(name="schema_version",nullable=false) private Integer schemaVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private Map<String,Object> definition;
    @Column(name="token_hash",nullable=false,length=64) private String tokenHash;
    @Column(nullable=false) private boolean active=true;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void create(){Instant now=Instant.now();createdAt=now;updatedAt=now;}
    @PreUpdate void update(){updatedAt=Instant.now();}
}
