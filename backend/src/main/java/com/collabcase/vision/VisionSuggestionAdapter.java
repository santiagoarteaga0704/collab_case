package com.collabcase.vision;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.ai.dto.SuggestionType;
import com.collabcase.vision.dto.ClassCandidate;
import com.collabcase.vision.dto.RelationshipCandidate;
import com.collabcase.vision.dto.VisionExtraction;

@Component
public class VisionSuggestionAdapter {
    public List<AiSuggestion> adapt(VisionExtraction extraction, AiDiagramContext context) {
        List<AiSuggestion> result = new ArrayList<>();
        for (ClassCandidate candidate : extraction.classes()) {
            String classId = context.classes().stream()
                    .filter(item -> normalize(item.name()).equals(normalize(candidate.name())))
                    .map(item -> item.id()).findFirst().orElse(null);
            if (classId == null) result.add(suggestion(SuggestionType.ADD_CLASS,
                    "Agregar clase " + candidate.name(), "Clase detectada en la imagen.", candidate.name(),
                    null, candidate.name(), null, null, null, null, null, null, null, null, null, null));
            candidate.attributes().forEach(attribute -> result.add(suggestion(SuggestionType.ADD_ATTRIBUTE,
                    "Agregar atributo " + attribute.name(), "Atributo visible en la clase " + candidate.name() + ".",
                    candidate.name(), candidate.name(), null, attribute.name(), attribute.type(), null,
                    null, null, null, null, classId, null, null)));
            candidate.methods().forEach(method -> result.add(withReturnType(suggestion(SuggestionType.ADD_METHOD,
                    "Agregar metodo " + methodName(method.name()), "Metodo visible en la clase " + candidate.name() + ".",
                    candidate.name(), candidate.name(), null, null, null, methodName(method.name()),
                    null, null, null, null, classId, null, null), method.returnType())));
        }
        for (RelationshipCandidate relationship : extraction.relationships()) {
            result.add(suggestion(SuggestionType.ADD_RELATIONSHIP,
                    "Agregar relacion " + relationship.sourceClass() + " - " + relationship.targetClass(),
                    "Relacion detectada en la imagen.", relationship.sourceClass() + " y " + relationship.targetClass(),
                    null, null, null, null, null, relationship.sourceClass(), relationship.targetClass(),
                    relationship.relationshipType(), relationship.sourceCardinality(), relationship.targetCardinality(),
                    null, null));
        }
        return result;
    }

    private AiSuggestion suggestion(SuggestionType type, String title, String description, String reason,
            String targetClass, String className, String attributeName, String attributeType, String methodName,
            String sourceClass, String targetClassName, String relationshipType, String sourceCardinality,
            String targetCardinality, String targetElementId, String sourceElementId) {
        return new AiSuggestion(type, title, description, reason, targetClass, className, attributeName,
                attributeType, methodName, sourceClass, targetClassName, relationshipType, sourceCardinality,
                targetCardinality, targetElementId, sourceElementId, null, null, null);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    /** "comprar()" → "comprar": la web arma la firma con el nombre y el tipo de retorno por separado. */
    static String methodName(String name) {
        String bare = name.strip();
        return bare.endsWith("()") ? bare.substring(0, bare.length() - 2).strip() : bare;
    }

    /** El validador exige tipo de retorno; si la foto no lo muestra se asume void. */
    private static AiSuggestion withReturnType(AiSuggestion s, String returnType) {
        String type = returnType == null || returnType.isBlank() ? "void" : returnType.strip();
        return new AiSuggestion(s.type(), s.title(), s.description(), s.reason(), s.targetClass(), s.className(),
                s.attributeName(), s.attributeType(), s.methodName(), s.sourceClass(), s.targetClassName(),
                s.relationshipType(), s.sourceCardinality(), s.targetCardinality(), s.targetElementId(),
                s.sourceElementId(), s.targetRelationshipId(), s.currentRelationshipType(),
                s.suggestedRelationshipType(), s.attributes(), s.methods(), s.methodParameters(), type);
    }
}
