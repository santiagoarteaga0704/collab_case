package com.collabcase.codegen;

import java.util.List;

public record GenerationModel(int schemaVersion, String sourceDiagramId, String applicationName, String artifactId,
        String packageName, List<GenerationEntity> entities, List<GenerationRelationship> relationships,
        List<String> warnings) {
    public record GenerationEntity(String sourceElementId, String name, String javaName, String tableName,
            String apiPath, GenerationAttribute id, boolean syntheticId, String parentJavaName,
            List<GenerationAttribute> attributes, List<GenerationMethod> methods) {}
    public enum IdGenerationStrategy { IDENTITY, UUID, MANUAL }
    public record GenerationAttribute(String sourceId, String name, String javaName, String javaType,
            IdGenerationStrategy idGenerationStrategy) {
        public GenerationAttribute(String sourceId, String name, String javaName, String javaType) {
            this(sourceId, name, javaName, javaType, IdGenerationStrategy.MANUAL);
        }
        public boolean generated() { return idGenerationStrategy != IdGenerationStrategy.MANUAL; }
    }
    public record GenerationMethod(String name, String javaName, String visibility, String returnType,
            List<GenerationParameter> parameters) {}
    public record GenerationParameter(String name, String javaName, String javaType) {}
    public record GenerationRelationship(String originalType, String sourceId, String sourceJavaName,
            String sourceEntity, String targetId, String targetJavaName, String targetEntity,
            String sourceCardinality, String targetCardinality,
            String direction, String mobileReferenceField, String relationType, boolean multiple) {}
}
