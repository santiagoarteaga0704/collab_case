package com.collabcase.codegen;

import java.util.List;

public record GenerationValidation(boolean valid, int entities, int relationships,
        List<String> errors, List<String> warnings) {}
