package com.collabcase.sequence.dto;
import jakarta.validation.constraints.Size;
public record SequenceUpdateRequest(@Size(max=120) String name,@Size(max=4000) String scenario) {}
