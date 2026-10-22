package com.collabcase.export;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.collabcase.ai.context.AiAttributeContext;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiRelationshipContext;
import com.collabcase.uml.MultiplicityParser;

@Component
public class SqlDiagramExporter {
    private final MultiplicityParser multiplicities;
    public SqlDiagramExporter() { this(new MultiplicityParser()); }
    public SqlDiagramExporter(MultiplicityParser multiplicities) { this.multiplicities = multiplicities; }
    private record PrimaryKey(String column, String type, boolean synthetic) {}
    private record ForeignKey(String table, String column, String type, String targetTable,
            String targetColumn, boolean nullable, boolean unique, boolean cascade, String constraint) {}

    public ExportResult export(AiDiagramContext context) {
        List<String> warnings = new ArrayList<>();
        Map<String, AiClassContext> classes = new LinkedHashMap<>();
        context.classes().forEach(item -> classes.put(item.id(), item));
        Map<String, String> tables = uniqueNames(context.classes());
        Map<String, PrimaryKey> keys = new HashMap<>();
        for (AiClassContext umlClass : context.classes()) keys.put(umlClass.id(), primaryKey(umlClass, warnings));

        Map<String, String> parents = new HashMap<>();
        for (AiRelationshipContext relationship : context.relationships()) {
            if ("ClassInheritance".equals(relationship.relationshipType())
                    && classes.containsKey(relationship.sourceElementId()) && classes.containsKey(relationship.targetElementId())) {
                parents.put(relationship.sourceElementId(), relationship.targetElementId());
            }
        }

        List<ForeignKey> foreignKeys = new ArrayList<>();
        Set<String> joinTables = new HashSet<>();
        List<String> joins = new ArrayList<>();
        for (AiRelationshipContext relation : context.relationships()) {
            if (Set.of("ClassInheritance", "ClassRealization", "ClassDependency").contains(relation.relationshipType())) continue;
            AiClassContext source = classes.get(relation.sourceElementId());
            AiClassContext target = classes.get(relation.targetElementId());
            if (source == null || target == null) continue;
            boolean sourceMany = multiplicities.isMany(relation.sourceCardinality());
            boolean targetMany = multiplicities.isMany(relation.targetCardinality());
            if (sourceMany && targetMany) {
                if (!Set.of("ClassBidirectional", "ClassUnidirectional").contains(relation.relationshipType())) {
                    if ("ClassComposition".equals(relation.relationshipType())) warnings.add("Una composición muchos-a-muchos no representa ownership exclusivo; no se generó tabla intermedia.");
                    else warnings.add("La relación " + relation.relationshipType() + " muchos-a-muchos no genera tabla intermedia.");
                    continue;
                }
                String first = tables.get(source.id());
                String second = tables.get(target.id());
                String join = first.compareTo(second) <= 0 ? first + "_" + second : second + "_" + first;
                if (joinTables.add(join)) joins.add(joinTable(join, first, keys.get(source.id()), second, keys.get(target.id())));
                continue;
            }
            String ownerId;
            String referencedId;
            boolean unique = !sourceMany && !targetMany;
            if (sourceMany) { ownerId = source.id(); referencedId = target.id(); }
            else if (targetMany) { ownerId = target.id(); referencedId = source.id(); }
            else {
                ownerId = target.id(); referencedId = source.id();
                warnings.add("Ownership de relacion 1:1 " + source.name() + "-" + target.name() + " fue inferido en el lado target.");
            }
            PrimaryKey referencedKey = keys.get(referencedId);
            String column = tables.get(referencedId) + "_" + referencedKey.column();
            boolean nullable = !"1".equals(ownerId.equals(source.id())
                    ? relation.targetCardinality() : relation.sourceCardinality());
            boolean cascade = "ClassComposition".equals(relation.relationshipType()) && (sourceMany || targetMany);
            if ("ClassComposition".equals(relation.relationshipType()) && !cascade) {
                warnings.add("No se pudo determinar con seguridad el dependiente de la composicion "
                        + source.name() + "-" + target.name() + "; no se genero CASCADE.");
            }
            String ownerTable = tables.get(ownerId);
            foreignKeys.add(new ForeignKey(ownerTable, column, referencedKey.type(), tables.get(referencedId),
                    referencedKey.column(), nullable, unique, cascade, "fk_" + ownerTable + "_" + column));
        }

        StringBuilder sql = new StringBuilder("-- PostgreSQL DDL generated deterministically from the UML model.\n\n");
        for (AiClassContext umlClass : context.classes()) {
            String table = tables.get(umlClass.id());
            PrimaryKey key = keys.get(umlClass.id());
            String parentId = parents.get(umlClass.id());
            sql.append("CREATE TABLE ").append(q(table)).append(" (\n");
            List<String> columns = new ArrayList<>();
            if (parentId != null) {
                PrimaryKey parentKey = keys.get(parentId);
                keys.put(umlClass.id(), new PrimaryKey(parentKey.column(), parentKey.type(), false));
                columns.add("  " + q(parentKey.column()) + " " + parentKey.type() + " PRIMARY KEY");
            } else if (key.synthetic()) {
                columns.add("  -- Generated synthetic primary key because the UML class had no identifier.\n  \"id\" UUID PRIMARY KEY");
            }
            for (AiAttributeContext attribute : umlClass.attributes()) {
                String column = snake(attribute.name());
                String type = sqlType(attribute.type(), umlClass.name(), attribute.name(), warnings);
                boolean primary = parentId == null && column.equals(key.column());
                columns.add("  " + q(column) + " " + type + (primary ? " PRIMARY KEY" : ""));
            }
            for (ForeignKey fk : foreignKeys) if (fk.table().equals(table)) {
                columns.add("  " + q(fk.column()) + " " + fk.type()
                        + (fk.nullable() ? "" : " NOT NULL") + (fk.unique() ? " UNIQUE" : ""));
            }
            sql.append(String.join(",\n", columns)).append("\n);\n\n");
            if (parentId != null) {
                String parentTable = tables.get(parentId);
                PrimaryKey parentKey = keys.get(parentId);
                sql.append("ALTER TABLE ").append(q(table)).append(" ADD CONSTRAINT ")
                        .append(q("fk_" + table + "_parent")).append(" FOREIGN KEY (").append(q(parentKey.column()))
                        .append(") REFERENCES ").append(q(parentTable)).append(" (").append(q(parentKey.column()))
                        .append(") ON DELETE CASCADE;\n\n");
            }
        }
        joins.forEach(join -> sql.append(join).append("\n"));
        for (ForeignKey fk : foreignKeys) {
            sql.append("ALTER TABLE ").append(q(fk.table())).append(" ADD CONSTRAINT ").append(q(fk.constraint()))
                    .append(" FOREIGN KEY (").append(q(fk.column())).append(") REFERENCES ")
                    .append(q(fk.targetTable())).append(" (").append(q(fk.targetColumn())).append(")")
                    .append(fk.cascade() ? " ON DELETE CASCADE" : "").append(";\n\n");
        }
        return new ExportResult(sql.toString(), List.copyOf(warnings));
    }

