package com.collabcase.diagram;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.collabcase.diagram.dto.DiagramRequest;
import com.collabcase.diagram.dto.DiagramResponse;
import com.collabcase.diagram.dto.DiagramSummaryResponse;
import com.collabcase.diagram.dto.DiagramRenameRequest;
import com.collabcase.project.Project;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.project.ProjectAccessService.Access;
import com.collabcase.project.version.ProjectVersionService;
import com.collabcase.sequence.GeneratedSequenceDiagramRepository;

@Service
public class DiagramService {
    public record RuntimeDiagramSource(UUID diagramId,UUID projectId,String name,Map<String,Object> model){}
    private final DiagramRepository diagramRepository;
    private final ProjectAccessService accessService;
    private final ApplicationEventPublisher events;
    private final ProjectVersionService versions;
    private final GeneratedSequenceDiagramRepository sequences;

    @Autowired public DiagramService(DiagramRepository diagramRepository, ProjectAccessService accessService,ApplicationEventPublisher events,ProjectVersionService versions,GeneratedSequenceDiagramRepository sequences) {
        this.diagramRepository = diagramRepository;
        this.accessService = accessService;
        this.events=events;
        this.versions=versions;this.sequences=sequences;
    }
    public DiagramService(DiagramRepository diagramRepository,ProjectAccessService accessService,ApplicationEventPublisher events){this(diagramRepository,accessService,events,null,null);}

    @Transactional
    public DiagramResponse createForProject(UUID projectId, DiagramRequest request, UUID userId) {
        Project project = accessService.requireDiagramEditor(projectId, userId).project();
        Diagram diagram = new Diagram();
        diagram.setProject(project);
        diagram.setVersion(versions.ensureMain(project));
        applyRequest(diagram, request);
        return toResponse(diagramRepository.saveAndFlush(diagram));
    }

    @Transactional(readOnly = true)
    public List<DiagramSummaryResponse> findByProjectId(UUID projectId, UUID userId) {
        accessService.requireRead(projectId, userId);
        return diagramRepository.findByProjectIdOrderByUpdatedAtDesc(projectId).stream()
                .map(this::toSummaryResponse).toList();
    }

    @Transactional(readOnly = true)
    public DiagramResponse findById(UUID id, UUID userId) {
        Diagram diagram = findDiagram(id);
        accessService.requireRead(diagram.getProject().getId(), userId);
        return toResponse(diagram);
    }

    @Transactional
    public DiagramResponse update(UUID id, DiagramRequest request, UUID userId) {
        Diagram diagram = findDiagram(id);
        accessService.requireDiagramEditor(diagram.getProject().getId(), userId);
        applyRequest(diagram, request);
        return toResponse(diagramRepository.saveAndFlush(diagram));
    }

    @Transactional public DiagramResponse createForVersion(UUID projectId,UUID versionId,DiagramRequest request,UUID userId){Project project=accessService.requireDiagramEditor(projectId,userId).project();Diagram diagram=new Diagram();diagram.setProject(project);diagram.setVersion(versions.require(projectId,versionId));applyRequest(diagram,request);return toResponse(diagramRepository.saveAndFlush(diagram));}

    @Transactional public void delete(UUID id,UUID userId){Diagram diagram=findDiagram(id);accessService.requireDiagramEditor(diagram.getProject().getId(),userId);sequences.deleteBySourceDiagramId(id);diagramRepository.delete(diagram);events.publishEvent(new DiagramDeletedEvent(id));}

    @Transactional public DiagramResponse rename(UUID id,DiagramRenameRequest request,UUID userId){Diagram diagram=findDiagram(id);accessService.requireDiagramEditor(diagram.getProject().getId(),userId);String name=request.name().trim();diagram.setName(name);Map<String,Object>model=new LinkedHashMap<>(diagram.getModel());model.put("title",name);diagram.setModel(model);DiagramResponse response=toResponse(diagramRepository.saveAndFlush(diagram));events.publishEvent(new DiagramRenamedEvent(id,name));return response;}

    @Transactional
    public DiagramResponse replaceImported(UUID projectId, UUID diagramId, DiagramRequest request, UUID userId) {
        Diagram diagram = findDiagram(diagramId);
        if (!diagram.getProject().getId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram not found");
        }
        accessService.requireDiagramEditor(projectId, userId);
        applyRequest(diagram, request);
        return toResponse(diagramRepository.saveAndFlush(diagram));
    }

    @Transactional(readOnly = true)
    public Access requireCollaborationAccess(UUID diagramId, UUID userId) {
        Diagram diagram = findDiagram(diagramId);
        return accessService.requireDiagramEditor(diagram.getProject().getId(), userId);
    }

    @Transactional(readOnly = true)
    public Access requireCollaborationReadAccess(UUID diagramId, UUID userId) {
        Diagram diagram = findDiagram(diagramId);
        return accessService.requireRead(diagram.getProject().getId(), userId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findModelForAnalysis(UUID diagramId, UUID userId) {
        Diagram diagram = findDiagram(diagramId);
        accessService.requireRead(diagram.getProject().getId(), userId);
        return diagram.getModel();
    }

    @Transactional(readOnly = true)
    public RuntimeDiagramSource requireRuntimeRegistration(UUID diagramId,UUID userId){
        Diagram diagram=findDiagram(diagramId);
        accessService.requireDiagramEditor(diagram.getProject().getId(),userId);
        return new RuntimeDiagramSource(diagram.getId(),diagram.getProject().getId(),diagram.getName(),diagram.getModel());
    }

    private Diagram findDiagram(UUID id) {
        return diagramRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram not found"));
    }

    private void applyRequest(Diagram diagram, DiagramRequest request) {
        diagram.setName(request.name().trim());
        diagram.setType(request.type());
        Map<String,Object> model=new LinkedHashMap<>(request.model());model.put("title",diagram.getName());diagram.setModel(model);
    }

    private DiagramResponse toResponse(Diagram diagram) {
        return new DiagramResponse(diagram.getId(), diagram.getName(), diagram.getType(), diagram.getModel(),
                diagram.getCreatedAt(), diagram.getUpdatedAt());
    }

    private DiagramSummaryResponse toSummaryResponse(Diagram diagram) {
        return new DiagramSummaryResponse(diagram.getId(), diagram.getName(), diagram.getType(),
                diagram.getCreatedAt(), diagram.getUpdatedAt());
    }
}
