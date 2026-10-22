package com.collabcase.importer;

import java.util.List;
import java.util.Map;

public record ImportedDiagram(String title, Map<String, Object> model, int classes, int attributes,
        int methods, int relationships, List<String> warnings) {
}
