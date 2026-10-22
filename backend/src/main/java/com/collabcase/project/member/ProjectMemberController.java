package com.collabcase.project.member;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.collabcase.project.member.dto.AddProjectMemberRequest;
import com.collabcase.project.member.dto.ProjectMemberResponse;
import com.collabcase.project.member.dto.UpdateProjectMemberRequest;
import com.collabcase.user.User;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects/{projectId}/members")
public class ProjectMemberController {

    private final ProjectMemberService memberService;

    public ProjectMemberController(ProjectMemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    public List<ProjectMemberResponse> findAll(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal User user) {
        return memberService.findAll(projectId, user.getId());
    }

    @PostMapping
    public ResponseEntity<ProjectMemberResponse> add(
            @PathVariable UUID projectId,
            @Valid @RequestBody AddProjectMemberRequest request,
            @AuthenticationPrincipal User user) {
        ProjectMemberResponse response = memberService.add(projectId, request, user.getId());
        return ResponseEntity.created(
                URI.create("/api/projects/" + projectId + "/members/" + response.userId())).body(response);
    }

    @PutMapping("/{userId}")
    public ProjectMemberResponse update(
            @PathVariable UUID projectId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateProjectMemberRequest request,
            @AuthenticationPrincipal User user) {
        return memberService.update(projectId, userId, request, user.getId());
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(
            @PathVariable UUID projectId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal User user) {
        memberService.remove(projectId, userId, user.getId());
        return ResponseEntity.noContent().build();
    }
}
