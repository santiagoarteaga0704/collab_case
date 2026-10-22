package com.collabcase.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.collabcase.ai.context.AiAttributeContext;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiRelationshipContext;
import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.ai.dto.AiSuggestedAttribute;
import com.collabcase.ai.dto.AiSuggestedMethod;
import com.collabcase.ai.dto.SuggestionType;

@Component
public class AiSuggestionValidator {
    private static final Set<String> RELATIONSHIP_TYPES = Set.of(
            "ClassBidirectional", "ClassUnidirectional", "ClassAggregation", "ClassComposition",
            "ClassInheritance", "ClassDependency", "ClassRealization");
    private static final Map<String, String> RELATIONSHIP_ALIASES = Map.ofEntries(
            Map.entry("ASSOCIATION", "ClassBidirectional"),
            Map.entry("BIDIRECTIONAL", "ClassBidirectional"),
            Map.entry("UNIDIRECTIONAL", "ClassUnidirectional"),
            Map.entry("AGGREGATION", "ClassAggregation"),
            Map.entry("COMPOSITION", "ClassComposition"),
            Map.entry("INHERITANCE", "ClassInheritance"),
            Map.entry("DEPENDENCY", "ClassDependency"),
            Map.entry("REALIZATION", "ClassRealization"));

    public List<AiSuggestion> validate(List<AiSuggestion> suggestions, AiDiagramContext context) {
        Map<String, AiClassContext> classesById = context.classes().stream()
                .collect(Collectors.toMap(AiClassContext::id, Function.identity()));
        Map<String, AiRelationshipContext> relationshipsById = context.relationships().stream()
                .collect(Collectors.toMap(AiRelationshipContext::id, Function.identity()));
        Set<String> existingClasses = context.classes().stream().map(item -> normalize(item.name()))
                .collect(Collectors.toSet());
        Set<String> suggestedClasses = suggestions.stream()
                .filter(item -> item != null && item.type() == SuggestionType.ADD_CLASS)
                .map(item -> normalize(item.className())).filter(name -> !name.isEmpty())
                .collect(Collectors.toSet());
        Set<String> emitted = new HashSet<>();
        Set<String> embedded = suggestions.stream().filter(item -> item != null && item.type() == SuggestionType.ADD_CLASS)
                .flatMap(item -> {
                    var keys = new ArrayList<String>();
                    safe(item.attributes()).forEach(attribute -> keys.add("attribute:" + normalize(item.className()) + ":" + normalize(attribute.name())));
                    safe(item.methods()).forEach(method -> keys.add("method:" + normalize(item.className()) + ":" + normalizeMethod(method.name())));
                    return keys.stream();
                }).collect(Collectors.toSet());
        List<AiSuggestion> result = new ArrayList<>();

        for (AiSuggestion suggestion : suggestions) {
            AiSuggestion valid = validateOne(suggestion, context, classesById, relationshipsById,
                    existingClasses, suggestedClasses);
            if (valid == null) continue;
            String embeddedKey = memberClassKey(valid);
            if (embeddedKey != null && embedded.contains(embeddedKey)) continue;
            String key = suggestionKey(valid);
            if (emitted.add(key)) result.add(valid);
        }
        return result;
    }

    private AiSuggestion validateOne(AiSuggestion suggestion, AiDiagramContext context,
            Map<String, AiClassContext> classesById,
            Map<String, AiRelationshipContext> relationshipsById,
            Set<String> existingClasses,
            Set<String> suggestedClasses) {
        if (suggestion == null || suggestion.type() == null) return null;
        return switch (suggestion.type()) {
            case ADD_CLASS -> validateClass(suggestion, existingClasses);
            case ADD_ATTRIBUTE -> validateAttribute(suggestion, context, classesById, suggestedClasses);
            case ADD_METHOD -> validateMethod(suggestion, context, classesById, suggestedClasses);
            case ADD_RELATIONSHIP -> validateRelationship(suggestion, context, classesById,
                    existingClasses, suggestedClasses);
            case CHANGE_CARDINALITY -> validateCardinality(suggestion, relationshipsById);
            case CHANGE_RELATIONSHIP_TYPE -> validateRelationshipType(suggestion, relationshipsById);
            case WARNING, IMPROVEMENT -> suggestion;
        };
    }

    private AiSuggestion validateClass(AiSuggestion suggestion, Set<String> existingClasses) {
        if (existingClasses.contains(normalize(suggestion.className())) || normalize(suggestion.className()).isEmpty()) return null;
        Set<String> attributes = new HashSet<>();
        for (AiSuggestedAttribute attribute : safe(suggestion.attributes())) {
            if (normalize(attribute.name()).isEmpty() || clean(attribute.dataType()) == null
                    || !attributes.add(normalize(attribute.name()))) return null;
        }
        Set<String> methods = new HashSet<>();
        for (AiSuggestedMethod method : safe(suggestion.methods())) {
            if (normalizeMethod(method.name()).isEmpty() || clean(method.returnType()) == null
                    || !methods.add(normalizeMethod(method.name()))
                    || safe(method.parameters()).stream().anyMatch(p -> normalize(p.name()).isEmpty() || clean(p.dataType()) == null)) return null;
        }
        return suggestion;
    }

