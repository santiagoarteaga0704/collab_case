package com.collabcase.project.member.dto;

import com.collabcase.project.member.ProjectRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddProjectMemberRequest(
        @NotBlank @Email String email,
        @NotNull ProjectRole role) {
}
