package com.collabcase.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.collabcase.ai.context.AiAttributeContext;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiMethodContext;
import com.collabcase.ai.context.AiRelationshipContext;

@Component
public class AiDiagramContextMapper {

    public AiDiagramContext map(Map<String, Object> model) {
        List<AiClassContext> classes = new ArrayList<>();
        Map<String, String> classNames = new LinkedHashMap<>();
        for (Map<String, Object> node : objectList(model.get("nodes"))) {
            if (!"class".equals(string(node.get("type")))) continue;
            Map<String, Object> data = objectMap(node.get("data"));
            String id = string(node.get("id"));
            String name = string(data.get("name"));
            classNames.put(id, name);
            classes.add(new AiClassContext(id, name,
                    attributes(data.get("attributes")), methods(data.get("methods"))));
        }

        List<AiRelationshipContext> relationships = objectList(model.get("edges")).stream()
                .map(edge -> relationship(edge, classNames))
                .toList();
        return new AiDiagramContext(
                string(model.get("id")), string(model.get("title")), string(model.get("type")),
                classes, relationships);
    }

    private List<AiAttributeContext> attributes(Object value) {
        return objectList(value).stream().map(attribute -> {
            String display = string(attribute.get("name"));
            String content = withoutVisibility(display);
            int separator = content.indexOf(':');
            String name = separator < 0 ? content : content.substring(0, separator).trim();
            String type = separator < 0 ? null : blankToNull(content.substring(separator + 1));
            return new AiAttributeContext(string(attribute.get("id")), name, type, display);
        }).toList();
    }

    private List<AiMethodContext> methods(Object value) {
        return objectList(value).stream().map(method -> {
            String signature = string(method.get("name"));
            String content = withoutVisibility(signature);
            int parenthesis = content.indexOf('(');
            String name = parenthesis < 0 ? content : content.substring(0, parenthesis).trim();
            int returnSeparator = content.lastIndexOf(':');
            String returnType = returnSeparator > parenthesis ? blankToNull(content.substring(returnSeparator + 1)) : null;
            return new AiMethodContext(string(method.get("id")), name, returnType, signature);
        }).toList();
    }

    private AiRelationshipContext relationship(Map<String, Object> edge, Map<String, String> classNames) {
        String source = string(edge.get("source"));
        String target = string(edge.get("target"));
        Map<String, Object> data = objectMap(edge.get("data"));
        return new AiRelationshipContext(
                string(edge.get("id")), source, classNames.get(source), target, classNames.get(target),
                string(edge.get("type")), nullableString(data.get("sourceMultiplicity")),
                nullableString(data.get("targetMultiplicity")), nullableString(data.get("label")));
    }

    private String withoutVisibility(String value) {
        return value == null ? "" : value.trim().replaceFirst("^[+#~-]\\s*", "").trim();
    }

    private String blankToNull(String value) {
        String result = value == null ? null : value.trim();
        return result == null || result.isEmpty() ? null : result;
    }

    private String nullableString(Object value) {
        return value == null ? null : blankToNull(value.toString());
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList();
    }
}
