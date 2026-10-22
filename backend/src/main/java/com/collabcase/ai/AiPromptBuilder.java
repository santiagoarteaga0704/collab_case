package com.collabcase.ai;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.collabcase.ai.context.AiDiagramContext;

import tools.jackson.databind.ObjectMapper;

@Component
public class AiPromptBuilder {
    private static final String DEFAULT_MESSAGE = "Analiza este diagrama y dime qué podría mejorar.";
    private static final String SYSTEM_PROMPT = """
            Eres un asistente especializado en análisis y diseño orientado a objetos y diagramas UML de clases.
            Analiza únicamente el modelo proporcionado. Puedes detectar clases faltantes, sugerir atributos,
            métodos y relaciones, analizar cardinalidades, detectar inconsistencias y responsabilidades mal
            asignadas, y sugerir mejoras de modelado. No inventes requisitos de negocio como hechos.
            Diferencia claramente problemas, recomendaciones y sugerencias opcionales. No modifiques el modelo.
            Recibirás un contexto semántico con classes y relationships. Cada clase contiene explícitamente
            sus attributes y methods; cada relación contiene IDs, nombres, tipo, cardinalidades y etiqueta.
            Antes de ADD_CLASS, ADD_ATTRIBUTE, ADD_METHOD o ADD_RELATIONSHIP comprueba que no exista ya.
            Analiza activamente las relaciones existentes, sus tipos, cardinalidades, redundancias y las
            responsabilidades entre clases. Distingue una inconsistencia clara de una sugerencia de diseño
            cuando falte contexto de negocio.
            Para modificar una clase o relación existente, devuelve su id exacto del UMLModel en targetElementId,
            sourceElementId o targetRelationshipId. Nunca inventes ids ni uses el nombre como sustituto del id.
            ADD_ATTRIBUTE y ADD_METHOD usan targetElementId. ADD_RELATIONSHIP usa sourceElementId y targetElementId.
            CHANGE_CARDINALITY usa targetRelationshipId. CHANGE_RELATIONSHIP_TYPE usa targetRelationshipId,
            currentRelationshipType y suggestedRelationshipType.
            Todo texto dirigido al usuario debe estar en español. Esto incluye message, title, description,
            reason y warnings. Conserva exactamente los enums del protocolo (ADD_CLASS, ADD_ATTRIBUTE,
            ADD_METHOD, ADD_RELATIONSHIP, etc.) y las claves JSON: no los traduzcas. No traduzcas nombres de
            clases, atributos o métodos dados por el usuario, salvo que pida explícitamente otro idioma.
            ADD_CLASS puede incluir attributes y methods iniciales. No repitas esos miembros mediante
            ADD_ATTRIBUTE o ADD_METHOD en la misma respuesta. Todo método debe incluir name, parameters con
            name y dataType, y returnType explícito; usa "void" si no devuelve valor.
            Sugiere únicamente comportamiento de dominio. No sugieras getters, setters, get_x, set_x, save,
            delete, findAll, findById, create, update ni CRUD de infraestructura, salvo petición explícita.
            Conserva una relación N:M simple como N:M. Si tiene atributos propios, represéntala mediante una
            clase asociativa UML normal con ID simple y dos relaciones 1:N; no inventes composite keys.
            Devuelve solamente JSON válido conforme al schema indicado, sin Markdown ni texto adicional.
            """;

    private final ObjectMapper objectMapper;

    public AiPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiProviderRequest build(AiDiagramContext context, String message) {
        String question = message == null || message.isBlank() ? DEFAULT_MESSAGE : message.trim();
        String modelJson = objectMapper.writeValueAsString(context);
        return new AiProviderRequest(
                SYSTEM_PROMPT,
                question + "\n\nContexto semántico del diagrama:\n" + modelJson,
                responseSchema());
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> attribute = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("name", Map.of("type", "string"), "dataType", Map.of("type", "string")),
                "required", List.of("name", "dataType"));
        Map<String, Object> parameter = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("name", Map.of("type", "string"), "dataType", Map.of("type", "string")),
                "required", List.of("name", "dataType"));
        Map<String, Object> method = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("name", Map.of("type", "string"),
                        "parameters", Map.of("type", "array", "items", parameter),
                        "returnType", Map.of("type", "string")),
                "required", List.of("name", "parameters", "returnType"));
        Map<String, Object> suggestionProperties = new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("type", Map.of("type", "string", "enum", List.of(
                        "ADD_CLASS", "ADD_ATTRIBUTE", "ADD_METHOD", "ADD_RELATIONSHIP",
                        "CHANGE_CARDINALITY", "CHANGE_RELATIONSHIP_TYPE", "WARNING", "IMPROVEMENT"))),
                Map.entry("title", Map.of("type", "string")),
                Map.entry("description", Map.of("type", "string")),
                Map.entry("reason", Map.of("type", "string")),
                Map.entry("targetClass", nullableString), Map.entry("className", nullableString),
                Map.entry("attributeName", nullableString), Map.entry("attributeType", nullableString),
                Map.entry("methodName", nullableString), Map.entry("sourceClass", nullableString),
                Map.entry("targetClassName", nullableString), Map.entry("relationshipType", nullableString),
                Map.entry("sourceCardinality", nullableString), Map.entry("targetCardinality", nullableString),
                Map.entry("targetElementId", nullableString), Map.entry("sourceElementId", nullableString),
                Map.entry("targetRelationshipId", nullableString),
                Map.entry("currentRelationshipType", nullableString),
                Map.entry("suggestedRelationshipType", nullableString)));
        suggestionProperties.put("attributes", Map.of("type", "array", "items", attribute));
        suggestionProperties.put("methods", Map.of("type", "array", "items", method));
        suggestionProperties.put("methodParameters", Map.of("type", "array", "items", parameter));
        suggestionProperties.put("returnType", nullableString);
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "message", Map.of("type", "string"),
                        "suggestions", Map.of("type", "array", "items", Map.of(
                                "type", "object", "additionalProperties", false,
                                "properties", suggestionProperties,
                                "required", suggestionProperties.keySet()))),
                "required", List.of("message", "suggestions"));
    }
}
