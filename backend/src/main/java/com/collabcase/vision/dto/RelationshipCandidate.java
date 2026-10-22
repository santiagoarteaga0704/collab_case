package com.collabcase.vision.dto;

public record RelationshipCandidate(String temporaryId, String sourceClass, String targetClass,
        String relationshipType, String sourceCardinality, String targetCardinality, String label) {
}
