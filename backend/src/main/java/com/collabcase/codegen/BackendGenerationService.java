package com.collabcase.codegen;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.collabcase.ai.AiDiagramContextMapper;
import com.collabcase.diagram.DiagramService;

@Service
public class BackendGenerationService {
    public record Download(byte[] content,String filename){}
    private final DiagramService diagrams;private final AiDiagramContextMapper contextMapper;private final GenerationModelBuilder builder;private final GenerationValidator validator;private final SpringBootProjectGenerator generator;private final ZipService zip;
    public BackendGenerationService(DiagramService diagrams,AiDiagramContextMapper contextMapper,GenerationModelBuilder builder,GenerationValidator validator,SpringBootProjectGenerator generator,ZipService zip){this.diagrams=diagrams;this.contextMapper=contextMapper;this.builder=builder;this.validator=validator;this.generator=generator;this.zip=zip;}
    public GenerationValidation validate(UUID diagramId,UUID userId){return validator.validate(model(diagramId,userId));}
    public Download generate(UUID diagramId,UUID userId){GenerationModel model=model(diagramId,userId);GenerationValidation validation=validator.validate(model);if(!validation.valid())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,String.join(" ",validation.errors()));return new Download(zip.zip(generator.generate(model)),model.artifactId()+".zip");}
    GenerationModel model(UUID id,UUID userId){var diagram=diagrams.findById(id,userId);var context=contextMapper.map(diagram.model());return builder.build(new com.collabcase.ai.context.AiDiagramContext(context.id(),diagram.name(),context.type(),context.classes(),context.relationships()));}
}
