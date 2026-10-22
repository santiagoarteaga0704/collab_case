package com.collabcase.importer;

import java.util.List;
import java.util.UUID;

public record DiagramImportResult(UUID diagramId, int classesImported, int attributesImported,
        int methodsImported, int relationshipsImported, List<String> warnings) {
}
