package com.collabcase.teamai.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.collabcase.ai.context.AiAttributeContext;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiRelationshipContext;
import com.collabcase.teamai.assistant.TeamPromptBuilder.PromptInput;
import com.collabcase.teamai.chat.ChatMessage;
import com.collabcase.teamai.chat.ChatMessageKind;
import com.collabcase.teamai.chat.HelpLevel;

class TeamPromptBuilderTests {

    private final TeamPromptBuilder builder = new TeamPromptBuilder();

    private final AiDiagramContext diagram = new AiDiagramContext("d1", "Ventas", "ClassDiagram",
            List.of(new AiClassContext("c1", "Cliente",
                    List.of(new AiAttributeContext("a1", "nombre", "String", "nombre: String")), List.of()),
                    new AiClassContext("c2", "Producto", List.of(), List.of())),
            List.of(new AiRelationshipContext("r1", "c1", "Cliente", "c2", "Producto", "ClassBidirectional",
                    "1", "*", null)));

    private PromptInput input(HelpLevel level, boolean canDraw, boolean summary, List<ChatMessage> history,
            List<String> editingNow) {
        return new PromptInput("Luis", "EDITOR", "@IA agrega una clase Venta", false, summary, level, canDraw,
                history, diagram, List.of("Ana: Producto (último cambio hace 3 min)"), editingNow);
    }

    private static ChatMessage message(ChatMessageKind kind, String author, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setKind(kind);
        m.setAuthorName(author);
        m.setContent(content);
        return m;
    }

    private static String system(List<Map<String, String>> messages) {
        assertThat(messages.getFirst().get("role")).isEqualTo("system");
        return messages.getFirst().get("content");
    }

    private static int totalChars(List<Map<String, String>> messages) {
        return messages.stream().mapToInt(m -> m.get("content").length()).sum();
    }

    @Test
    void systemNamesTheAskerAndForbidsGenericAnswers() {
        var messages = builder.build(input(HelpLevel.DRAW, true, false, List.of(), List.of()));
        String system = system(messages);
        assertThat(system).contains("desarrollador senior del equipo", "Luis", "EDITOR",
                "Nunca des respuestas genéricas", "castellano", "Cliente", "Producto");
        assertThat(messages.getLast()).isEqualTo(Map.of("role", "user", "content", "[Luis]: @IA agrega una clase Venta"));
    }

    @Test
    void historyIsTaggedWithAuthorsAndTrimmedNewestFirst() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            boolean ai = i % 3 == 2;
            history.add(message(ai ? ChatMessageKind.AI : ChatMessageKind.PERSON, ai ? "IA" : "Ana",
                    "mensaje " + i + " " + "x".repeat(400)));
        }
        var messages = builder.build(input(HelpLevel.DRAW, true, false, history, List.of()));
        var middle = messages.subList(1, messages.size() - 1);

        assertThat(totalChars(messages)).isLessThanOrEqualTo(TeamPromptBuilder.MAX_TOTAL_CHARS);
        assertThat(middle).isNotEmpty().hasSizeLessThanOrEqualTo(TeamPromptBuilder.MAX_HISTORY_MESSAGES);
        // the newest message is the last one before the question, and the order is chronological
        assertThat(middle.getLast().get("content")).startsWith("mensaje 59 ");
        int first = 60 - middle.size();
        for (int i = 0; i < middle.size(); i++) {
            int n = first + i;
            boolean ai = n % 3 == 2;
            assertThat(middle.get(i).get("role")).isEqualTo(ai ? "assistant" : "user");
            assertThat(middle.get(i).get("content")).startsWith((ai ? "" : "[Ana]: ") + "mensaje " + n + " ");
        }
    }

    @Test
    void historyKeepsAtMostThirtyMessages() {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 60; i++) history.add(message(ChatMessageKind.PERSON, "Ana", "hola " + i));
        var messages = builder.build(input(HelpLevel.DRAW, true, false, history, List.of()));
        assertThat(messages).hasSize(1 + 30 + 1);
        assertThat(messages.get(1).get("content")).isEqualTo("[Ana]: hola 30");
    }

    @Test
    void explainLevelForbidsChanges() {
        String explain = system(builder.build(input(HelpLevel.EXPLAIN, true, false, List.of(), List.of())));
        String viewer = system(builder.build(input(HelpLevel.DRAW, false, false, List.of(), List.of())));
        String draw = system(builder.build(input(HelpLevel.DRAW, true, false, List.of(), List.of())));
        assertThat(explain).contains("No propongas cambios: `cambios` va vacío");
        assertThat(viewer).contains("No propongas cambios: `cambios` va vacío");
        assertThat(draw).doesNotContain("`cambios` va vacío")
                .contains("completa `cambios`", "Nunca renombres, borres ni muevas");
    }

    @Test
    void summaryUsesSummaryInstruction() {
        String system = system(builder.build(input(HelpLevel.DRAW, true, true, List.of(), List.of())));
        assertThat(system).contains("Resume qué hizo cada integrante y qué falta",
                "Ana: Producto (último cambio hace 3 min)");
    }

    @Test
    void editingNowIsIncluded() {
        String system = system(builder.build(input(HelpLevel.DRAW, true, false, List.of(),
                List.of("Ana está editando Producto"))));
        assertThat(system).contains("Ana está editando Producto", "avísale");
    }

    @Test
    void diagramIsCappedAndMissingDiagramIsSaid() {
        List<AiClassContext> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) many.add(new AiClassContext("c" + i, "ClaseNumero" + i, List.of(), List.of()));
        var big = new AiDiagramContext("d", "Grande", "ClassDiagram", many, List.of());
        var in = new PromptInput("Luis", "EDITOR", "hola", false, false, HelpLevel.DRAW, true, List.of(), big,
                List.of(), List.of());
        assertThat(TeamPromptBuilder.describe(big).length()).isLessThanOrEqualTo(TeamPromptBuilder.MAX_DIAGRAM_CHARS);
        assertThat(totalChars(builder.build(in))).isLessThanOrEqualTo(TeamPromptBuilder.MAX_TOTAL_CHARS);

        var none = new PromptInput("Luis", "EDITOR", "hola", false, false, HelpLevel.DRAW, false, List.of(), null,
                List.of(), List.of());
        assertThat(system(builder.build(none))).contains("no tiene un diagrama abierto");
    }
}
