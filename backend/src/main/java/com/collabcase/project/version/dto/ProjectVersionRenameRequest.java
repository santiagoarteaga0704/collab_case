package com.collabcase.project.version.dto;
import jakarta.validation.constraints.*;
public record ProjectVersionRenameRequest(@NotBlank @Size(max=120) String name){}
