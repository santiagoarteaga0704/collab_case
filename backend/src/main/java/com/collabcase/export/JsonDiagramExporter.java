package com.collabcase.export;

import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class JsonDiagramExporter {
    private final ObjectMapper objectMapper;

    public JsonDiagramExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] export(Map<String, Object> model) {
        return objectMapper.writeValueAsBytes(model);
    }
}
