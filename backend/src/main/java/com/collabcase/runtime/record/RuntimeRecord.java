package com.collabcase.runtime.record;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.collabcase.runtime.app.RuntimeApp;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name="runtime_records",uniqueConstraints=@UniqueConstraint(name="uk_runtime_record",columnNames={"app_id","entity_type","record_id"}),indexes={@Index(name="idx_runtime_record_type",columnList="app_id,entity_type"),@Index(name="idx_runtime_record_sync",columnList="app_id,sync_version")})
@Getter @Setter @NoArgsConstructor
public class RuntimeRecord {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="app_id",nullable=false) private RuntimeApp app;
    @Column(name="entity_type",nullable=false) private String entityType;
    @Column(name="record_id",nullable=false) private UUID recordId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private Map<String,Object> data;
    @Column(nullable=false) private long version;
    @Column(name="sync_version",nullable=false) private long syncVersion;
    @Column(nullable=false) private boolean deleted;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void create(){Instant now=Instant.now();createdAt=now;updatedAt=now;}
    @PreUpdate void update(){updatedAt=Instant.now();}
}
