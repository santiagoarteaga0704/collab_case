package com.collabcase.runtime.app;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.collabcase.ai.AiDiagramContextMapper;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.codegen.GenerationModelBuilder;
import com.collabcase.codegen.GenerationValidator;
import com.collabcase.codegen.SpringBootProjectGenerator;
import com.collabcase.diagram.DiagramService;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.runtime.dto.RuntimeDtos.*;
import com.collabcase.runtime.security.RuntimeTokenService;

@Service
public class RuntimeAppService {
    private final RuntimeAppRepository apps;private final DiagramService diagrams;private final ProjectAccessService access;
    private final AiDiagramContextMapper contextMapper;private final GenerationModelBuilder builder;private final GenerationValidator validator;
    private final RuntimeTokenService tokens;private final SpringBootProjectGenerator definitions;
    public RuntimeAppService(RuntimeAppRepository apps,DiagramService diagrams,ProjectAccessService access,AiDiagramContextMapper contextMapper,GenerationModelBuilder builder,GenerationValidator validator,RuntimeTokenService tokens,SpringBootProjectGenerator definitions){this.apps=apps;this.diagrams=diagrams;this.access=access;this.contextMapper=contextMapper;this.builder=builder;this.validator=validator;this.tokens=tokens;this.definitions=definitions;}
    @Transactional public Registration register(UUID diagramId,UUID userId){var source=diagrams.requireRuntimeRegistration(diagramId,userId);var context=contextMapper.map(source.model());var model=builder.build(new AiDiagramContext(context.id(),source.name(),context.type(),context.classes(),context.relationships()));var validation=validator.validate(model);if(!validation.valid())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,String.join(" ",validation.errors()));Map<String,Object> definition=definitions.appDefinition(model);String token=tokens.generate();RuntimeApp app=new RuntimeApp();app.setSourceDiagramId(source.diagramId());app.setSourceProjectId(source.projectId());app.setName(model.applicationName());app.setSchemaVersion(model.schemaVersion());app.setDefinition(definition);app.setTokenHash(tokens.hash(token));apps.saveAndFlush(app);return new Registration(app.getId(),app.getName(),app.getSchemaVersion(),token,app.getDefinition());}
    @Transactional(readOnly=true) public List<Summary> list(UUID projectId,UUID userId){access.requireRead(projectId,userId);return apps.findBySourceProjectIdOrderByCreatedAtDesc(projectId).stream().map(a->new Summary(a.getId(),a.getName(),a.getSchemaVersion(),a.isActive(),a.getCreatedAt())).toList();}
    @Transactional(readOnly=true) public Detail detail(UUID appId,UUID userId){RuntimeApp app=find(appId);access.requireRead(app.getSourceProjectId(),userId);return detail(app);}
    @Transactional public void disable(UUID appId,UUID userId){RuntimeApp app=find(appId);access.requireOwner(app.getSourceProjectId(),userId);app.setActive(false);apps.save(app);}
    @Transactional(readOnly=true) public RuntimeApp authenticate(UUID appId,String token){RuntimeApp app=find(appId);if(!app.isActive()||!tokens.matches(token,app.getTokenHash()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid runtime application token");return app;}
    // Writes lock the app row until commit, so syncVersion values are committed in order and a
    // client cursor never jumps past a change that was still uncommitted when it read.
    @Transactional public RuntimeApp authenticateForWrite(UUID appId,String token){RuntimeApp app=apps.findLockedById(appId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Runtime application not found"));if(!app.isActive()||!tokens.matches(token,app.getTokenHash()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid runtime application token");return app;}
    private RuntimeApp find(UUID id){return apps.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Runtime application not found"));}
    private Detail detail(RuntimeApp a){return new Detail(a.getId(),a.getSourceDiagramId(),a.getSourceProjectId(),a.getName(),a.getSchemaVersion(),a.isActive(),a.getCreatedAt(),a.getUpdatedAt(),a.getDefinition());}
}
