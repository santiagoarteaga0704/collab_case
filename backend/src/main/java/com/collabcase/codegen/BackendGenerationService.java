package com.collabcase.codegen;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.collabcase.ai.AiDiagramContextMapper;
import com.collabcase.diagram.DiagramService;

@Service
public class BackendGenerationService {
    /** Proyecto listo para escribir: archivos generados + el APK de la app móvil. */
    public record Prepared(Map<String,byte[]> files,List<ZipService.StreamedEntry> streamed,String filename){
        public void writeTo(ZipService zip,OutputStream output) throws IOException {zip.write(files,streamed,output);}
    }
    // El APK es el mismo para cualquier proyecto: la app lee las entidades del backend al conectarse.
    static final String MOBILE_APK="/codegen/mobile-app.apk";
    public Prepared prepare(UUID diagramId,UUID userId){GenerationModel model=model(diagramId,userId);GenerationValidation validation=validator.validate(model);if(!validation.valid())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,String.join(" ",validation.errors()));var apk=new ZipService.StreamedEntry(model.artifactId()+"/"+model.artifactId()+".apk",()->BackendGenerationService.class.getResourceAsStream(MOBILE_APK));return new Prepared(generator.generate(model),List.of(apk),model.artifactId()+".zip");}
    private final DiagramService diagrams;private final AiDiagramContextMapper contextMapper;private final GenerationModelBuilder builder;private final GenerationValidator validator;private final SpringBootProjectGenerator generator;private final ZipService zip;
    public BackendGenerationService(DiagramService diagrams,AiDiagramContextMapper contextMapper,GenerationModelBuilder builder,GenerationValidator validator,SpringBootProjectGenerator generator,ZipService zip){this.diagrams=diagrams;this.contextMapper=contextMapper;this.builder=builder;this.validator=validator;this.generator=generator;this.zip=zip;}
    public GenerationValidation validate(UUID diagramId,UUID userId){return validator.validate(model(diagramId,userId));}
    GenerationModel model(UUID id,UUID userId){var diagram=diagrams.findById(id,userId);var context=contextMapper.map(diagram.model());return builder.build(new com.collabcase.ai.context.AiDiagramContext(context.id(),diagram.name(),context.type(),context.classes(),context.relationships()));}
}
