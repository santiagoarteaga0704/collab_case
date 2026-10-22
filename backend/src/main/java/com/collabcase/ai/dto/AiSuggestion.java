package com.collabcase.ai.dto;

import java.util.List;

public record AiSuggestion(
        SuggestionType type,
        String title,
        String description,
        String reason,
        String targetClass,
        String className,
        String attributeName,
        String attributeType,
        String methodName,
        String sourceClass,
        String targetClassName,
        String relationshipType,
        String sourceCardinality,
        String targetCardinality,
        String targetElementId,
        String sourceElementId,
        String targetRelationshipId,
        String currentRelationshipType,
        String suggestedRelationshipType,
        List<AiSuggestedAttribute> attributes,
        List<AiSuggestedMethod> methods,
        List<AiSuggestedParameter> methodParameters,
        String returnType) {

    public AiSuggestion(SuggestionType type, String title, String description, String reason,
            String targetClass, String className, String attributeName, String attributeType,
            String methodName, String sourceClass, String targetClassName, String relationshipType,
            String sourceCardinality, String targetCardinality, String targetElementId,
            String sourceElementId, String targetRelationshipId, String currentRelationshipType,
            String suggestedRelationshipType) {
        this(type, title, description, reason, targetClass, className, attributeName, attributeType,
                methodName, sourceClass, targetClassName, relationshipType, sourceCardinality,
                targetCardinality, targetElementId, sourceElementId, targetRelationshipId,
                currentRelationshipType, suggestedRelationshipType, List.of(), List.of(), List.of(), null);
    }
}
