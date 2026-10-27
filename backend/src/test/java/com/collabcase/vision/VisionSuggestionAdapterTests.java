package com.collabcase.vision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.collabcase.ai.AiSuggestionValidator;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.ai.dto.SuggestionType;
import com.collabcase.vision.dto.ClassCandidate;
import com.collabcase.vision.dto.MethodCandidate;
import com.collabcase.vision.dto.VisionExtraction;

class VisionSuggestionAdapterTests {
    private final AiDiagramContext empty = new AiDiagramContext("d", "Pizarra", "ClassDiagram", List.of(), List.of());

    private List<AiSuggestion> methodsSurvivingValidation(MethodCandidate... methods) {
        VisionExtraction extraction = new VisionExtraction("resumen",
                List.of(new ClassCandidate("c1", "Cliente", List.of(), List.of(methods), 0.9)), List.of(), List.of());
        List<AiSuggestion> adapted = new VisionSuggestionAdapter().adapt(extraction, empty);
        return new AiSuggestionValidator().validate(adapted, empty).stream()
                .filter(s -> s.type() == SuggestionType.ADD_METHOD).toList();
    }

    @Test
    void methodSuggestionsCarryNameAndReturnTypeSoTheyPassValidation() {
        List<AiSuggestion> methods = methodsSurvivingValidation(
                new MethodCandidate("comprar()", "void"), new MethodCandidate("total", "double"));

        assertThat(methods).extracting(AiSuggestion::methodName).containsExactly("comprar", "total");
        assertThat(methods).extracting(AiSuggestion::returnType).containsExactly("void", "double");
    }

    @Test
    void missingReturnTypeDefaultsToVoid() {
        assertThat(methodsSurvivingValidation(new MethodCandidate("pagar", null)))
                .extracting(AiSuggestion::returnType).containsExactly("void");
    }
}
