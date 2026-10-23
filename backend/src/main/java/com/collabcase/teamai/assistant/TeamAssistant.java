package com.collabcase.teamai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.collabcase.ai.AiDiagramContextMapper;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.diagram.DiagramService;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.project.ProjectAccessService.Access;
import com.collabcase.project.ProjectAccessService.EffectiveRole;
import com.collabcase.teamai.activity.ActivityService;
import com.collabcase.teamai.assistant.TeamPromptBuilder.PromptInput;
import com.collabcase.teamai.chat.ChatMessage;
import com.collabcase.teamai.chat.ChatMessageKind;
import com.collabcase.teamai.chat.ChatMessageStatus;
import com.collabcase.teamai.chat.ChatRoom;
import com.collabcase.teamai.chat.ChatService;
import com.collabcase.teamai.chat.ChatSession;
import com.collabcase.teamai.chat.HelpLevel;
import com.collabcase.teamai.chat.ProposalState;
import com.collabcase.teamai.chat.QuestionHandler;
import com.collabcase.teamai.connector.AiJobListener;
import com.collabcase.teamai.connector.AiJobQueue;
import com.collabcase.teamai.connector.AiUnavailableException;

import tools.jackson.databind.ObjectMapper;

/**
 * The team's AI: answers questions from the chat through the local model, streams the answer, and,
 * depending on the project's help level, proposes or draws the changes it suggests.
 */
@Component
public class TeamAssistant implements QuestionHandler {

    private static final Logger log = LoggerFactory.getLogger(TeamAssistant.class);
    private static final Pattern MENTION = Pattern.compile("(?i)(^|\\s)@ia\\b");
    private static final int HISTORY_FETCH = TeamPromptBuilder.MAX_HISTORY_MESSAGES + 1;
    private static final int MAX_REPORTED_NAMES = 50;
    private static final int MAX_REPORTED_LENGTH = 120;
    private static final String AI_NAME = "IA";
    private static final String UNREADABLE = "No pude armar la respuesta. Prueba de nuevo";
    private static final String NOT_FOUND = "No se encontró esa propuesta";
    private static final String NOT_EDITOR = "Solo un editor puede aplicar cambios";
    private static final String EXPLAIN_ONLY = "El nivel de ayuda actual no permite aplicar cambios";
    private static final String NOT_PENDING = "Esa propuesta ya no está pendiente";
    private static final String OPEN_DIAGRAM = "Abre el diagrama para aplicar el cambio";

    private final AiJobQueue queue;
    private final ChatService chat;
    private final ChatRoom room;
    private final ProjectAccessService access;
    private final DiagramService diagrams;
    private final AiDiagramContextMapper mapper;
    private final ChangeTranslator translator;
    private final ActivityService activity;
    private final TeamPromptBuilder prompts;
    private final ObjectMapper json;

    public TeamAssistant(AiJobQueue queue, ChatService chat, ChatRoom room, ProjectAccessService access,
            DiagramService diagrams, AiDiagramContextMapper mapper, ChangeTranslator translator,
            ActivityService activity, TeamPromptBuilder prompts, ObjectMapper json) {
        this.queue = queue;
        this.chat = chat;
        this.room = room;
        this.access = access;
        this.diagrams = diagrams;
        this.mapper = mapper;
        this.translator = translator;
        this.activity = activity;
        this.prompts = prompts;
        this.json = json;
    }

    @Override
    public void handle(ChatMessage question, ChatSession asker) {
        Access current = currentAccess(asker.projectId(), asker.userId());
        if (current == null) return;
        UUID projectId = question.getProjectId();
        UUID diagramId = question.getDiagramId();
        boolean summary = isSummary(question.getContent());
        HelpLevel level = current.project().effectiveHelpLevel();
        AiDiagramContext ctx = diagramId == null ? null : diagramContext(diagramId, asker.userId());
        boolean canDraw = !summary && current.role() != EffectiveRole.VIEWER && ctx != null;

        List<ChatMessage> history = new ArrayList<>();
        for (ChatMessage m : chat.recentVisibleTo(projectId, asker.userId(), HISTORY_FETCH)) {
            boolean visible = m.getPrivateOwnerId() == null || m.getPrivateOwnerId().equals(asker.userId());
            if (visible && !m.getId().equals(question.getId())) history.add(m);
        }

        ChatMessage ai = new ChatMessage();
        ai.setProjectId(projectId);
        ai.setAuthorName(AI_NAME);
        ai.setKind(ChatMessageKind.AI);
        ai.setPrivateOwnerId(question.getPrivateOwnerId());
        ai.setDiagramId(diagramId);
        ai.setContent("");
        ai.setStatus(ChatMessageStatus.QUEUED);
        ai.setProposalState(ProposalState.NONE);
        ai.setRequestedById(asker.userId());
        ai.setRequestedByName(asker.userName());
        ai = chat.save(ai);
        publish(ai, "message");

        List<Map<String, String>> messages = prompts.build(new PromptInput(asker.userName(), current.role().name(),
                question.getContent(), question.getPrivateOwnerId() != null, summary, level, canDraw, history, ctx,
                safe(() -> activity.summaryLines(projectId)), safe(() -> activity.editingNow(projectId))));
        Job job = new Job(ai, asker, ctx, summary);
        try {
            queue.submit(messages, summary ? TeamAiReply.summarySchema() : TeamAiReply.jsonSchema(), job);
        } catch (AiUnavailableException e) {
            job.failed(e.getMessage());
        }
    }

