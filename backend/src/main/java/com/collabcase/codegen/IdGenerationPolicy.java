package com.collabcase.codegen;

import org.springframework.stereotype.Component;

import com.collabcase.codegen.GenerationModel.IdGenerationStrategy;

@Component
public class IdGenerationPolicy {
    public IdGenerationStrategy forType(String javaType) {
        return switch (javaType) {
            case "Long", "Integer" -> IdGenerationStrategy.IDENTITY;
            case "UUID" -> IdGenerationStrategy.UUID;
            default -> IdGenerationStrategy.MANUAL;
        };
    }
}
