package com.collabcase.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.collabcase.ai.dto.AiAnalysisResponse;
import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.diagram.DiagramService;

import tools.jackson.databind.ObjectMapper;

@Service
public class AiService {
    private static final int MAX_MODEL_JSON_CHARACTERS = 500_000;

    private record GeneratedAnalysis(String message, List<AiSuggestion> suggestions) {
    }

    private final AiProvider provider;
    private final DiagramService diagramService;
    private final AiPromptBuilder promptBuilder;
    private final AiDiagramContextMapper contextMapper;
    private final AiSuggestionValidator suggestionValidator;
    private final ObjectMapper objectMapper;

    public AiService(AiProvider provider, DiagramService diagramService,
            AiPromptBuilder promptBuilder, AiDiagramContextMapper contextMapper,
            AiSuggestionValidator suggestionValidator, ObjectMapper objectMapper) {
        this.provider = provider;
        this.diagramService = diagramService;
        this.promptBuilder = promptBuilder;
        this.contextMapper = contextMapper;
        this.suggestionValidator = suggestionValidator;
        this.objectMapper = objectMapper;
    }

    public AiAnalysisResponse analyze(UUID diagramId, UUID userId, String message) {
        Map<String, Object> model = diagramService.findModelForAnalysis(diagramId, userId);
        if (objectMapper.writeValueAsString(model).length() > MAX_MODEL_JSON_CHARACTERS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "El diagrama es demasiado grande para analizarlo");
        }
        var context = contextMapper.map(model);
        AiProviderResponse providerResponse = provider.generate(promptBuilder.build(context, message));
        GeneratedAnalysis generated = parse(providerResponse.content());
        validate(generated);
        List<AiSuggestion> suggestions = suggestionValidator.validate(generated.suggestions(), context);
        return new AiAnalysisResponse(generated.message(), suggestions, providerResponse.provider());
    }

    private GeneratedAnalysis parse(String content) {
        String normalized = content.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
        }
        try {
            return objectMapper.readValue(normalized, GeneratedAnalysis.class);
        } catch (RuntimeException exception) {
            throw new AiResponseException("El proveedor devolvió una respuesta con formato inválido", exception);
        }
    }

    private void validate(GeneratedAnalysis analysis) {
        if (analysis == null || isBlank(analysis.message()) || analysis.suggestions() == null) {
            throw new AiResponseException("La respuesta de IA no cumple el contrato esperado");
        }
        for (AiSuggestion suggestion : analysis.suggestions()) {
            if (suggestion == null || suggestion.type() == null || isBlank(suggestion.title())
                    || isBlank(suggestion.description()) || isBlank(suggestion.reason())) {
                throw new AiResponseException("Una sugerencia de IA no cumple el contrato esperado");
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