    private AiSuggestion validateAttribute(AiSuggestion suggestion, AiDiagramContext context,
            Map<String, AiClassContext> classesById, Set<String> suggestedClasses) {
        AiClassContext target = resolveClass(suggestion.targetElementId(), suggestion.targetClass(), context, classesById);
        String attributeName = normalize(suggestion.attributeName());
        if (attributeName.isEmpty()) return null;
        if (target == null) return suggestedClasses.contains(normalize(suggestion.targetClass())) ? suggestion : null;
        AiAttributeContext existing = target.attributes().stream()
                .filter(attribute -> normalize(attribute.name()).equals(attributeName)).findFirst().orElse(null);
        if (existing == null) return withReferences(suggestion, target.id(), suggestion.sourceElementId(), null,
                suggestion.relationshipType(), suggestion.currentRelationshipType(), suggestion.suggestedRelationshipType());
        if (!normalize(existing.type()).equals(normalize(suggestion.attributeType()))) {
            return informative(suggestion, "El atributo " + existing.name() + " ya existe en " + target.name()
                    + (existing.type() == null ? "." : " con tipo " + existing.type() + "."));
        }
        return null;
    }

    private AiSuggestion validateMethod(AiSuggestion suggestion, AiDiagramContext context,
            Map<String, AiClassContext> classesById, Set<String> suggestedClasses) {
        AiClassContext target = resolveClass(suggestion.targetElementId(), suggestion.targetClass(), context, classesById);
        String methodName = normalizeMethod(suggestion.methodName());
        if (methodName.isEmpty() || clean(suggestion.returnType()) == null
                || safe(suggestion.methodParameters()).stream().anyMatch(p -> normalize(p.name()).isEmpty() || clean(p.dataType()) == null)) return null;
        if (target == null) return suggestedClasses.contains(normalize(suggestion.targetClass())) ? suggestion : null;
        boolean duplicate = target.methods().stream().anyMatch(method -> normalizeMethod(method.name()).equals(methodName));
        return duplicate ? null : withReferences(suggestion, target.id(), suggestion.sourceElementId(), null,
                suggestion.relationshipType(), suggestion.currentRelationshipType(), suggestion.suggestedRelationshipType());
    }

    private AiSuggestion validateRelationship(AiSuggestion suggestion, AiDiagramContext context,
            Map<String, AiClassContext> classesById, Set<String> existingClasses, Set<String> suggestedClasses) {
        AiClassContext source = resolveClass(suggestion.sourceElementId(), suggestion.sourceClass(), context, classesById);
        AiClassContext target = resolveClass(suggestion.targetElementId(), suggestion.targetClassName(), context, classesById);
        String sourceName = source == null ? normalize(suggestion.sourceClass()) : normalize(source.name());
        String targetName = target == null ? normalize(suggestion.targetClassName()) : normalize(target.name());
        if (!(existingClasses.contains(sourceName) || suggestedClasses.contains(sourceName))
                || !(existingClasses.contains(targetName) || suggestedClasses.contains(targetName))
                || sourceName.equals(targetName)) return null;
        String relationshipType = relationshipType(suggestion.relationshipType());
        if (relationshipType == null) return null;
        if (source != null && target != null && relationshipExists(context, source.id(), target.id(), relationshipType)) {
            return null;
        }
        return withReferences(suggestion, target == null ? null : target.id(), source == null ? null : source.id(),
                null, relationshipType, suggestion.currentRelationshipType(), suggestion.suggestedRelationshipType());
    }

    private AiSuggestion validateCardinality(AiSuggestion suggestion,
            Map<String, AiRelationshipContext> relationshipsById) {
        AiRelationshipContext relationship = relationshipsById.get(suggestion.targetRelationshipId());
        String source = clean(suggestion.sourceCardinality());
        String target = clean(suggestion.targetCardinality());
        if (relationship == null || source == null && target == null
                || source != null && !validCardinality(source)
                || target != null && !validCardinality(target)) return null;
        boolean changesSource = source != null && !source.equals(clean(relationship.sourceCardinality()));
        boolean changesTarget = target != null && !target.equals(clean(relationship.targetCardinality()));
        return changesSource || changesTarget ? withRelationshipContext(suggestion, relationship,
                relationship.relationshipType(), suggestion.currentRelationshipType(),
                suggestion.suggestedRelationshipType()) : null;
    }

    private AiSuggestion validateRelationshipType(AiSuggestion suggestion,
            Map<String, AiRelationshipContext> relationshipsById) {
        AiRelationshipContext relationship = relationshipsById.get(suggestion.targetRelationshipId());
        String suggested = relationshipType(suggestion.suggestedRelationshipType());
        if (relationship == null || suggested == null || suggested.equals(relationship.relationshipType())) return null;
        return withRelationshipContext(suggestion, relationship, relationship.relationshipType(),
                relationship.relationshipType(), suggested);
    }