    private Map<String, String> uniqueNames(List<AiClassContext> classes) {
        Map<String, String> result = new LinkedHashMap<>(); Set<String> used = new HashSet<>();
        for (AiClassContext item : classes) { String base = snake(item.name()); String name = base; int i = 2;
            while (!used.add(name)) name = base + "_" + i++; result.put(item.id(), name); }
        return result;
    }
    private PrimaryKey primaryKey(AiClassContext umlClass, List<String> warnings) {
        String expected = snake(umlClass.name()) + "_id";
        List<AiAttributeContext> candidates = umlClass.attributes().stream()
                .filter(a -> snake(a.name()).equals("id") || snake(a.name()).equals(expected)).toList();
        if (candidates.size() == 1) { AiAttributeContext a = candidates.getFirst();
            return new PrimaryKey(snake(a.name()), sqlType(a.type(), umlClass.name(), a.name(), warnings), false); }
        warnings.add("Clase " + umlClass.name() + " no tenia un identificador unico; se genero UUID.");
        return new PrimaryKey("id", "UUID", true);
    }
    private String sqlType(String type, String className, String attribute, List<String> warnings) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "string", "text" -> "VARCHAR(255)"; case "integer", "int" -> "INTEGER";
            case "long" -> "BIGINT"; case "boolean", "bool" -> "BOOLEAN";
            case "double" -> "DOUBLE PRECISION"; case "float" -> "REAL";
            case "bigdecimal", "decimal" -> "NUMERIC(19,2)"; case "date", "localdate" -> "DATE";
            case "datetime", "localdatetime", "timestamp" -> "TIMESTAMP"; case "uuid" -> "UUID";
            default -> { warnings.add("Tipo " + (type == null ? "desconocido" : type) + " de " + className + "." + attribute
                    + " no reconocido; se mapeo a VARCHAR(255)."); yield "VARCHAR(255)"; }
        };
    }
    private String joinTable(String table, String first, PrimaryKey firstKey, String second, PrimaryKey secondKey) {
        String a = first + "_" + firstKey.column(); String b = second + "_" + secondKey.column();
        return "CREATE TABLE " + q(table) + " (\n  " + q(a) + " " + firstKey.type() + " NOT NULL,\n  "
                + q(b) + " " + secondKey.type() + " NOT NULL,\n  PRIMARY KEY (" + q(a) + ", " + q(b)
                + "),\n  CONSTRAINT " + q("fk_" + table + "_" + first) + " FOREIGN KEY (" + q(a) + ") REFERENCES " + q(first) + " (" + q(firstKey.column())
                + "),\n  CONSTRAINT " + q("fk_" + table + "_" + second) + " FOREIGN KEY (" + q(b) + ") REFERENCES " + q(second) + " (" + q(secondKey.column()) + ")\n);\n";
    }
    private String snake(String value) { String ascii = Normalizer.normalize(value == null ? "unnamed" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("[^A-Za-z0-9]+", "_")
            .replaceAll("^_+|_+$", "").toLowerCase(Locale.ROOT); return ascii.isBlank() ? "unnamed" : ascii; }
    private String q(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