    @Override
    public void applied(ChatSession who, UUID messageId, List<String> applied, List<Failure> failed) {
        try {
            ChatMessage m = proposalVisibleTo(who, messageId);
            if (m == null) {
                error(who, NOT_FOUND);
                return;
            }
            if (!canEdit(who)) {
                error(who, NOT_EDITOR);
                return;
            }
            List<String> names = cleanNames(applied);
            if (!names.isEmpty()) {
                m.setProposalState(ProposalState.APPLIED);
                ChatMessage notice = notice(m, null, "IA agregó " + String.join(", ", names) + " (pedido de "
                        + (m.getRequestedByName() == null ? "alguien" : m.getRequestedByName()) + ")");
                publish(notice, "message");
                activity.recordAiChange(m.getProjectId(), m.getDiagramId(), m.getRequestedById(),
                        m.getRequestedByName(), names);
            }
            List<Failure> failures = failed == null ? List.of() : failed;
            for (Failure f : failures.stream().limit(MAX_REPORTED_NAMES).toList()) {
                ChatMessage notice = notice(m, who.userId(),
                        "No se pudo aplicar " + clip(f.name()) + ": " + clip(f.reason()));
                publish(notice, "message");
                m.setProposalState(ProposalState.PENDING);
            }
            if (!names.isEmpty() || !failures.isEmpty()) {
                publish(chat.save(m), "message_updated");
            }
        } catch (RuntimeException e) {
            log.warn("Could not record applied AI changes for {}", messageId, e);
            error(who, "No se pudo registrar el cambio");
        }
    }

    @Override
    public void applyProposal(ChatSession who, UUID messageId) {
        ChatMessage m = proposalVisibleTo(who, messageId);
        if (m == null) {
            error(who, NOT_FOUND);
            return;
        }
        Access current = currentAccess(who.projectId(), who.userId());
        if (current == null || current.role() == EffectiveRole.VIEWER) {
            error(who, NOT_EDITOR);
            return;
        }
        if (current.project().effectiveHelpLevel() == HelpLevel.EXPLAIN) {
            error(who, EXPLAIN_ONLY);
            return;
        }
        if (m.getProposalState() != ProposalState.PENDING) {
            error(who, NOT_PENDING);
            return;
        }
        if (who.diagramId() == null || !who.diagramId().equals(m.getDiagramId())) {
            error(who, OPEN_DIAGRAM);
            return;
        }
        List<AiSuggestion> suggestions = chat.toResponse(m).proposal();
        if (suggestions == null || suggestions.isEmpty()) {
            error(who, NOT_FOUND);
            return;
        }
        room.send(who, applyChanges(m, suggestions));
    }

    /** One AI answer in progress. Callbacks come from the connector threads and never throw. */
    private final class Job implements AiJobListener {
        private final ChatSession asker;
        private final AiDiagramContext ctx;
        private final boolean summary;
        private final MessageFieldStreamer streamer = new MessageFieldStreamer();
        private ChatMessage message;
        private boolean done;

        Job(ChatMessage message, ChatSession asker, AiDiagramContext ctx, boolean summary) {
            this.message = message;
            this.asker = asker;
            this.ctx = ctx;
            this.summary = summary;
        }

