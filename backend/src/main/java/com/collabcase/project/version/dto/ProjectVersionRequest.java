package com.collabcase.project.version.dto;
import java.util.UUID;import jakarta.validation.constraints.*;
public record ProjectVersionRequest(@NotBlank @Size(max=120) String name,@Size(max=1000) String description,UUID sourceVersionId){}
