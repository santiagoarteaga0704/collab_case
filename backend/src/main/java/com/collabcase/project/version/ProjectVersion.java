package com.collabcase.project.version;

import java.time.Instant;
import java.util.UUID;
import com.collabcase.project.Project;
import com.collabcase.user.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="project_versions",uniqueConstraints=@UniqueConstraint(name="uk_project_versions_project_name",columnNames={"project_id","name"}))
@Getter @Setter @NoArgsConstructor
public class ProjectVersion {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="project_id",nullable=false) private Project project;
    @Column(nullable=false,length=120) private String name;
    @Column(columnDefinition="text") private String description;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="created_from_id") private ProjectVersion createdFrom;
    @Column(name="main_version",nullable=false) private boolean mainVersion;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="created_by",nullable=false) private User createdBy;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void create(){createdAt=updatedAt=Instant.now();}
    @PreUpdate void update(){updatedAt=Instant.now();}
}
