package com.collabcase.vision;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class VisionPromptBuilder {
    private static final String PROMPT = """
            Eres especialista en UML de clases. Analiza exclusivamente lo visible en la imagen.
            Extrae clases, atributos, metodos, relaciones, tipos de relacion y cardinalidades.
            No inventes entidades ausentes. Si una palabra no puede leerse claramente, agrega una advertencia.
            Usa tipos oficiales de Apollon para relationshipType: ClassBidirectional, ClassUnidirectional,
            ClassAggregation, ClassComposition, ClassInheritance, ClassDependency o ClassRealization.
            Los campos dudosos deben ser null. Devuelve unicamente JSON conforme al esquema.
            """;

    public VisionProviderRequest build(byte[] image, String contentType, String instruction) {
        String prompt = PROMPT + (instruction == null || instruction.isBlank()
                ? "" : "\nInstruccion adicional: " + instruction.trim());
        return new VisionProviderRequest(image, contentType, prompt, schema());
    }

    private Map<String, Object> schema() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> attribute = object(Map.of(
                "name", Map.of("type", "string"), "type", nullableString, "visibility", nullableString));
        Map<String, Object> method = object(Map.of(
                "name", Map.of("type", "string"), "returnType", nullableString));
        Map<String, Object> classCandidate = object(Map.of(
                "temporaryId", Map.of("type", "string"), "name", Map.of("type", "string"),
                "attributes", array(attribute), "methods", array(method),
                "confidence", Map.of("type", List.of("number", "null"))));
        Map<String, Object> relationship = object(Map.of(
                "temporaryId", Map.of("type", "string"), "sourceClass", Map.of("type", "string"),
                "targetClass", Map.of("type", "string"), "relationshipType", nullableString,
                "sourceCardinality", nullableString, "targetCardinality", nullableString,
                "label", nullableString));
        return object(Map.of(
                "summary", Map.of("type", "string"), "classes", array(classCandidate),
                "relationships", array(relationship), "warnings", array(Map.of("type", "string"))));
    }

    private Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "additionalProperties", false,
                "properties", properties, "required", properties.keySet());
    }

    private Map<String, Object> array(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }
}
