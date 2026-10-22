package com.collabcase.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.ai.dto.AiSuggestedAttribute;
import com.collabcase.ai.dto.AiSuggestedMethod;
import com.collabcase.ai.dto.AiSuggestedParameter;
import com.collabcase.ai.dto.SuggestionType;
import tools.jackson.databind.ObjectMapper;

class AiDiagramAnalysisTests {
    private final AiDiagramContextMapper mapper = new AiDiagramContextMapper();
    private final AiSuggestionValidator validator = new AiSuggestionValidator();

    @Test
    void extractsClassesMembersAndRelationshipsFromApollonV4() {
        AiDiagramContext context = mapper.map(model());

        assertEquals("Cliente", context.classes().getFirst().name());
        assertEquals("correo", context.classes().getFirst().attributes().getFirst().name());
        assertEquals("String", context.classes().getFirst().attributes().getFirst().type());
        assertEquals("registrar", context.classes().getFirst().methods().getFirst().name());
        assertEquals("ClassBidirectional", context.relationships().getFirst().relationshipType());
        assertEquals("1", context.relationships().getFirst().sourceCardinality());
        assertEquals("0..*", context.relationships().getFirst().targetCardinality());
    }

    @Test
    void filtersNormalizedAttributeAndRelationshipDuplicates() {
        AiDiagramContext context = mapper.map(model());
        List<AiSuggestion> suggestions = List.of(
                suggestion(SuggestionType.ADD_ATTRIBUTE, " c1 ", " CORREO ", null, null, null),
                suggestion(SuggestionType.ADD_RELATIONSHIP, "c2", null, "c1", "ClassBidirectional", null),
                suggestion(SuggestionType.ADD_ATTRIBUTE, "c1", "telefono", null, null, null));

        List<AiSuggestion> validated = validator.validate(suggestions, context);

        assertEquals(1, validated.size());
        assertEquals("telefono", validated.getFirst().attributeName());
    }

    @Test
    void rejectsUnchangedOrInvalidCardinalities() {
        AiDiagramContext context = mapper.map(model());
        List<AiSuggestion> suggestions = List.of(
                suggestion(SuggestionType.CHANGE_CARDINALITY, null, null, null, null, "0..*"),
                suggestion(SuggestionType.CHANGE_CARDINALITY, null, null, null, null, "many"));

        assertTrue(validator.validate(suggestions, context).isEmpty());
    }

    @Test
    void acceptsAtomicClassMembersAndRemovesRepeatedStandaloneMembers() {
        AiDiagramContext empty = mapper.map(Map.of("id", "d2", "title", "Biblioteca", "type", "ClassDiagram",
                "nodes", List.of(), "edges", List.of()));
        AiSuggestion addClass = new AiSuggestion(SuggestionType.ADD_CLASS, "Agregar Libro", "Crea Libro", "Es necesaria",
                null, "Libro", null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new AiSuggestedAttribute("id", "Long"), new AiSuggestedAttribute("titulo", "String")),
                List.of(new AiSuggestedMethod("estaDisponible", List.of(), "Boolean")), List.of(), null);
        AiSuggestion repeatedAttribute = new AiSuggestion(SuggestionType.ADD_ATTRIBUTE, "Título", "Descripción", "Razón",
                "Libro", null, "titulo", "String", null, null, null, null, null, null, null, null, null, null, null);

        List<AiSuggestion> validated = validator.validate(List.of(addClass, repeatedAttribute), empty);

        assertEquals(1, validated.size());
        assertEquals(SuggestionType.ADD_CLASS, validated.getFirst().type());
        assertEquals(2, validated.getFirst().attributes().size());
    }

    @Test
    void rejectsAiMethodsWithoutExplicitReturnType() {
        AiSuggestion invalid = new AiSuggestion(SuggestionType.ADD_METHOD, "Método", "Descripción", "Razón",
                "Cliente", null, null, null, "cancelar", null, null, null, null, null, "c1", null, null, null, null,
                List.of(), List.of(), List.of(new AiSuggestedParameter("motivo", "String")), null);

        assertTrue(validator.validate(List.of(invalid), mapper.map(model())).isEmpty());
    }

    @Test
    void keepsLibraryDomainMethodsWithExplicitReturnAndNoInfrastructureMethods() {
        AiDiagramContext empty = mapper.map(Map.of("id", "d3", "title", "Biblioteca", "type", "ClassDiagram",
                "nodes", List.of(), "edges", List.of()));
        AiSuggestion libro = new AiSuggestion(SuggestionType.ADD_CLASS, "Agregar Libro", "Modelo de libro", "Dominio",
                null, "Libro", null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(new AiSuggestedAttribute("id", "Long"), new AiSuggestedAttribute("titulo", "String"),
                        new AiSuggestedAttribute("autor", "String"), new AiSuggestedAttribute("disponible", "Boolean")),
                List.of(new AiSuggestedMethod("estaDisponible", List.of(), "Boolean")), List.of(), null);

        AiSuggestion result = validator.validate(List.of(libro), empty).getFirst();
        assertEquals("estaDisponible", result.methods().getFirst().name());
        assertTrue(result.methods().stream().noneMatch(method -> Set.of("save", "delete", "get_id").contains(method.name())));
    }

    @Test
    void promptRequiresSpanishWithoutTranslatingProtocolEnums() {
        AiProviderRequest request = new AiPromptBuilder(new ObjectMapper()).build(mapper.map(model()),
                "Analiza mi diagrama y dime qué falta.");

        assertTrue(request.systemPrompt().contains("Todo texto dirigido al usuario debe estar en español"));
        assertTrue(request.systemPrompt().contains("ADD_CLASS"));
        assertTrue(request.systemPrompt().contains("No sugieras getters"));
        assertTrue(request.jsonSchema().toString().contains("returnType"));
        assertTrue(request.jsonSchema().toString().contains("attributes"));
    }

    private AiSuggestion suggestion(SuggestionType type, String targetId, String attribute,
            String sourceId, String relationshipType, String targetCardinality) {
        return new AiSuggestion(type, "Título", "Descripción", "Razón", "Cliente", null,
                attribute, "String", null, "Reserva", "Cliente", relationshipType,
                null, targetCardinality, targetId, sourceId,
                type == SuggestionType.CHANGE_CARDINALITY ? "r1" : null, null, null);
    }

    private Map<String, Object> model() {
        Map<String, Object> cliente = Map.of(
                "id", "c1", "type", "class",
                "data", Map.of(
                        "name", "Cliente",
                        "attributes", List.of(Map.of("id", "a1", "name", "+ correo: String")),
                        "methods", List.of(Map.of("id", "m1", "name", "+ registrar(): void"))));
        Map<String, Object> reserva = Map.of(
                "id", "c2", "type", "class",
                "data", Map.of("name", "Reserva", "attributes", List.of(), "methods", List.of()));
        Map<String, Object> relationship = Map.of(
                "id", "r1", "source", "c1", "target", "c2", "type", "ClassBidirectional",
                "data", Map.of("points", List.of(), "sourceMultiplicity", "1", "targetMultiplicity", "0..*"));
        return Map.of("id", "d1", "title", "Prueba", "type", "ClassDiagram",
                "nodes", List.of(cliente, reserva), "edges", List.of(relationship));
    }
}