        @Override
        public synchronized void queued(int position) {
            run("queued", () -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "queue_position");
                payload.put("messageId", message.getId());
                payload.put("position", position);
                room.publish(message, payload);
            });
        }

        @Override
        public synchronized void started() {
            run("started", () -> {
                if (done) return;
                message.setStatus(ChatMessageStatus.WRITING);
                message = chat.save(message);
                publish(message, "message_updated");
            });
        }

        @Override
        public synchronized void chunk(String text) {
            run("chunk", () -> {
                if (done) return;
                String fresh = streamer.feed(text);
                if (fresh.isEmpty()) return;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "chunk");
                payload.put("messageId", message.getId());
                payload.put("text", fresh);
                room.publish(message, payload);
            });
        }

        @Override
        public synchronized void finished(String content) {
            run("finished", () -> {
                if (done) return;
                done = true;
                TeamAiReply reply = parse(content);
                if (reply == null) {
                    message.setStatus(ChatMessageStatus.FAILED);
                    message.setContent(streamer.text().isBlank() ? UNREADABLE : streamer.text());
                    message = chat.save(message);
                    publish(message, "message_updated");
                    return;
                }
                message.setContent(reply.mensaje());
                message.setStatus(ChatMessageStatus.COMPLETE);
                HelpLevel level = summary ? null : drawingLevel();
                List<AiSuggestion> changes = level == null ? List.of()
                        : translator.translate(reply.cambios(), ctx, asker.userName());
                if (!changes.isEmpty()) {
                    message.setProposalJson(json.writeValueAsString(changes));
                    message.setProposalState(level == HelpLevel.SUGGEST ? ProposalState.PENDING : ProposalState.NONE);
                    message = chat.save(message);
                    if (level == HelpLevel.DRAW && !room.toUserInDiagram(message.getProjectId(), asker.userId(),
                            message.getDiagramId(), applyChanges(message, changes))) {
                        message.setProposalState(ProposalState.PENDING);
                    }
                }
                message = chat.save(message);
                publish(message, "message_updated");
            });
        }

        @Override
        public synchronized void failed(String reason) {
            run("failed", () -> {
                if (done) return;
                done = true;
                message.setStatus(ChatMessageStatus.FAILED);
                message.setContent(streamer.text().isBlank() ? reason : streamer.text());
                message = chat.save(message);
                publish(message, "message_updated");
            });
        }

        /** The level that applies now, or null when this answer must not change the diagram. */
        private HelpLevel drawingLevel() {
            if (ctx == null || message.getDiagramId() == null) return null;
            Access current = currentAccess(message.getProjectId(), asker.userId());
            if (current == null || current.role() == EffectiveRole.VIEWER) return null;
            HelpLevel level = current.project().effectiveHelpLevel();
            return level == HelpLevel.EXPLAIN ? null : level;
        }

        private void run(String step, Runnable action) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("AI answer {} failed at {}", message.getId(), step, e);
            }
        }
    }

    private TeamAiReply parse(String content) {
        if (content == null || content.isBlank()) return null;
        try {
            TeamAiReply reply = json.readValue(content, TeamAiReply.class);
            return reply == null || reply.mensaje() == null || reply.mensaje().isBlank() ? null : reply;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The AI message with a proposal, in the session's project and visible to its user; else null. */
    private ChatMessage proposalVisibleTo(ChatSession who, UUID messageId) {
        ChatMessage m = chat.find(messageId).orElse(null);
        if (m == null || m.getKind() != ChatMessageKind.AI || !who.projectId().equals(m.getProjectId())) return null;
        if (m.getPrivateOwnerId() != null && !m.getPrivateOwnerId().equals(who.userId())) return null;
        if (m.getProposalJson() == null || m.getProposalJson().isBlank()) return null;
        return m;
    }

    private boolean canEdit(ChatSession who) {
        Access current = currentAccess(who.projectId(), who.userId());
        return current != null && current.role() != EffectiveRole.VIEWER;
    }

    private Access currentAccess(UUID projectId, UUID userId) {
        try {
            return access.requireRead(projectId, userId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private AiDiagramContext diagramContext(UUID diagramId, UUID userId) {
        try {
            Map<String, Object> model = diagrams.findModelForAnalysis(diagramId, userId);
            return model == null ? null : mapper.map(model);
        } catch (RuntimeException e) {
            log.warn("Could not read diagram {} for the AI", diagramId, e);
            return null;
        }
    }

    private ChatMessage notice(ChatMessage proposal, UUID privateOwner, String content) {
        ChatMessage n = new ChatMessage();
        n.setProjectId(proposal.getProjectId());
        n.setAuthorName(AI_NAME);
        n.setKind(ChatMessageKind.NOTICE);
        n.setPrivateOwnerId(privateOwner);
        n.setDiagramId(proposal.getDiagramId());
        n.setContent(content);
        n.setStatus(ChatMessageStatus.COMPLETE);
        n.setProposalState(ProposalState.NONE);
        n.setRequestedById(proposal.getRequestedById());
        n.setRequestedByName(proposal.getRequestedByName());
        return chat.save(n);
    }

    private Map<String, Object> applyChanges(ChatMessage m, List<AiSuggestion> suggestions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "apply_changes");
        payload.put("messageId", m.getId());
        payload.put("diagramId", m.getDiagramId());
        payload.put("suggestions", suggestions);
        return payload;
    }

    private void publish(ChatMessage m, String kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("message", chat.toResponse(m));
        room.publish(m, payload);
    }

    private void error(ChatSession who, String message) {
        room.send(who, Map.of("kind", "error", "message", message));
    }

    static boolean isSummary(String text) {
        if (text == null) return false;
        String rest = MENTION.matcher(text).replaceAll(" ").replaceAll("[\\p{Punct}¿¡\\s]+", " ").trim();
        return rest.toLowerCase(Locale.ROOT).equals("resumen");
    }

    private static List<String> cleanNames(List<String> names) {
        if (names == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank() && out.size() < MAX_REPORTED_NAMES) out.add(clip(name));
        }
        return new ArrayList<>(out);
    }

    private static String clip(String value) {
        String v = value == null ? "" : value.trim();
        return v.length() <= MAX_REPORTED_LENGTH ? v : v.substring(0, MAX_REPORTED_LENGTH) + "…";
    }

    private static List<String> safe(java.util.function.Supplier<List<String>> source) {
        try {
            List<String> lines = source.get();
            return lines == null ? List.of() : lines;
        } catch (RuntimeException e) {
            log.warn("Could not read the team activity for the AI", e);
            return List.of();
        }
    }
}
