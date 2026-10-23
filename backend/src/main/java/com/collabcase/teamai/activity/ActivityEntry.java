package com.collabcase.teamai.activity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "project_activity", indexes = @Index(name = "idx_activity_project_at", columnList = "project_id, at"))
@Getter
@Setter
@NoArgsConstructor
public class ActivityEntry {

    public static final String EDITING = "EDITING";
    public static final String AI_CHANGE = "AI_CHANGE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "diagram_id")
    private UUID diagramId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "element_name", nullable = false)
    private String elementName;

    @Column(nullable = false, length = 16)
    private String action;

    @Column(nullable = false)
    private Instant at;
}
