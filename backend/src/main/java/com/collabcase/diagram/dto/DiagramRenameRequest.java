package com.collabcase.diagram.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record DiagramRenameRequest(@NotBlank @Size(max=120) String name) {}
