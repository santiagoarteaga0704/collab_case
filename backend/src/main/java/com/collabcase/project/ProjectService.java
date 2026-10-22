package com.collabcase.project;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import com.collabcase.project.ProjectAccessService.Access;
import com.collabcase.project.ProjectAccessService.EffectiveRole;
import com.collabcase.project.dto.ProjectRequest;
import com.collabcase.project.dto.ProjectResponse;
import com.collabcase.project.dto.ProjectRenameRequest;
import com.collabcase.project.member.ProjectMember;
import com.collabcase.project.member.ProjectMemberRepository;
import com.collabcase.user.User;
import com.collabcase.project.version.*;
import com.collabcase.diagram.*;
import com.collabcase.sequence.GeneratedSequenceDiagramRepository;

@Service
public class ProjectService {
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectAccessService accessService;
    private final ProjectVersionRepository versionRepository;private final DiagramRepository diagramRepository;private final GeneratedSequenceDiagramRepository sequenceRepository;private final ProjectVersionService versionService;private final ApplicationEventPublisher events;

    @Autowired public ProjectService(ProjectRepository projectRepository, ProjectMemberRepository memberRepository,
            ProjectAccessService accessService,ProjectVersionRepository versionRepository,DiagramRepository diagramRepository,GeneratedSequenceDiagramRepository sequenceRepository,ProjectVersionService versionService,ApplicationEventPublisher events) {
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.accessService = accessService;
        this.versionRepository=versionRepository;this.diagramRepository=diagramRepository;this.sequenceRepository=sequenceRepository;this.versionService=versionService;this.events=events;
    }
    public ProjectService(ProjectRepository projectRepository,ProjectMemberRepository memberRepository,ProjectAccessService accessService){this(projectRepository,memberRepository,accessService,null,null,null,null,null);}

    @Transactional
    public ProjectResponse create(ProjectRequest request, User owner) {
        Project project = new Project();
        project.setOwner(owner);
        applyRequest(project, request);
        project=projectRepository.saveAndFlush(project);versionService.ensureMain(project);return toResponse(project, EffectiveRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> findAll(UUID userId) {
        Map<UUID, ProjectResponse> projects = new LinkedHashMap<>();
        projectRepository.findByOwnerIdOrderByUpdatedAtDesc(userId)
                .forEach(project -> projects.put(project.getId(), toResponse(project, EffectiveRole.OWNER)));
        for (ProjectMember member : memberRepository.findByUserId(userId)) {
            Project project = member.getProject();
            projects.putIfAbsent(project.getId(),
                    toResponse(project, EffectiveRole.valueOf(member.getRole().name())));
        }
        return projects.values().stream()
                .sorted(Comparator.comparing(ProjectResponse::updatedAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse findById(UUID id, UUID userId) {
        Access access = accessService.requireRead(id, userId);
        return toResponse(access.project(), access.role());
    }

    @Transactional
    public ProjectResponse update(UUID id, ProjectRequest request, UUID userId) {
        Project project = accessService.requireOwner(id, userId).project();
        applyRequest(project, request);
        return toResponse(projectRepository.saveAndFlush(project), EffectiveRole.OWNER);
    }

    @Transactional public ProjectResponse rename(UUID id,ProjectRenameRequest request,UUID userId){Access access=accessService.requireOwner(id,userId);Project project=access.project();project.setName(request.name().trim());return toResponse(projectRepository.saveAndFlush(project),access.role());}

    @Transactional public void delete(UUID id,UUID userId){Project project=accessService.requireOwner(id,userId).project();for(Diagram diagram:diagramRepository.findByProjectIdOrderByUpdatedAtDesc(id)){sequenceRepository.deleteBySourceDiagramId(diagram.getId());events.publishEvent(new DiagramDeletedEvent(diagram.getId()));}diagramRepository.deleteAll(diagramRepository.findByProjectIdOrderByUpdatedAtDesc(id));List<ProjectVersion> ownedVersions=versionRepository.findByProjectIdOrderByMainVersionDescCreatedAtAsc(id);ownedVersions.forEach(version->version.setCreatedFrom(null));versionRepository.flush();versionRepository.deleteAll(ownedVersions);memberRepository.deleteByProjectId(id);projectRepository.delete(project);}

    private void applyRequest(Project project, ProjectRequest request) {
        project.setName(request.name().trim());
        project.setDescription(request.description());
    }

    private ProjectResponse toResponse(Project project, EffectiveRole role) {
        return new ProjectResponse(project.getId(), project.getName(), project.getDescription(), role.name(),
                project.getCreatedAt(), project.getUpdatedAt());
    }
}
