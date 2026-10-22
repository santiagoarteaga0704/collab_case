package com.collabcase.project.member.dto;

import com.collabcase.project.member.ProjectRole;

import jakarta.validation.constraints.NotNull;

public record UpdateProjectMemberRequest(@NotNull ProjectRole role) {
}
