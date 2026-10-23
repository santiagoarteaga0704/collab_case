package com.collabcase.teamai.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
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
    /** A drawing the asker's browser has not reported after this long gets the Aplicar button. */
    static final Duration REPORT_WAIT = Duration.ofSeconds(30);
    /** Untracked drawings (lost by a restart) older than this are released too; longer than any job. */
    static final Duration RESTART_GRACE = Duration.ofMinutes(15);
    private static final String AI_NAME = "IA";
    private static final String LOST_ON_RESTART = "La IA no respondió a tiempo";
    private static final String UNREADABLE = "No pude armar la respuesta. Prueba de nuevo";
    private static final String GENERIC_FAILURE = "La IA no pudo responder. Prueba de nuevo";
    private static final String NOT_FOUND = "No se encontró esa propuesta";
    private static final String NOT_EDITOR = "Solo un editor puede aplicar cambios";
    private static final String NOT_YOURS = "Esa propuesta no se te envió para aplicar";
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
    private final Clock clock;

    /** Guards every change of a proposal's state, so reports, sweeps and answers never overwrite each other. */
    private final Object proposals = new Object();
    /** Users that received apply_changes for a proposal; only they may report it applied. */
    private final Map<UUID, Set<UUID>> sentTo = new ConcurrentHashMap<>();
    /** Drawings sent to the asker and not yet reported, with the moment they were sent. */
    private final Map<UUID, Instant> drawnAt = new ConcurrentHashMap<>();

    @Autowired
    public TeamAssistant(AiJobQueue queue, ChatService chat, ChatRoom room, ProjectAccessService access,
            DiagramService diagrams, AiDiagramContextMapper mapper, ChangeTranslator translator,
            ActivityService activity, TeamPromptBuilder prompts, ObjectMapper json) {
        this(queue, chat, room, access, diagrams, mapper, translator, activity, prompts, json, Clock.systemUTC());
    }

    public TeamAssistant(AiJobQueue queue, ChatService chat, ChatRoom room, ProjectAccessService access,
            DiagramService diagrams, AiDiagramContextMapper mapper, ChangeTranslator translator,
            ActivityService activity, TeamPromptBuilder prompts, ObjectMapper json, Clock clock) {
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
        this.clock = clock;
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
            boolean failedAnswer = m.getKind() == ChatMessageKind.AI && m.getStatus() == ChatMessageStatus.FAILED;
            if (visible && !failedAnswer && !m.getId().equals(question.getId())) history.add(m);
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

        // from here on the AI message exists: any failure must end it as FAILED, never leave it QUEUED
        Job job = new Job(ai, asker, ctx, summary);
        try {
            List<Map<String, String>> messages = prompts.build(new PromptInput(asker.userName(),
                    current.role().name(), question.getContent(), question.getPrivateOwnerId() != null, summary,
                    level, canDraw, history, ctx, safe(() -> activity.summaryLines(projectId)),
                    safe(() -> activity.editingNow(projectId))));
            queue.submit(messages, summary ? TeamAiReply.summarySchema() : TeamAiReply.jsonSchema(), job);
        } catch (AiUnavailableException e) {
            job.failed(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Could not send question {} to the AI", question.getId(), e);
            job.failed(GENERIC_FAILURE);
        }
    }

    @Override
    public void applied(ChatSession who, UUID messageId, List<String> applied, List<Failure> failed) {
        try {
            synchronized (proposals) {
                ChatMessage m = proposalVisibleTo(who, messageId);
                if (m == null) {
                    error(who, NOT_FOUND);
                    return;
                }
                if (m.getProposalState() == ProposalState.APPLIED) return; // already reported: idempotent
                if (!canEdit(who)) {
                    error(who, NOT_EDITOR);
                    return;
                }
                boolean receivedIt = sentTo.getOrDefault(m.getId(), Set.of()).contains(who.userId())
                        || (m.getProposalState() == ProposalState.NONE && who.userId().equals(m.getRequestedById()));
                if (!receivedIt) {
                    error(who, NOT_YOURS);
                    return;
                }
                Map<String, String> known = proposalNames(m);
                List<String> names = matching(applied, known);
                List<Failure> failures = new ArrayList<>();
                for (Failure f : failed == null ? List.<Failure>of() : failed) {
                    String name = f == null ? null : known.get(key(f.name()));
                    if (name != null && failures.size() < MAX_REPORTED_NAMES) {
                        failures.add(new Failure(name, clip(f.reason())));
                    }
                }
                if (names.isEmpty() && failures.isEmpty()) return;

                drawnAt.remove(m.getId());
                if (!names.isEmpty()) {
                    m.setProposalState(ProposalState.APPLIED);
                    ChatMessage notice = notice(m, null, "IA agregó " + String.join(", ", names) + " (pedido de "
                            + (m.getRequestedByName() == null ? "alguien" : m.getRequestedByName()) + ")");
                    publish(notice, "message");
                    activity.recordAiChange(m.getProjectId(), m.getDiagramId(), m.getRequestedById(),
                            m.getRequestedByName(), names);
                }
                for (Failure f : failures) {
                    publish(notice(m, who.userId(), "No se pudo aplicar " + f.name() + ": " + f.reason()), "message");
                    m.setProposalState(ProposalState.PENDING);
                }
                if (m.getProposalState() == ProposalState.APPLIED) sentTo.remove(m.getId());
                publish(chat.save(m), "message_updated");
            }
        } catch (RuntimeException e) {
            log.warn("Could not record applied AI changes for {}", messageId, e);
            error(who, "No se pudo registrar el cambio");
        }
    }

    @Override
    public void applyProposal(ChatSession who, UUID messageId) {
        synchronized (proposals) {
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
            sentTo.computeIfAbsent(m.getId(), k -> ConcurrentHashMap.newKeySet()).add(who.userId());
            room.send(who, applyChanges(m, suggestions));
        }
    }

    /**
     * A drawing whose browser never reported back (the asker left, the tab closed) becomes a
     * pending proposal with the Aplicar button, as the design asks.
     */
    @Scheduled(fixedDelay = 10_000)
    public void releaseUnreportedDrawings() {
        try {
            Instant now = clock.instant();
            Set<UUID> due = new LinkedHashSet<>();
            drawnAt.forEach((id, at) -> {
                if (!at.plus(REPORT_WAIT).isAfter(now)) due.add(id);
            });
            for (ChatMessage m : chat.unreportedDrawings(now.minus(RESTART_GRACE))) {
                if (!drawnAt.containsKey(m.getId())) due.add(m.getId());
            }
            for (UUID id : due) {
                release(id);
            }
        } catch (RuntimeException e) {
            log.warn("Could not release unreported AI drawings", e);
        }
    }

    /**
     * The job queue lives in memory and a single instance runs: after a restart no answer that was
     * QUEUED or WRITING will ever finish, so it ends as FAILED instead of spinning forever.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failAnswersLostOnRestart() {
        try {
            for (ChatMessage m : chat.unfinished()) {
                try {
                    m.setStatus(ChatMessageStatus.FAILED);
                    m.setContent(LOST_ON_RESTART);
                    chat.save(m);
                } catch (RuntimeException e) {
                    log.warn("Could not mark AI answer {} as failed after a restart", m.getId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not sweep AI answers left unfinished by a restart", e);
        }
    }

    private void release(UUID id) {
        try {
            synchronized (proposals) {
                drawnAt.remove(id);
                ChatMessage m = chat.find(id).orElse(null);
                if (m == null || m.getProposalState() != ProposalState.NONE || m.getProposalJson() == null) return;
                m.setProposalState(ProposalState.PENDING);
                publish(chat.save(m), "message_updated");
            }
        } catch (RuntimeException e) {
            log.warn("Could not release AI drawing {}", id, e);
        }
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
                try {
                    complete(content);
                } catch (RuntimeException e) {
                    log.warn("AI answer {} could not be completed", message.getId(), e);
                    markFailed(streamer.text().isBlank() ? GENERIC_FAILURE : streamer.text());
                }
            });
        }

        @Override
        public synchronized void failed(String reason) {
            run("failed", () -> {
                if (done) return;
                done = true;
                String text = reason == null || reason.isBlank() ? GENERIC_FAILURE : reason;
                markFailed(streamer.text().isBlank() ? text : streamer.text());
            });
        }

        private void complete(String content) {
            TeamAiReply reply = parse(content);
            if (reply == null) {
                markFailed(streamer.text().isBlank() ? UNREADABLE : streamer.text());
                return;
            }
            message.setContent(reply.mensaje());
            message.setStatus(ChatMessageStatus.COMPLETE);
            HelpLevel level = summary ? null : drawingLevel();
            List<AiSuggestion> changes = level == null ? List.of()
                    : translator.translate(reply.propuesta(), ctx, asker.userName());
            if (changes.isEmpty()) {
                message = chat.save(message);
                publish(message, "message_updated");
                return;
            }
            message.setProposalJson(json.writeValueAsString(changes));
            if (level == HelpLevel.SUGGEST) {
                message.setProposalState(ProposalState.PENDING);
                message = chat.save(message);
                publish(message, "message_updated");
                return;
            }
            synchronized (proposals) {
                // DRAW: saved and shown before apply_changes goes out, so a quick report finds the
                // proposal and the clients never see an older state after it
                message.setProposalState(ProposalState.NONE);
                message = chat.save(message);
                publish(message, "message_updated");
                UUID id = message.getId();
                sentTo.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(asker.userId());
                drawnAt.put(id, clock.instant());
                if (room.toUserInDiagram(message.getProjectId(), asker.userId(), message.getDiagramId(),
                        applyChanges(message, changes))) {
                    return;
                }
                drawnAt.remove(id);
                sentTo.remove(id);
                message.setProposalState(ProposalState.PENDING);
                message = chat.save(message);
                publish(message, "message_updated");
            }
        }

        /** Saves the message as FAILED, retrying the save once; publishing is best effort. */
        private void markFailed(String text) {
            message.setStatus(ChatMessageStatus.FAILED);
            message.setContent(text);
            try {
                message = chat.save(message);
            } catch (RuntimeException first) {
                log.warn("Retrying to save failed AI answer {}", message.getId(), first);
                message = chat.save(message);
            }
            publish(message, "message_updated");
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

    /**
     * Names a browser may report for this proposal, by lower-case key: each suggestion's class
     * name, attribute name and description (the description of a relationship is "Origen - Destino").
     */
    private Map<String, String> proposalNames(ChatMessage m) {
        Map<String, String> names = new LinkedHashMap<>();
        List<AiSuggestion> suggestions = chat.toResponse(m).proposal();
        for (AiSuggestion s : suggestions == null ? List.<AiSuggestion>of() : suggestions) {
            for (String name : new String[] { s.className(), s.attributeName(), s.description() }) {
                if (name != null && !name.isBlank()) names.putIfAbsent(key(name), name.trim());
            }
        }
        return names;
    }

    private static List<String> matching(List<String> reported, Map<String, String> known) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : reported == null ? List.<String>of() : reported) {
            String canonical = name == null ? null : known.get(key(name));
            if (canonical != null && out.size() < MAX_REPORTED_NAMES) out.add(canonical);
        }
        return new ArrayList<>(out);
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
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

    private static String clip(String value) {
        String v = value == null ? "" : value.trim();
        return v.length() <= MAX_REPORTED_LENGTH ? v : v.substring(0, MAX_REPORTED_LENGTH) + "…";
    }

    private static List<String> safe(Supplier<List<String>> source) {
        try {
            List<String> lines = source.get();
            return lines == null ? List.of() : lines;
        } catch (RuntimeException e) {
            log.warn("Could not read the team activity for the AI", e);
            return List.of();
        }
    }
}
