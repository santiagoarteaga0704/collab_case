package com.collabcase.export;

import java.util.List;

public record ExportPreviewResponse(String content, List<String> warnings) {
}
