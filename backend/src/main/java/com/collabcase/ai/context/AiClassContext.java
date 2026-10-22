package com.collabcase.ai.context;

import java.util.List;

public record AiClassContext(
        String id,
        String name,
        List<AiAttributeContext> attributes,
        List<AiMethodContext> methods) {
}
