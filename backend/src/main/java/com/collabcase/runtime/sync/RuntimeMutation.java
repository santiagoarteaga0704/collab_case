package com.collabcase.runtime.sync;

import java.time.Instant;
import java.util.UUID;
import com.collabcase.runtime.app.RuntimeApp;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name="runtime_mutations",uniqueConstraints=@UniqueConstraint(name="uk_runtime_mutation",columnNames={"app_id","mutation_id"}))
@Getter @Setter @NoArgsConstructor
public class RuntimeMutation {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="app_id",nullable=false) private RuntimeApp app;
    @Column(name="mutation_id",nullable=false) private UUID mutationId;
    @Column(name="processed_at",nullable=false) private Instant processedAt;
    @PrePersist void create(){processedAt=Instant.now();}
}
