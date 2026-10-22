package com.collabcase.sequence.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record SequenceGenerateRequest(@NotBlank @Size(max=120) String name,@NotBlank @Size(max=4000) String scenario,@Size(max=120) String entryClass,@Size(max=120) String entryMethod) {}
