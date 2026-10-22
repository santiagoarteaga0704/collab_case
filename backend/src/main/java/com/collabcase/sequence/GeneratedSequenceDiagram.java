package com.collabcase.sequence;
import java.time.Instant;import java.util.UUID;import com.collabcase.diagram.Diagram;import jakarta.persistence.*;import lombok.*;
@Entity @Table(name="generated_sequence_diagrams") @Getter @Setter @NoArgsConstructor
public class GeneratedSequenceDiagram{
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="source_diagram_id",nullable=false) private Diagram sourceDiagram;
 @Column(nullable=false,length=120) private String name;
 @Column(nullable=false,columnDefinition="text") private String scenario;
 @Column(name="plant_uml",nullable=false,columnDefinition="text") private String plantUml;
 @Column(name="sequence_json",nullable=false,columnDefinition="text") private String sequenceJson;
 @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
 @Column(name="updated_at",nullable=false) private Instant updatedAt;
 @PrePersist void onCreate(){createdAt=updatedAt=Instant.now();}@PreUpdate void onUpdate(){updatedAt=Instant.now();}
}
