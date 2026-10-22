package com.collabcase.sequence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.collabcase.ai.AiDiagramContextMapper;
import com.collabcase.ai.AiProvider;
import com.collabcase.ai.AiProviderRequest;
import com.collabcase.ai.AiResponseException;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.diagram.Diagram;
import com.collabcase.diagram.DiagramRepository;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.sequence.dto.SequenceDefinition;
import com.collabcase.sequence.dto.SequenceDiagramResponse;
import com.collabcase.sequence.dto.SequenceGenerateRequest;
import com.collabcase.sequence.dto.SequenceUpdateRequest;

import tools.jackson.databind.ObjectMapper;

@Service
public class SequenceDiagramService {
    private static final Logger log = LoggerFactory.getLogger(SequenceDiagramService.class);
    private final GeneratedSequenceDiagramRepository repository;
    private final DiagramRepository diagramRepository;
    private final ProjectAccessService accessService;
    private final AiDiagramContextMapper contextMapper;
    private final AiProvider aiProvider;
    private final SequenceDiagramValidator validator;
    private final SequencePlantUmlGenerator plantUmlGenerator;
    private final ObjectMapper json;

    public SequenceDiagramService(GeneratedSequenceDiagramRepository repository, DiagramRepository diagramRepository,
            ProjectAccessService accessService, AiDiagramContextMapper contextMapper, AiProvider aiProvider,
            SequenceDiagramValidator validator, SequencePlantUmlGenerator plantUmlGenerator, ObjectMapper json) {
        this.repository = repository;
        this.diagramRepository = diagramRepository;
        this.accessService = accessService;
        this.contextMapper = contextMapper;
        this.aiProvider = aiProvider;
        this.validator = validator;
        this.plantUmlGenerator = plantUmlGenerator;
        this.json = json;
    }

    @Transactional
    public SequenceDiagramResponse generate(UUID diagramId, SequenceGenerateRequest request, UUID userId) {
        Diagram source = source(diagramId);
        accessService.requireDiagramEditor(source.getProject().getId(), userId);
        return generateAndSave(source, request.name().trim(), request.scenario().trim(),
                trim(request.entryClass()), trim(request.entryMethod()), null);
    }

    @Transactional(readOnly = true)
    public List<SequenceDiagramResponse> findAll(UUID diagramId, UUID userId) {
        Diagram source = source(diagramId);
        accessService.requireRead(source.getProject().getId(), userId);
        AiDiagramContext context = contextMapper.map(source.getModel());
        return repository.findBySourceDiagramIdOrderByUpdatedAtDesc(diagramId).stream()
                .map(item -> response(item, context)).toList();
    }

    @Transactional(readOnly = true)
    public SequenceDiagramResponse findById(UUID id, UUID userId) {
        GeneratedSequenceDiagram item = find(id);
        accessService.requireRead(item.getSourceDiagram().getProject().getId(), userId);
        return response(item, contextMapper.map(item.getSourceDiagram().getModel()));
    }

    @Transactional
    public SequenceDiagramResponse update(UUID id, SequenceUpdateRequest request, UUID userId) {
        GeneratedSequenceDiagram item = find(id);
        accessService.requireDiagramEditor(item.getSourceDiagram().getProject().getId(), userId);
        if (request.name() != null) {
            if (request.name().isBlank()) throw bad("El nombre no puede estar vacío");
            item.setName(request.name().trim());
        }
        if (request.scenario() != null) {
            if (request.scenario().isBlank()) throw bad("El escenario no puede estar vacío");
            item.setScenario(request.scenario().trim());
        }
        SequenceDefinition definition = parse(item.getSequenceJson());
        item.setPlantUml(plantUmlGenerator.generate(item.getName(), definition));
        return response(repository.saveAndFlush(item), contextMapper.map(item.getSourceDiagram().getModel()));
    }

    @Transactional
    public SequenceDiagramResponse regenerate(UUID id, UUID userId) {
        GeneratedSequenceDiagram item = find(id);
        accessService.requireDiagramEditor(item.getSourceDiagram().getProject().getId(), userId);
        return generateAndSave(item.getSourceDiagram(), item.getName(), item.getScenario(), null, null, item);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        GeneratedSequenceDiagram item = find(id);
        accessService.requireDiagramEditor(item.getSourceDiagram().getProject().getId(), userId);
        repository.delete(item);
    }

    @Transactional(readOnly = true)
    public String plantUml(UUID id, UUID userId) { return findById(id, userId).plantUml(); }

