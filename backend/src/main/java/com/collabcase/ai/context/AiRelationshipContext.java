package com.collabcase.ai.context;

public record AiRelationshipContext(
        String id,
        String sourceElementId,
        String sourceClass,
        String targetElementId,
        String targetClass,
        String relationshipType,
        String sourceCardinality,
        String targetCardinality,
        String label) {
}
