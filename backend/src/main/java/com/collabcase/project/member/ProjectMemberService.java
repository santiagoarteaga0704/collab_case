package com.collabcase.project.member;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.collabcase.project.Project;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.project.ProjectAccessService.EffectiveRole;
import com.collabcase.collaboration.CollaborationWebSocketHandler;
import com.collabcase.project.member.dto.AddProjectMemberRequest;
import com.collabcase.project.member.dto.ProjectMemberResponse;
import com.collabcase.project.member.dto.UpdateProjectMemberRequest;
import com.collabcase.user.User;
import com.collabcase.user.UserService;

@Service
public class ProjectMemberService {

    private final ProjectMemberRepository memberRepository;
    private final UserService userService;
    private final ProjectAccessService accessService;
    private final CollaborationWebSocketHandler collaborationHandler;

    public ProjectMemberService(
            ProjectMemberRepository memberRepository,
            UserService userService,
            ProjectAccessService accessService,
            CollaborationWebSocketHandler collaborationHandler) {
        this.memberRepository = memberRepository;
        this.userService = userService;
        this.accessService = accessService;
        this.collaborationHandler = collaborationHandler;
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> findAll(UUID projectId, UUID requesterId) {
        Project project = accessService.requireOwner(projectId, requesterId).project();
        List<ProjectMemberResponse> responses = new ArrayList<>();
        User owner = project.getOwner();
        responses.add(new ProjectMemberResponse(
                owner.getId(), owner.getName(), owner.getEmail(), "OWNER", project.getCreatedAt()));
        memberRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(this::toResponse)
                .forEach(responses::add);
        return responses;
    }

    @Transactional
    public ProjectMemberResponse add(UUID projectId, AddProjectMemberRequest request, UUID requesterId) {
        Project project = accessService.requireOwner(projectId, requesterId).project();
        User user = userService.findByEmail(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        rejectOwner(project, user.getId());
        if (memberRepository.existsByProjectIdAndUserId(projectId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a project member");
        }

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(request.role());
        try {
            return toResponse(memberRepository.saveAndFlush(member));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a project member", exception);
        }
    }

    @Transactional
    public ProjectMemberResponse update(
            UUID projectId,
            UUID userId,
            UpdateProjectMemberRequest request,
            UUID requesterId) {
        Project project = accessService.requireOwner(projectId, requesterId).project();
        rejectOwner(project, userId);
        ProjectMember member = findMember(projectId, userId);
        member.setRole(request.role());
        ProjectMemberResponse response = toResponse(memberRepository.saveAndFlush(member));
        afterCommit(() -> collaborationHandler.notifyRoleChanged(
                projectId, userId, EffectiveRole.valueOf(request.role().name())));
        return response;
    }

    @Transactional
    public void remove(UUID projectId, UUID userId, UUID requesterId) {
        Project project = accessService.requireOwner(projectId, requesterId).project();
        rejectOwner(project, userId);
        ProjectMember member = findMember(projectId, userId);
        memberRepository.delete(member);
        afterCommit(() -> collaborationHandler.notifyAccessRevoked(projectId, userId));
    }

    private ProjectMember findMember(UUID projectId, UUID userId) {
        return memberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project member not found"));
    }

    private void rejectOwner(Project project, UUID userId) {
        if (project.getOwner().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The project owner cannot be a member");
        }
    }

    private ProjectMemberResponse toResponse(ProjectMember member) {
        User user = member.getUser();
        return new ProjectMemberResponse(
                user.getId(), user.getName(), user.getEmail(), member.getRole().name(), member.getCreatedAt());
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
