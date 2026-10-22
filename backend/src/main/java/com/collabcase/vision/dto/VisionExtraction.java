package com.collabcase.vision.dto;

import java.util.List;

public record VisionExtraction(String summary, List<ClassCandidate> classes,
        List<RelationshipCandidate> relationships, List<String> warnings) {
}
