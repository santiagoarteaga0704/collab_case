package com.collabcase.sequence.dto;
import java.time.Instant;import java.util.List;import java.util.UUID;
public record SequenceDiagramResponse(UUID id,UUID sourceDiagramId,String name,String scenario,String plantUml,String svg,SequenceDefinition sequence,List<String>warnings,Instant createdAt,Instant updatedAt){}
