package com.collabcase.teamai.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.collabcase.ai.AiSuggestionValidator;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiRelationshipContext;
import com.collabcase.ai.dto.AiSuggestedAttribute;
import com.collabcase.ai.dto.AiSuggestedMethod;
import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.ai.dto.SuggestionType;
import com.collabcase.teamai.assistant.TeamAiReply.Changes;

/** Cleans the model's changes and turns them into validated {@link AiSuggestion}s. */
@Component
public class ChangeTranslator {
    private static final Map<String, String> RELATION_TYPES = Map.of(
            "ASOCIACION", "ASSOCIATION", "AGREGACION", "AGGREGATION", "COMPOSICION", "COMPOSITION",
            "HERENCIA", "INHERITANCE", "DEPENDENCIA", "DEPENDENCY", "REALIZACION", "REALIZATION");
    private static final String DEFAULT_TYPE = "String";

    private final AiSuggestionValidator validator;

    public ChangeTranslator(AiSuggestionValidator validator) {
        this.validator = validator;
    }

    public List<AiSuggestion> translate(Changes changes, AiDiagramContext ctx, String requestedBy) {
        if (changes == null) return List.of();
        String title = "Pedido de " + requestedBy;
        Set<String> existing = ctx.classes().stream().map(c -> key(c.name())).collect(Collectors.toSet());
        List<AiSuggestion> out = new ArrayList<>();

        for (var c : changes.clasesNuevas()) {
            if (c == null) continue;
            String name = cleanName(c.nombre());
            if (name.isEmpty() || existing.contains(key(name))) continue;
            List<AiSuggestedAttribute> attributes = new ArrayList<>();
            for (var a : c.atributos()) {
                if (a == null) continue;
                String[] p = split(a.nombre(), a.tipo());
                if (!p[0].isEmpty()) attributes.add(new AiSuggestedAttribute(p[0], orDefault(p[1], DEFAULT_TYPE)));
            }
            List<AiSuggestedMethod> methods = new ArrayList<>();
            for (var m : c.metodos()) {
                if (m == null) continue;
                String methodName = cleanName(m.nombre());
                int paren = methodName.indexOf('(');
                if (paren >= 0) methodName = methodName.substring(0, paren).trim();
                if (!methodName.isEmpty()) {
                    methods.add(new AiSuggestedMethod(methodName, List.of(), orDefault(trim(m.retorno()), "void")));
                }
            }
            out.add(new AiSuggestion(SuggestionType.ADD_CLASS, title, name, name, null, name, null, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    attributes, methods, List.of(), null));
        }

        for (var a : changes.atributosNuevos()) {
            if (a == null) continue;
            String clazz = cleanName(a.clase());
            String[] p = split(a.nombre(), a.tipo());
            if (clazz.isEmpty() || p[0].isEmpty()) continue;
            out.add(new AiSuggestion(SuggestionType.ADD_ATTRIBUTE, title, p[0], p[0], clazz, null, p[0],
                    orDefault(p[1], DEFAULT_TYPE), null, null, null, null, null, null, null, null, null, null, null));
        }

        for (var r : changes.relacionesNuevas()) {
            if (r == null) continue;
            String type = RELATION_TYPES.get(upper(r.tipo()));
            String from = cleanName(r.origen());
            String to = cleanName(r.destino());
            if (type == null || from.isEmpty() || to.isEmpty()) continue;
            String label = from + " - " + to;
            out.add(new AiSuggestion(SuggestionType.ADD_RELATIONSHIP, title, label, label, null, null, null, null,
                    null, from, to, type, multiplicity(r.multOrigen()), multiplicity(r.multDestino()),
                    null, null, null, null, null));
        }

        for (var ch : changes.cambiosRelacion()) {
            if (ch == null) continue;
            String from = cleanName(ch.origen());
            String to = cleanName(ch.destino());
            AiRelationshipContext direct = find(ctx, from, to);
            AiRelationshipContext found = direct != null ? direct : find(ctx, to, from);
            if (found == null) continue;
            boolean reversed = direct == null;
            String label = from + " - " + to;
            String newType = RELATION_TYPES.get(upper(ch.tipoNuevo()));
            if (newType != null) {
                out.add(new AiSuggestion(SuggestionType.CHANGE_RELATIONSHIP_TYPE, title, label, label, null, null,
                        null, null, null, null, null, null, null, null, null, null, found.id(), null, newType));
            }
            String first = multiplicity(ch.multOrigen());
            String second = multiplicity(ch.multDestino());
            if (first != null || second != null) {
                out.add(new AiSuggestion(SuggestionType.CHANGE_CARDINALITY, title, label, label, null, null, null,
                        null, null, null, null, null, reversed ? second : first, reversed ? first : second,
                        null, null, found.id(), null, null));
            }
        }
        return validator.validate(out, ctx);
    }

    private static AiRelationshipContext find(AiDiagramContext ctx, String source, String target) {
        return ctx.relationships().stream()
                .filter(r -> key(r.sourceClass()).equals(key(source)) && key(r.targetClass()).equals(key(target)))
                .findFirst().orElse(null);
    }

    private static String[] split(String rawName, String rawType) {
        String name = cleanName(rawName);
        String type = trim(rawType);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            String glued = name.substring(colon + 1).trim();
            name = name.substring(0, colon).trim();
            if (!glued.isEmpty() && (type == null || type.equalsIgnoreCase(glued))) type = glued;
        }
        return new String[] { name, type };
    }

    private static String cleanName(String value) {
        if (value == null) return "";
        return value.trim().replaceFirst("^[+\\-#~]\\s*", "").trim();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String upper(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }

    private static String key(String value) {
        return cleanName(value).toLowerCase(Locale.ROOT);
    }

    private static String multiplicity(String value) {
        String v = trim(value);
        if (v == null) return null;
        v = v.toLowerCase(Locale.ROOT);
        if (v.equals("n") || v.equals("muchos") || v.equals("*")) return "*";
        return v.replaceAll("\\.\\.(n|\\*)$", "..*");
    }
}
