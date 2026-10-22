package com.collabcase.vision;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.collabcase.ai.AiDiagramContextMapper;
import com.collabcase.ai.AiSuggestionValidator;
import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.diagram.DiagramService;
import com.collabcase.vision.dto.ClassCandidate;
import com.collabcase.vision.dto.RelationshipCandidate;
import com.collabcase.vision.dto.VisionExtraction;
import com.collabcase.vision.dto.VisionExtractionResponse;

import tools.jackson.databind.ObjectMapper;

@Service
public class VisionService {
    private static final Set<String> TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private final VisionProvider provider;
    private final VisionPromptBuilder promptBuilder;
    private final DiagramService diagramService;
    private final AiDiagramContextMapper contextMapper;
    private final VisionSuggestionAdapter adapter;
    private final AiSuggestionValidator validator;
    private final ObjectMapper objectMapper;
    private final long maxFileSize;

    public VisionService(VisionProvider provider, VisionPromptBuilder promptBuilder, DiagramService diagramService,
            AiDiagramContextMapper contextMapper, VisionSuggestionAdapter adapter,
            AiSuggestionValidator validator, ObjectMapper objectMapper,
            @Value("${vision.max-file-size}") long maxFileSize) {
        this.provider = provider;
        this.promptBuilder = promptBuilder;
        this.diagramService = diagramService;
        this.contextMapper = contextMapper;
        this.adapter = adapter;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.maxFileSize = maxFileSize;
    }

    public VisionExtractionResponse extract(UUID diagramId, UUID userId, MultipartFile file, String instruction) {
        byte[] image = validateImage(file);
        Map<String, Object> model = diagramService.findModelForAnalysis(diagramId, userId);
        var context = contextMapper.map(model);
        VisionProviderResponse providerResult = provider.extract(
                promptBuilder.build(image, file.getContentType(), instruction));
        VisionExtraction extraction = parse(providerResult.content());
        validateExtraction(extraction);
        List<AiSuggestion> suggestions = validator.validate(adapter.adapt(extraction, context), context);
        if (extraction.classes().isEmpty() && extraction.relationships().isEmpty()) {
            throw new VisionException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No se reconocieron elementos UML en la imagen.");
        }
        return new VisionExtractionResponse(providerResult.provider(), extraction.summary(), extraction.classes(),
                extraction.relationships(), extraction.warnings(), suggestions);
    }

    private byte[] validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new VisionException(HttpStatus.BAD_REQUEST, "La imagen esta vacia.");
        if (file.getSize() > maxFileSize) {
            throw new VisionException(HttpStatus.PAYLOAD_TOO_LARGE, "La imagen supera el limite de 10 MB.");
        }
        if (!TYPES.contains(file.getContentType())) {
            throw new VisionException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Usa una imagen PNG, JPEG o WebP.");
        }
        try {
            byte[] bytes = file.getBytes();
            if (!matchesSignature(bytes, file.getContentType())) {
                throw new VisionException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El contenido no corresponde a una imagen valida.");
            }
            return bytes;
        } catch (IOException exception) {
            throw new VisionException(HttpStatus.BAD_REQUEST, "No se pudo leer la imagen.", exception);
        }
    }

    private boolean matchesSignature(byte[] bytes, String type) {
        if ("image/png".equals(type)) return bytes.length > 8 && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47;
        if ("image/jpeg".equals(type)) return bytes.length > 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8;
        return bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private VisionExtraction parse(String content) {
        String normalized = content.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        try {
            return objectMapper.readValue(normalized, VisionExtraction.class);
        } catch (RuntimeException exception) {
            throw new VisionException(HttpStatus.BAD_GATEWAY,
                    "El proveedor visual devolvio JSON invalido.", exception);
        }
    }

    private void validateExtraction(VisionExtraction extraction) {
        if (extraction == null || extraction.summary() == null || extraction.classes() == null
                || extraction.relationships() == null || extraction.warnings() == null) {
            throw new VisionException(HttpStatus.BAD_GATEWAY, "La extraccion visual no cumple el contrato esperado.");
        }
        for (ClassCandidate candidate : extraction.classes()) {
            if (candidate == null || candidate.name() == null || candidate.name().isBlank()
                    || candidate.attributes() == null || candidate.methods() == null) {
                throw new VisionException(HttpStatus.BAD_GATEWAY, "Una clase extraida no es valida.");
            }
            if (candidate.attributes().stream().anyMatch(item -> item == null || item.name() == null || item.name().isBlank())
                    || candidate.methods().stream().anyMatch(item -> item == null || item.name() == null || item.name().isBlank())) {
                throw new VisionException(HttpStatus.BAD_GATEWAY, "Un miembro UML extraido no es valido.");
            }
        }
        for (RelationshipCandidate relationship : extraction.relationships()) {
            if (relationship == null || relationship.sourceClass() == null || relationship.sourceClass().isBlank()
                    || relationship.targetClass() == null || relationship.targetClass().isBlank()) {
                throw new VisionException(HttpStatus.BAD_GATEWAY, "Una relacion extraida no es valida.");
            }
        }
    }
}
