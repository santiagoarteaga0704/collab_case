package com.collabcase.project;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.collabcase.project.member.ProjectMember;
import com.collabcase.project.member.ProjectMemberRepository;

@Service
public class ProjectAccessService {

    public enum EffectiveRole {
        OWNER,
        EDITOR,
        VIEWER
    }

    public record Access(Project project, EffectiveRole role) {
    }

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;

    public ProjectAccessService(ProjectRepository projectRepository, ProjectMemberRepository memberRepository) {
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public Access requireRead(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> notFound("Project not found"));

        if (project.getOwner().getId().equals(userId)) {
            return new Access(project, EffectiveRole.OWNER);
        }

        ProjectMember member = memberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> notFound("Project not found"));
        return new Access(project, EffectiveRole.valueOf(member.getRole().name()));
    }

    @Transactional(readOnly = true)
    public Access requireOwner(UUID projectId, UUID userId) {
        Access access = requireRead(projectId, userId);
        if (access.role() != EffectiveRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the project owner can perform this action");
        }
        return access;
    }

    @Transactional(readOnly = true)
    public Access requireDiagramEditor(UUID projectId, UUID userId) {
        Access access = requireRead(projectId, userId);
        if (access.role() == EffectiveRole.VIEWER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        }
        return access;
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
