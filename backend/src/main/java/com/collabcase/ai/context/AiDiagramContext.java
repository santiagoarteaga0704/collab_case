package com.collabcase.ai.context;

import java.util.List;

public record AiDiagramContext(
        String id,
        String title,
        String type,
        List<AiClassContext> classes,
        List<AiRelationshipContext> relationships) {
}