    private AiClassContext resolveClass(String id, String name, AiDiagramContext context,
            Map<String, AiClassContext> classesById) {
        AiClassContext byId = classesById.get(id);
        if (byId != null) return byId;
        List<AiClassContext> byName = context.classes().stream()
                .filter(item -> normalize(item.name()).equals(normalize(name))).toList();
        return byName.size() == 1 ? byName.getFirst() : null;
    }

    private boolean relationshipExists(AiDiagramContext context, String source, String target, String type) {
        return context.relationships().stream().anyMatch(item -> item.relationshipType().equals(type)
                && (item.sourceElementId().equals(source) && item.targetElementId().equals(target)
                    || "ClassBidirectional".equals(type)
                        && item.sourceElementId().equals(target) && item.targetElementId().equals(source)));
    }

    private boolean validCardinality(String value) {
        return value != null && value.matches("(?:\\d+|\\*)(?:\\.\\.(?:\\d+|\\*))?");
    }

    private String relationshipType(String value) {
        String clean = clean(value);
        if (clean == null) return null;
        if (RELATIONSHIP_TYPES.contains(clean)) return clean;
        return RELATIONSHIP_ALIASES.get(clean.toUpperCase(Locale.ROOT));
    }

    private String suggestionKey(AiSuggestion suggestion) {
        return switch (suggestion.type()) {
            case ADD_CLASS -> "class:" + normalize(suggestion.className());
            case ADD_ATTRIBUTE -> "attribute:" + suggestion.targetElementId() + ":" + normalize(suggestion.attributeName());
            case ADD_METHOD -> "method:" + suggestion.targetElementId() + ":" + normalizeMethod(suggestion.methodName());
            case ADD_RELATIONSHIP -> "relationship:"
                    + (suggestion.sourceElementId() == null ? normalize(suggestion.sourceClass()) : suggestion.sourceElementId())
                    + ":" + (suggestion.targetElementId() == null
                            ? normalize(suggestion.targetClassName()) : suggestion.targetElementId())
                    + ":" + suggestion.relationshipType();
            case CHANGE_CARDINALITY -> "cardinality:" + suggestion.targetRelationshipId() + ":"
                    + clean(suggestion.sourceCardinality()) + ":" + clean(suggestion.targetCardinality());
            case CHANGE_RELATIONSHIP_TYPE -> "relationship-type:" + suggestion.targetRelationshipId() + ":"
                    + suggestion.suggestedRelationshipType();
            case WARNING, IMPROVEMENT -> suggestion.type() + ":" + normalize(suggestion.title());
        };
    }

    private String memberClassKey(AiSuggestion suggestion) {
        String target = normalize(suggestion.targetClass());
        return switch (suggestion.type()) {
            case ADD_ATTRIBUTE -> "attribute:" + target + ":" + normalize(suggestion.attributeName());
            case ADD_METHOD -> "method:" + target + ":" + normalizeMethod(suggestion.methodName());
            default -> null;
        };
    }

    private <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }

    private AiSuggestion informative(AiSuggestion source, String detail) {
        return new AiSuggestion(SuggestionType.IMPROVEMENT, source.title(), detail, source.reason(),
                source.targetClass(), source.className(), source.attributeName(), source.attributeType(),
                source.methodName(), source.sourceClass(), source.targetClassName(), source.relationshipType(),
                source.sourceCardinality(), source.targetCardinality(), null, null, null, null, null,
                source.attributes(), source.methods(), source.methodParameters(), source.returnType());
    }

    private AiSuggestion withReferences(AiSuggestion source, String targetElementId, String sourceElementId,
            String targetRelationshipId, String relationshipType, String currentType, String suggestedType) {
        return new AiSuggestion(source.type(), source.title(), source.description(), source.reason(),
                source.targetClass(), source.className(), source.attributeName(), source.attributeType(),
                source.methodName(), source.sourceClass(), source.targetClassName(), relationshipType,
                source.sourceCardinality(), source.targetCardinality(), targetElementId, sourceElementId,
                targetRelationshipId, currentType, suggestedType, source.attributes(), source.methods(),
                source.methodParameters(), source.returnType());
    }

    private AiSuggestion withRelationshipContext(AiSuggestion source, AiRelationshipContext relationship,
            String relationshipType, String currentType, String suggestedType) {
        return new AiSuggestion(source.type(), source.title(), source.description(), source.reason(),
                source.targetClass(), source.className(), source.attributeName(), source.attributeType(),
                source.methodName(), relationship.sourceClass(), relationship.targetClass(), relationshipType,
                source.sourceCardinality(), source.targetCardinality(), relationship.targetElementId(),
                relationship.sourceElementId(), relationship.id(), currentType, suggestedType, source.attributes(),
                source.methods(), source.methodParameters(), source.returnType());
    }

    private String normalizeMethod(String value) {
        String normalized = normalize(value);
        int parenthesis = normalized.indexOf('(');
        return parenthesis < 0 ? normalized : normalized.substring(0, parenthesis).trim();
    }

    private String normalize(String value) {
        String clean = clean(value);
        return clean == null ? "" : clean.replaceFirst("^[+#~-]\\s*", "").trim().toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