    private SequenceDiagramResponse generateAndSave(Diagram source, String name, String scenario,
            String entryClass, String entryMethod, GeneratedSequenceDiagram existing) {
        log.info("[SEQUENCE] diagramId={}", source.getId());
        log.info("[SEQUENCE] scenario={}", scenario);
        AiDiagramContext context = contextMapper.map(source.getModel());
        log.info("[SEQUENCE] semanticContext classes={}", context.classes().stream().map(item -> item.name()).toList());
        log.info("[SEQUENCE] aiProvider={}", aiProvider.getClass().getSimpleName());
        var generated = aiProvider.generate(prompt(context, scenario, entryClass, entryMethod));
        log.info("[SEQUENCE] aiRawResponse={}", generated.content());
        SequenceDefinition definition = parse(generated.content());
        log.info("[SEQUENCE] parsed participants={} messages={}",
                definition.participants() == null ? null : definition.participants().size(),
                definition.messages() == null ? null : definition.messages().size());
        List<String> warnings = validator.validate(definition, context, scenario);
        log.info("[SEQUENCE] validation=ok warnings={}", warnings.size());
        String plantUml = plantUmlGenerator.generate(name, definition);
        log.info("[SEQUENCE] plantUmlGenerated=true");
        String svg = plantUmlGenerator.renderSvg(plantUml);
        log.info("[SEQUENCE] svgGenerated=true");
        GeneratedSequenceDiagram item = existing == null ? new GeneratedSequenceDiagram() : existing;
        item.setSourceDiagram(source);
        item.setName(name);
        item.setScenario(scenario);
        item.setSequenceJson(json.writeValueAsString(definition));
        item.setPlantUml(plantUml);
        item = repository.saveAndFlush(item);
        log.info("[SEQUENCE] persisted={}", item.getId());
        return response(item, definition, warnings, svg);
    }

    private AiProviderRequest prompt(AiDiagramContext context, String scenario, String entryClass, String entryMethod) {
        String system = """
                Genera una secuencia UML para el escenario indicado. Utiliza únicamente las clases presentes
                en el contexto. Puedes agregar actores externos. No inventes nuevas clases del sistema.
                Usa métodos existentes cuando sea posible. Devuelve exclusivamente JSON estructurado según
                el schema, nunca PlantUML ni Markdown. CALL y RETURN son los tipos principales; CREATE es opcional.
                """;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenario", scenario);
        input.put("entryClass", entryClass);
        input.put("entryMethod", entryMethod);
        input.put("diagram", context);
        Map<String, Object> participant = Map.of(
                "type", "object", "additionalProperties", false,
                "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "name", Map.of("type", "string"),
                        "type", Map.of("type", "string", "enum", List.of("ACTOR", "CLASS"))),
                "required", List.of("id", "name", "type"));
        Map<String, Object> message = Map.of(
                "type", "object", "additionalProperties", false,
                "properties", Map.of(
                        "order", Map.of("type", "integer", "minimum", 1),
                        "from", Map.of("type", "string"),
                        "to", Map.of("type", "string"),
                        "label", Map.of("type", "string"),
                        "kind", Map.of("type", "string", "enum", List.of("CALL", "RETURN", "CREATE"))),
                "required", List.of("order", "from", "to", "label", "kind"));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("participants", "messages"),
                "properties", Map.of(
                        "participants", Map.of("type", "array", "items", participant),
                        "messages", Map.of("type", "array", "items", message)));
        return new AiProviderRequest(system, json.writeValueAsString(input), schema);
    }

    private SequenceDiagramResponse response(GeneratedSequenceDiagram item, AiDiagramContext context) {
        SequenceDefinition definition = parse(item.getSequenceJson());
        return response(item, definition, validator.validate(definition, context, item.getScenario()));
    }

    private SequenceDiagramResponse response(GeneratedSequenceDiagram item, SequenceDefinition definition, List<String> warnings) {
        return response(item, definition, warnings, plantUmlGenerator.renderSvg(item.getPlantUml()));
    }

    private SequenceDiagramResponse response(GeneratedSequenceDiagram item, SequenceDefinition definition,
            List<String> warnings, String svg) {
        return new SequenceDiagramResponse(item.getId(), item.getSourceDiagram().getId(), item.getName(),
                item.getScenario(), item.getPlantUml(), svg,
                definition, warnings, item.getCreatedAt(), item.getUpdatedAt());
    }

    private SequenceDefinition parse(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("```")) normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        try { return json.readValue(normalized, SequenceDefinition.class); }
        catch (RuntimeException exception) { throw new AiResponseException("La IA devolvió una secuencia JSON inválida", exception); }
    }

    private Diagram source(UUID id) { return diagramRepository.findById(id).orElseThrow(() -> notFound("Diagram not found")); }
    private GeneratedSequenceDiagram find(UUID id) { return repository.findById(id).orElseThrow(() -> notFound("Sequence diagram not found")); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
