package com.collabcase.importer;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.collabcase.collaboration.CollaborationRoomManager;
import com.collabcase.diagram.DiagramService;
import com.collabcase.diagram.dto.DiagramRequest;
import com.collabcase.diagram.dto.DiagramResponse;

@Service
public class DiagramImportService {
    public enum Mode { NEW, REPLACE }
    private static final long MAX_SIZE = 10 * 1024 * 1024;
    private final XmiDiagramImporter importer;
    private final DiagramService diagrams;
    private final CollaborationRoomManager rooms;

    public DiagramImportService(XmiDiagramImporter importer, DiagramService diagrams, CollaborationRoomManager rooms) {
        this.importer = importer; this.diagrams = diagrams; this.rooms = rooms;
    }

    public DiagramImportResult importXmi(UUID projectId, UUID userId, MultipartFile file, Mode mode, UUID diagramId) {
        return importXmi(projectId, userId, file, mode, diagramId, null);
    }

    public DiagramImportResult importXmi(UUID projectId, UUID userId, MultipartFile file, Mode mode, UUID diagramId, UUID versionId) {
        validate(file);
        if (mode == Mode.REPLACE && diagramId == null) throw new XmiImportException(HttpStatus.BAD_REQUEST, "diagramId es obligatorio para REPLACE.");
        if (mode == Mode.REPLACE && rooms.activeSessionCount(diagramId) > 1) throw new XmiImportException(HttpStatus.CONFLICT,
                "No se puede reemplazar el diagrama mientras otros usuarios estan editandolo.");
        try {
            ImportedDiagram imported = importer.parse(file.getBytes());
            String name = imported.title().isBlank() ? "Diagrama importado" : imported.title();
            DiagramRequest request = new DiagramRequest(name, "ClassDiagram", imported.model());
            DiagramResponse saved = mode == Mode.NEW
                    ? versionId == null ? diagrams.createForProject(projectId, request, userId)
                        : diagrams.createForVersion(projectId, versionId, request, userId)
                    : diagrams.replaceImported(projectId, diagramId, request, userId);
            return new DiagramImportResult(saved.id(), imported.classes(), imported.attributes(), imported.methods(),
                    imported.relationships(), imported.warnings());
        } catch (IOException exception) {
            throw new XmiImportException(HttpStatus.BAD_REQUEST, "No se pudo leer el archivo XMI.", exception);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new XmiImportException(HttpStatus.BAD_REQUEST, "El archivo XMI esta vacio.");
        if (file.getSize() > MAX_SIZE) throw new XmiImportException(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo XMI supera 10 MB.");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xmi") && !filename.endsWith(".xml"))
            throw new XmiImportException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Selecciona un archivo .xmi o .xml.");
    }
}
