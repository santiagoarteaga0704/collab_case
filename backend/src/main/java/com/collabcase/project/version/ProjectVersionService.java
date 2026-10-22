package com.collabcase.project.version;

import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.collabcase.diagram.*;
import com.collabcase.diagram.dto.DiagramSummaryResponse;
import com.collabcase.project.Project;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.project.version.dto.*;
import com.collabcase.sequence.*;
import com.collabcase.user.User;

@Service
public class ProjectVersionService {
 private final ProjectVersionRepository versions;private final DiagramRepository diagrams;private final GeneratedSequenceDiagramRepository sequences;private final ProjectAccessService access;private final ObjectMapper json;private final ApplicationEventPublisher events;
 public ProjectVersionService(ProjectVersionRepository versions,DiagramRepository diagrams,GeneratedSequenceDiagramRepository sequences,ProjectAccessService access,ObjectMapper json,ApplicationEventPublisher events){this.versions=versions;this.diagrams=diagrams;this.sequences=sequences;this.access=access;this.json=json;this.events=events;}
 @Transactional public ProjectVersion ensureMain(Project project){return versions.findByProjectIdAndMainVersionTrue(project.getId()).orElseGet(()->{ProjectVersion v=new ProjectVersion();v.setProject(project);v.setName("main");v.setMainVersion(true);v.setCreatedBy(project.getOwner());return versions.saveAndFlush(v);});}
 @Transactional(readOnly=true) public List<ProjectVersionResponse> list(UUID projectId,UUID userId){access.requireRead(projectId,userId);return versions.findByProjectIdOrderByMainVersionDescCreatedAtAsc(projectId).stream().map(this::response).toList();}
 @Transactional public ProjectVersionResponse create(UUID projectId,ProjectVersionRequest request,User user){Project project=access.requireDiagramEditor(projectId,user.getId()).project();String name=request.name().trim();if("main".equalsIgnoreCase(name)||versions.existsByProjectIdAndNameIgnoreCase(projectId,name))throw bad(HttpStatus.CONFLICT,"Ya existe una versión con ese nombre");ProjectVersion source=request.sourceVersionId()==null?ensureMain(project):findInProject(projectId,request.sourceVersionId());ProjectVersion target=new ProjectVersion();target.setProject(project);target.setName(name);target.setDescription(request.description());target.setCreatedFrom(source);target.setCreatedBy(user);target=versions.saveAndFlush(target);cloneDiagrams(source,target);return response(target);}
 @Transactional public ProjectVersionResponse rename(UUID projectId,UUID versionId,ProjectVersionRenameRequest request,UUID userId){access.requireOwner(projectId,userId);ProjectVersion v=findInProject(projectId,versionId);if(v.isMainVersion())throw bad(HttpStatus.BAD_REQUEST,"La versión main no se puede renombrar");String name=request.name().trim();if(versions.existsByProjectIdAndNameIgnoreCase(projectId,name)&&!v.getName().equalsIgnoreCase(name))throw bad(HttpStatus.CONFLICT,"Ya existe una versión con ese nombre");v.setName(name);return response(versions.saveAndFlush(v));}
 @Transactional public void delete(UUID projectId,UUID versionId,UUID userId){access.requireOwner(projectId,userId);ProjectVersion v=findInProject(projectId,versionId);if(v.isMainVersion())throw bad(HttpStatus.BAD_REQUEST,"La versión main no se puede eliminar");for(ProjectVersion child:versions.findByCreatedFromId(versionId))child.setCreatedFrom(null);versions.flush();List<Diagram> owned=diagrams.findByVersionId(versionId);for(Diagram d:owned){sequences.deleteBySourceDiagramId(d.getId());events.publishEvent(new DiagramDeletedEvent(d.getId()));}diagrams.deleteAll(owned);versions.delete(v);}
 @Transactional(readOnly=true) public List<DiagramSummaryResponse> diagrams(UUID projectId,UUID versionId,UUID userId){access.requireRead(projectId,userId);findInProject(projectId,versionId);return diagrams.findByVersionIdOrderByUpdatedAtDesc(versionId).stream().map(d->new DiagramSummaryResponse(d.getId(),d.getName(),d.getType(),d.getCreatedAt(),d.getUpdatedAt())).toList();}
 @Transactional(readOnly=true) public ProjectVersion require(UUID projectId,UUID versionId){return findInProject(projectId,versionId);}
 private void cloneDiagrams(ProjectVersion source,ProjectVersion target){for(Diagram original:diagrams.findByVersionId(source.getId())){Diagram copy=new Diagram();copy.setProject(target.getProject());copy.setVersion(target);copy.setName(original.getName());copy.setType(original.getType());copy.setModel(json.convertValue(original.getModel(),Map.class));copy=diagrams.saveAndFlush(copy);for(GeneratedSequenceDiagram old:sequences.findBySourceDiagramIdOrderByUpdatedAtDesc(original.getId())){GeneratedSequenceDiagram item=new GeneratedSequenceDiagram();item.setSourceDiagram(copy);item.setName(old.getName());item.setScenario(old.getScenario());item.setPlantUml(old.getPlantUml());item.setSequenceJson(old.getSequenceJson());sequences.save(item);}}}
 private ProjectVersion findInProject(UUID projectId,UUID id){return versions.findById(id).filter(v->v.getProject().getId().equals(projectId)).orElseThrow(()->bad(HttpStatus.NOT_FOUND,"Versión no encontrada"));}
 private ProjectVersionResponse response(ProjectVersion v){return new ProjectVersionResponse(v.getId(),v.getName(),v.getDescription(),v.getCreatedFrom()==null?null:v.getCreatedFrom().getId(),v.isMainVersion(),v.getCreatedAt(),v.getUpdatedAt());}
 private ResponseStatusException bad(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
