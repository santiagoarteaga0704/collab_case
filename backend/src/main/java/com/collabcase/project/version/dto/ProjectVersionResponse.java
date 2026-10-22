package com.collabcase.project.version.dto;
import java.time.Instant;import java.util.UUID;
public record ProjectVersionResponse(UUID id,String name,String description,UUID createdFromId,boolean mainVersion,Instant createdAt,Instant updatedAt){}
