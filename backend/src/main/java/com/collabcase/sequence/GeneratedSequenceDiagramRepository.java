package com.collabcase.sequence;
import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;
public interface GeneratedSequenceDiagramRepository extends JpaRepository<GeneratedSequenceDiagram,UUID>{List<GeneratedSequenceDiagram> findBySourceDiagramIdOrderByUpdatedAtDesc(UUID diagramId);void deleteBySourceDiagramId(UUID diagramId);}
