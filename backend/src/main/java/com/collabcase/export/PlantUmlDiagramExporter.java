package com.collabcase.export;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiRelationshipContext;

@Component
public class PlantUmlDiagramExporter {
    private static final Map<String, String> ARROWS = Map.of(
            "ClassBidirectional", "--", "ClassUnidirectional", "-->",
            "ClassAggregation", "o--", "ClassComposition", "*--",
            "ClassInheritance", "--|>", "ClassDependency", "..>",
            "ClassRealization", "..|>");

    public ExportResult export(AiDiagramContext context) {
        StringBuilder output = new StringBuilder("@startuml\n");
        Map<String, String> aliases = new HashMap<>();
        int index = 1;
        for (AiClassContext umlClass : context.classes()) {
            String alias = "C_" + index++;
            aliases.put(umlClass.id(), alias);
            output.append("class \"").append(escape(umlClass.name())).append("\" as ").append(alias).append(" {\n");
            umlClass.attributes().forEach(attribute -> output.append("  ")
                    .append(escapeMember(attribute.displayName())).append("\n"));
            umlClass.methods().forEach(method -> output.append("  ")
                    .append(escapeMember(method.signature())).append("\n"));
            output.append("}\n\n");
        }
        for (AiRelationshipContext relationship : context.relationships()) {
            String source = aliases.get(relationship.sourceElementId());
            String target = aliases.get(relationship.targetElementId());
            if (source == null || target == null) continue;
            String arrow = ARROWS.getOrDefault(relationship.relationshipType(), "--");
            output.append(source);
            if (present(relationship.sourceCardinality())) output.append(" \"").append(escape(relationship.sourceCardinality())).append("\"");
            output.append(" ").append(arrow).append(" ");
            if (present(relationship.targetCardinality())) output.append("\"").append(escape(relationship.targetCardinality())).append("\" ");
            output.append(target);
            if (present(relationship.label())) output.append(" : ").append(escape(relationship.label()));
            output.append("\n");
        }
        output.append("@enduml\n");
        return new ExportResult(output.toString(), List.of());
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
    private String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private String escapeMember(String value) { return escape(value).replace("{", "\\{").replace("}", "\\}"); }
}
