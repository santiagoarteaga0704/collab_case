package com.collabcase.teamai.assistant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.collabcase.ai.context.AiAttributeContext;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiMethodContext;
import com.collabcase.ai.context.AiRelationshipContext;
import com.collabcase.teamai.chat.ChatMessage;
import com.collabcase.teamai.chat.ChatMessageKind;
import com.collabcase.teamai.chat.HelpLevel;

/**
 * Builds the messages sent to the local model for one team question. The budget is counted in
 * characters (about 4 per token, 3072 input tokens): the diagram and the activity are capped, and
 * the chat history fills what is left, newest first.
 */
@Component
public class TeamPromptBuilder {

    static final int MAX_TOTAL_CHARS = 12_000;
    static final int MAX_DIAGRAM_CHARS = 4_000;
    static final int MAX_ACTIVITY_CHARS = 1_500;
    static final int MAX_HISTORY_MESSAGES = 30;
    private static final String CUT = "\n…";

    public record PromptInput(String askerName, String askerRole, String question, boolean isPrivate,
            boolean summary, HelpLevel level, boolean canDraw, List<ChatMessage> history, AiDiagramContext diagram,
            List<String> activity, List<String> editingNow) {
    }

    public List<Map<String, String>> build(PromptInput in) {
        String system = system(in);
        String question = tag(in.askerName(), in.question());
        int left = MAX_TOTAL_CHARS - system.length() - question.length();

        Deque<Map<String, String>> history = new ArrayDeque<>();
        List<ChatMessage> source = in.history() == null ? List.of() : in.history();
        for (int i = source.size() - 1; i >= 0 && history.size() < MAX_HISTORY_MESSAGES; i--) {
            Map<String, String> m = historyMessage(source.get(i));
            if (m == null) continue;
            int size = m.get("content").length();
            if (size > left) break;
            left -= size;
            history.addFirst(m);
        }

        List<Map<String, String>> out = new ArrayList<>();
        out.add(Map.of("role", "system", "content", system));
        out.addAll(history);
        out.add(Map.of("role", "user", "content", question));
        return out;
    }

    private static Map<String, String> historyMessage(ChatMessage m) {
        if (m.getContent() == null || m.getContent().isBlank()) return null;
        if (m.getKind() == ChatMessageKind.AI) return Map.of("role", "assistant", "content", m.getContent());
        String author = m.getKind() == ChatMessageKind.NOTICE ? "Aviso" : m.getAuthorName();
        return Map.of("role", "user", "content", tag(author, m.getContent()));
    }

    private static String tag(String author, String text) {
        return "[" + (author == null || author.isBlank() ? "Alguien" : author) + "]: " + (text == null ? "" : text);
    }

    private static String system(PromptInput in) {
        String name = in.askerName() == null || in.askerName().isBlank() ? "Alguien" : in.askerName();
        StringBuilder s = new StringBuilder();
        s.append("Eres el desarrollador senior del equipo en un chat de grupo de un proyecto de diagramas UML. ")
                .append("Cada mensaje del chat empieza con el nombre de quien lo escribe entre corchetes. ")
                .append("Te escribe ").append(name).append(" (rol en el proyecto: ").append(in.askerRole())
                .append("). Responde SIEMPRE dirigiéndote a ").append(name)
                .append(" por su nombre y en relación con lo que esa persona está haciendo. ")
                .append("Nunca des respuestas genéricas: usa el diagrama, el chat y la actividad del equipo. ")
                .append("Responde en castellano, breve y concreto. Devuelve JSON con el texto en `mensaje`.\n");
        if (in.isPrivate()) {
            s.append("Esta conversación es privada entre ").append(name).append(" y tú.\n");
        }

        if (in.summary()) {
            s.append("Resume qué hizo cada integrante y qué falta según lo que dijeron en el chat y el diagrama. ")
                    .append("Nombra a cada integrante.\n");
        } else if (in.level() == HelpLevel.EXPLAIN || !in.canDraw()) {
            s.append("No propongas cambios: `cambios` va vacío. Explica y enseña, la persona hace los cambios.\n");
        } else {
            s.append("Si te piden construir o corregir algo del diagrama, completa `cambios` con lo necesario. ")
                    .append("Nunca renombres, borres ni muevas: si te lo piden, explica que lo haga una persona ")
                    .append("y deja `cambios` vacío. Si no te piden cambios, deja `cambios` vacío.\n");
        }

        List<String> editing = in.editingNow() == null ? List.of() : in.editingNow();
        if (!editing.isEmpty()) {
            s.append("\nQuién está editando ahora (si te piden algo que otra persona está editando, avísale a ")
                    .append(name).append("):\n");
            editing.forEach(line -> s.append("- ").append(line).append('\n'));
        }

        List<String> activity = in.activity() == null ? List.of() : in.activity();
        if (!activity.isEmpty()) {
            String lines = activity.stream().map(l -> "- " + l).collect(Collectors.joining("\n"));
            s.append("\nActividad reciente del equipo:\n").append(cap(lines, MAX_ACTIVITY_CHARS)).append('\n');
        }

        if (in.diagram() == null) {
            s.append("\n").append(name).append(" no tiene un diagrama abierto: conversa, pero no dibujes.\n");
        } else {
            s.append("\nDiagrama que tiene abierto ").append(name).append(":\n").append(describe(in.diagram()));
        }
        return s.toString();
    }

    /** Compact description of the diagram, at most {@link #MAX_DIAGRAM_CHARS} characters. */
    static String describe(AiDiagramContext d) {
        StringBuilder s = new StringBuilder();
        s.append("Título: ").append(d.title() == null ? "(sin título)" : d.title()).append('\n');
        List<AiClassContext> classes = d.classes() == null ? List.of() : d.classes();
        if (classes.isEmpty()) s.append("Sin clases todavía.\n");
        for (AiClassContext c : classes) {
            s.append("Clase ").append(c.name());
            List<AiAttributeContext> attrs = c.attributes() == null ? List.of() : c.attributes();
            if (!attrs.isEmpty()) {
                s.append(" | atributos: ").append(attrs.stream()
                        .map(a -> a.type() == null || a.type().isBlank() ? a.name() : a.name() + ": " + a.type())
                        .collect(Collectors.joining(", ")));
            }
            List<AiMethodContext> methods = c.methods() == null ? List.of() : c.methods();
            if (!methods.isEmpty()) {
                s.append(" | métodos: ").append(methods.stream()
                        .map(m -> m.name() + "()" + (m.returnType() == null || m.returnType().isBlank()
                                ? "" : ": " + m.returnType()))
                        .collect(Collectors.joining(", ")));
            }
            s.append('\n');
        }
        List<AiRelationshipContext> relations = d.relationships() == null ? List.of() : d.relationships();
        for (AiRelationshipContext r : relations) {
            s.append("Relación ").append(r.sourceClass()).append(" [").append(orBlank(r.sourceCardinality()))
                    .append("] -> ").append(r.targetClass()).append(" [").append(orBlank(r.targetCardinality()))
                    .append("] ").append(orBlank(r.relationshipType())).append('\n');
        }
        return cap(s.toString(), MAX_DIAGRAM_CHARS);
    }

    private static String orBlank(String value) {
        return value == null ? "" : value;
    }

    private static String cap(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max - CUT.length()) + CUT;
    }
}
