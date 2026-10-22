package com.collabcase.project;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import com.collabcase.diagram.DiagramService;
import com.collabcase.diagram.dto.DiagramRequest;
import com.collabcase.diagram.dto.DiagramResponse;
import com.collabcase.diagram.dto.DiagramSummaryResponse;
import com.collabcase.project.dto.ProjectRequest;
import com.collabcase.project.dto.ProjectResponse;
import com.collabcase.project.dto.ProjectRenameRequest;

import jakarta.validation.Valid;
import com.collabcase.user.User;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final DiagramService diagramService;

    public ProjectController(ProjectService projectService, DiagramService diagramService) {
        this.projectService = projectService;
        this.diagramService = diagramService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @Valid @RequestBody ProjectRequest request,
            @AuthenticationPrincipal User user) {
        ProjectResponse response = projectService.create(request, user);
        return ResponseEntity.created(URI.create("/api/projects/" + response.id())).body(response);
    }

    @GetMapping
    public List<ProjectResponse> findAll(@AuthenticationPrincipal User user) {
        return projectService.findAll(user.getId());
    }

    @GetMapping("/{id}")
    public ProjectResponse findById(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return projectService.findById(id, user.getId());
    }

    @PutMapping("/{id}")
    public ProjectResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody ProjectRequest request,
            @AuthenticationPrincipal User user) {
        return projectService.update(id, request, user.getId());
    }

    @PatchMapping("/{id}")
    public ProjectResponse rename(@PathVariable UUID id,@Valid @RequestBody ProjectRenameRequest request,@AuthenticationPrincipal User user){return projectService.rename(id,request,user.getId());}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id,@AuthenticationPrincipal User user){projectService.delete(id,user.getId());}

    @GetMapping("/{projectId}/diagrams")
    public List<DiagramSummaryResponse> findDiagrams(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal User user) {
        return diagramService.findByProjectId(projectId, user.getId());
    }

    @PostMapping("/{projectId}/diagrams")
    public ResponseEntity<DiagramResponse> createDiagram(
            @PathVariable UUID projectId,
            @Valid @RequestBody DiagramRequest request,
            @AuthenticationPrincipal User user) {
        DiagramResponse response = diagramService.createForProject(projectId, request, user.getId());
        return ResponseEntity.created(URI.create("/api/diagrams/" + response.id())).body(response);
    }

    @PostMapping("/{projectId}/versions/{versionId}/diagrams")
    public ResponseEntity<DiagramResponse> createVersionDiagram(@PathVariable UUID projectId,@PathVariable UUID versionId,@Valid @RequestBody DiagramRequest request,@AuthenticationPrincipal User user){DiagramResponse response=diagramService.createForVersion(projectId,versionId,request,user.getId());return ResponseEntity.created(URI.create("/api/diagrams/"+response.id())).body(response);}
}
