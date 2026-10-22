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
            candidate.methods().forEach(method -> result.add(suggestion(SuggestionType.ADD_METHOD,
                    "Agregar metodo " + method.name(), "Metodo visible en la clase " + candidate.name() + ".",
                    candidate.name(), candidate.name(), null, null, null,
                    method.returnType() == null ? method.name() : method.name() + "(): " + method.returnType(),
                    null, null, null, null, classId, null, null)));
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
}
