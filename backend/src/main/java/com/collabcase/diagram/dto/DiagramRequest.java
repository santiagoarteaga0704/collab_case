package com.collabcase.diagram.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DiagramRequest(
        @NotBlank @Size(max=120) String name,
        @NotBlank @Pattern(regexp = "ClassDiagram") String type,
        @NotNull Map<String, Object> model) {
}
