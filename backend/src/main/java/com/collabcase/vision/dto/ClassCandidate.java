package com.collabcase.vision.dto;

import java.util.List;

public record ClassCandidate(String temporaryId, String name, List<AttributeCandidate> attributes,
        List<MethodCandidate> methods, Double confidence) {
}
