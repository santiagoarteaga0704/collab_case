package com.collabcase.codegen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.collabcase.uml.MultiplicityParser;

@Component
public class GenerationValidator {
    private static final Set<String> RELATIONSHIPS = Set.of("ClassAggregation", "ClassBidirectional",
            "ClassComposition", "ClassDependency", "ClassInheritance", "ClassRealization", "ClassUnidirectional");
    private final MultiplicityParser multiplicities;
    public GenerationValidator() { this(new MultiplicityParser()); }
    public GenerationValidator(MultiplicityParser multiplicities) { this.multiplicities = multiplicities; }

    public GenerationValidation validate(GenerationModel model) {
        var errors = new ArrayList<String>(); var warnings = new ArrayList<>(model.warnings());
        if (model.entities().isEmpty()) errors.add("El diagrama no contiene clases.");
        Set<String> classes = new HashSet<>(), ids = new HashSet<>();
        for (var entity : model.entities()) {
            if (entity.javaName() == null) errors.add("La clase '" + entity.name() + "' no puede convertirse a un identificador Java.");
            else if (!classes.add(entity.javaName())) errors.add("Dos clases producen el mismo nombre Java: " + entity.javaName() + ".");
            if (entity.id().idGenerationStrategy() == GenerationModel.IdGenerationStrategy.MANUAL)
                warnings.add("El ID " + entity.name() + "." + entity.id().name() + " de tipo " + entity.id().javaType() + " no tiene una estrategia automática inequívoca; deberá asignarse manualmente.");
            ids.add(entity.sourceElementId()); Set<String> fields = new HashSet<>();
            for (var attribute : entity.attributes()) {
                if (attribute.javaName() == null) errors.add("El atributo '" + attribute.name() + "' de " + entity.name() + " no es un identificador Java válido.");
                else if (!fields.add(attribute.javaName())) errors.add("Atributo repetido en " + entity.name() + ": " + attribute.javaName() + ".");
            }
        }
        for (var relation : model.relationships()) {
            if (!RELATIONSHIPS.contains(relation.originalType())) errors.add("Tipo de relación no soportado: " + relation.originalType() + ".");
            if (!ids.contains(relation.sourceId()) || !ids.contains(relation.targetId())) errors.add("La relación " + relation.originalType() + " referencia una clase inexistente.");
            boolean manyToMany = multiplicities.isManyToMany(relation.sourceCardinality(), relation.targetCardinality());
            if (manyToMany && List.of("ClassInheritance", "ClassRealization").contains(relation.originalType())) errors.add("La multiplicidad muchos-a-muchos no es válida para " + relation.originalType() + ".");
            if (manyToMany && "ClassComposition".equals(relation.originalType())) errors.add("Una composición muchos-a-muchos no representa ownership exclusivo.");
            if (manyToMany && "ClassAggregation".equals(relation.originalType())) warnings.add("La agregación muchos-a-muchos se conserva como metadata y no genera @ManyToMany.");
        }
        return new GenerationValidation(errors.isEmpty(), model.entities().size(), model.relationships().size(), List.copyOf(errors), List.copyOf(warnings));
    }
}
