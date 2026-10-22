package com.collabcase.export;

import java.util.List;

public record ExportResult(String content, List<String> warnings) {
}
