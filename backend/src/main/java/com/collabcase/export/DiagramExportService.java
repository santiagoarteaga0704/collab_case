package com.collabcase.export;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.collabcase.ai.AiDiagramContextMapper;
import com.collabcase.diagram.DiagramService;
import com.collabcase.diagram.dto.DiagramResponse;

@Service
public class DiagramExportService {
    public record Download(byte[] content, String filename) {}

    private final DiagramService diagramService;
    private final AiDiagramContextMapper contextMapper;
    private final JsonDiagramExporter jsonExporter;
    private final PlantUmlDiagramExporter plantUmlExporter;
    private final SqlDiagramExporter sqlExporter;
    private final XmiDiagramExporter xmiExporter;

    public DiagramExportService(DiagramService diagramService, AiDiagramContextMapper contextMapper,
            JsonDiagramExporter jsonExporter, PlantUmlDiagramExporter plantUmlExporter,
            SqlDiagramExporter sqlExporter, XmiDiagramExporter xmiExporter) {
        this.diagramService = diagramService;
        this.contextMapper = contextMapper;
        this.jsonExporter = jsonExporter;
        this.plantUmlExporter = plantUmlExporter;
        this.sqlExporter = sqlExporter;
        this.xmiExporter = xmiExporter;
    }

    public Download json(UUID id, UUID userId) {
        DiagramResponse diagram = diagramService.findById(id, userId);
        return new Download(jsonExporter.export(diagram.model()), filename(diagram.name(), "json"));
    }

    public Download plantUml(UUID id, UUID userId) {
        DiagramResponse diagram = diagramService.findById(id, userId);
        return new Download(plantUmlExporter.export(contextMapper.map(diagram.model())).content().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                filename(diagram.name(), "puml"));
    }

    public ExportPreviewResponse plantUmlPreview(UUID id, UUID userId) {
        DiagramResponse diagram = diagramService.findById(id, userId);
        ExportResult result = plantUmlExporter.export(contextMapper.map(diagram.model()));
        return new ExportPreviewResponse(result.content(), result.warnings());
    }

    public Download sql(UUID id, UUID userId) {
        DiagramResponse diagram = diagramService.findById(id, userId);
        return new Download(sqlExporter.export(contextMapper.map(diagram.model())).content().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                filename(diagram.name(), "sql"));
    }

    public ExportPreviewResponse sqlPreview(UUID id, UUID userId) {
        DiagramResponse diagram = diagramService.findById(id, userId);
        ExportResult result = sqlExporter.export(contextMapper.map(diagram.model()));
        return new ExportPreviewResponse(result.content(), result.warnings());
    }

    public Download xmi(UUID id, UUID userId) {
        DiagramResponse diagram = diagramService.findById(id, userId);
        return new Download(xmiExporter.export(contextMapper.map(diagram.model()), diagram.model()), filename(diagram.name(), "xmi"));
    }

    private String filename(String name, String extension) {
        String normalized = Normalizer.normalize(name == null ? "diagrama" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return (normalized.isBlank() ? "diagrama" : normalized) + "." + extension;
    }
}
