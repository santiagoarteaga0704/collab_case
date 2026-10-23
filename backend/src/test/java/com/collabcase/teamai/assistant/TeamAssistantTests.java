package com.collabcase.teamai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.collabcase.ai.AiDiagramContextMapper;
import com.collabcase.ai.AiSuggestionValidator;
import com.collabcase.diagram.DiagramRepository;
import com.collabcase.diagram.DiagramService;
import com.collabcase.project.Project;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.project.ProjectAccessService.Access;
import com.collabcase.project.ProjectAccessService.EffectiveRole;
import com.collabcase.project.ProjectRepository;
import com.collabcase.teamai.activity.ActivityService;
import com.collabcase.teamai.chat.ChatMessage;
import com.collabcase.teamai.chat.ChatMessageKind;
import com.collabcase.teamai.chat.ChatMessageRepository;
import com.collabcase.teamai.chat.ChatMessageStatus;
import com.collabcase.teamai.chat.ChatRoom;
import com.collabcase.teamai.chat.ChatService;
import com.collabcase.teamai.chat.ChatSession;
import com.collabcase.teamai.chat.HelpLevel;
import com.collabcase.teamai.chat.ProposalState;
import com.collabcase.teamai.chat.QuestionHandler.Failure;
import com.collabcase.teamai.chat.dto.ChatDtos.MessageResponse;
import com.collabcase.teamai.connector.AiJobListener;
import com.collabcase.teamai.connector.AiJobQueue;
import com.collabcase.teamai.connector.AiUnavailableException;
import com.collabcase.user.UserRepository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TeamAssistantTests {

    private static final String DRAW_REPLY = "{\"mensaje\":\"Luis, agregué Venta y DetalleVenta\",\"propuesta\":{"
            + "\"clasesNuevas\":[{\"nombre\":\"Venta\",\"atributos\":[{\"nombre\":\"total\",\"tipo\":\"Double\"}],"
            + "\"metodos\":[]},{\"nombre\":\"DetalleVenta\",\"atributos\":[],\"metodos\":[]}],"
            + "\"atributosNuevos\":[],\"relacionesNuevas\":[],\"cambiosRelacion\":[]}}";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final UUID projectId = UUID.randomUUID();
    private final UUID diagramId = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();
    private final Map<UUID, ChatMessage> store = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger failingSaves = new AtomicInteger();
    private final MutableClock time = new MutableClock(Instant.parse("2026-10-23T12:00:00Z"));
    private DiagramService diagrams;
    private ProjectAccessService access;

    /** A clock the tests move by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private AiJobQueue queue;
    private ChatRoom room;
    private ActivityService activity;
    private ChatService chat;
    private Project project;
    private TeamAssistant assistant;

    private final List<AiJobListener> listeners = new ArrayList<>();
    private final List<List<Map<String, String>>> prompts = new ArrayList<>();
    private final List<Map<String, Object>> formats = new ArrayList<>();
    private final Map<UUID, EffectiveRole> roles = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        ChatMessageRepository repo = mock(ChatMessageRepository.class);
        when(repo.save(any(ChatMessage.class))).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            if (failingSaves.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IllegalStateException("db");
            if (m.getId() == null) m.setId(UUID.randomUUID());
            if (m.getCreatedAt() == null) m.setCreatedAt(time.instant().plusNanos(sequence.incrementAndGet()));
            store.put(m.getId(), copy(m));
            return copy(m);
        });
        when(repo.findById(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0)))
                .map(TeamAssistantTests::copy));
        when(repo.visibleTo(any(UUID.class), any(UUID.class), any(Pageable.class))).thenAnswer(i -> {
            UUID p = i.getArgument(0);
            UUID u = i.getArgument(1);
            Pageable page = i.getArgument(2);
            return store.values().stream()
                    .filter(m -> m.getProjectId().equals(p))
                    .filter(m -> m.getPrivateOwnerId() == null || m.getPrivateOwnerId().equals(u))
                    .sorted(Comparator.comparing(ChatMessage::getCreatedAt).reversed())
                    .limit(page.getPageSize()).map(TeamAssistantTests::copy).toList();
        });
        when(repo.findByKindAndProposalStateAndProposalJsonIsNotNullAndCreatedAtBefore(any(), any(), any()))
                .thenAnswer(i -> store.values().stream()
                        .filter(m -> m.getKind() == i.getArgument(0) && m.getProposalState() == i.getArgument(1))
                        .filter(m -> m.getProposalJson() != null)
                        .filter(m -> m.getCreatedAt().isBefore(i.getArgument(2)))
                        .map(TeamAssistantTests::copy).toList());

        project = new Project();
        project.setId(projectId);
        project.setHelpLevel(HelpLevel.DRAW);
        access = mock(ProjectAccessService.class);
        roles.put(luis, EffectiveRole.EDITOR);
        roles.put(ana, EffectiveRole.EDITOR);
        when(access.requireRead(eq(projectId), any(UUID.class))).thenAnswer(i -> {
            EffectiveRole role = roles.get(i.getArgument(1));
            if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
            return new Access(project, role);
        });

        chat = new ChatService(repo, access, mock(DiagramRepository.class), mock(ProjectRepository.class),
                mock(UserRepository.class), json);

        queue = mock(AiJobQueue.class);
        when(queue.submit(any(), any(), any())).thenAnswer(i -> {
            prompts.add(i.getArgument(0));
            formats.add(i.getArgument(1));
            listeners.add(i.getArgument(2));
            return UUID.randomUUID();
        });

        room = mock(ChatRoom.class);
        diagrams = mock(DiagramService.class);
        when(diagrams.findModelForAnalysis(eq(diagramId), any(UUID.class))).thenReturn(Map.of(
                "id", diagramId.toString(), "title", "Ventas", "type", "ClassDiagram",
                "nodes", List.of(Map.of("id", "c1", "type", "class", "data", Map.of("name", "Cliente"))),
                "edges", List.of()));

        activity = mock(ActivityService.class);
        when(activity.summaryLines(projectId)).thenReturn(List.of("Ana: Producto (último cambio hace 3 min)"));
        when(activity.editingNow(projectId)).thenReturn(List.of("Ana está editando Producto"));

        assistant = assistantWith(new ChangeTranslator(new AiSuggestionValidator()), new TeamPromptBuilder());
    }

    private TeamAssistant assistantWith(ChangeTranslator translator, TeamPromptBuilder prompts) {
        return new TeamAssistant(queue, chat, room, access, diagrams, new AiDiagramContextMapper(), translator,
                activity, prompts, json, time);
    }

    private static ChatMessage copy(ChatMessage m) {
        ChatMessage c = new ChatMessage();
        c.setId(m.getId());
        c.setProjectId(m.getProjectId());
        c.setAuthorId(m.getAuthorId());
        c.setAuthorName(m.getAuthorName());
        c.setKind(m.getKind());
        c.setPrivateOwnerId(m.getPrivateOwnerId());
        c.setDiagramId(m.getDiagramId());
        c.setContent(m.getContent());
        c.setStatus(m.getStatus());
        c.setProposalJson(m.getProposalJson());
        c.setProposalState(m.getProposalState());
        c.setRequestedById(m.getRequestedById());
        c.setRequestedByName(m.getRequestedByName());
        c.setCreatedAt(m.getCreatedAt());
        return c;
    }

    private ChatSession session(UUID user, String name, UUID diagram, EffectiveRole role) {
        return new ChatSession(UUID.randomUUID().toString(), projectId, user, name, diagram, role, null);
    }

    private ChatSession luisInDiagram() {
        return session(luis, "Luis", diagramId, EffectiveRole.EDITOR);
    }

    private ChatMessage question(UUID author, String name, String text, boolean isPrivate, UUID diagram) {
        ChatMessage m = new ChatMessage();
        m.setProjectId(projectId);
        m.setAuthorId(author);
        m.setAuthorName(name);
        m.setKind(ChatMessageKind.PERSON);
        m.setPrivateOwnerId(isPrivate ? author : null);
        m.setDiagramId(diagram);
        m.setContent(text);
        return chat.save(m);
    }

    private ChatMessage aiMessage() {
        return store.values().stream().filter(m -> m.getKind() == ChatMessageKind.AI)
                .max(Comparator.comparing(ChatMessage::getCreatedAt)).orElseThrow();
    }

    private List<ChatMessage> notices() {
        return store.values().stream().filter(m -> m.getKind() == ChatMessageKind.NOTICE)
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt)).toList();
    }

    private AiJobListener ask(ChatSession who, String text, boolean isPrivate) {
        assistant.handle(question(who.userId(), who.userName(), text, isPrivate, who.diagramId()), who);
        return listeners.getLast();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private List<Map<String, Object>> published(String kind) {
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(room, atLeast(0)).publish(any(), captor.capture());
        return captor.getAllValues().stream().map(m -> (Map<String, Object>) m)
                .filter(m -> kind.equals(m.get("kind"))).toList();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private List<Map<String, Object>> sentToSession(String kind) {
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(room, atLeast(0)).send(any(), captor.capture());
        return captor.getAllValues().stream().map(m -> (Map<String, Object>) m)
                .filter(m -> kind.equals(m.get("kind"))).toList();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Map<String, Object> applyChangesSentInDiagram() {
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(room).toUserInDiagram(eq(projectId), eq(luis), eq(diagramId), captor.capture());
        return captor.getValue();
    }

    @Test
    void drawLevelSendsApplyChangesToAsker() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        AiJobListener l = ask(luisInDiagram(), "@IA agrega Venta y DetalleVenta", false);
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.QUEUED);
        assertThat(formats.getLast()).isEqualTo(TeamAiReply.jsonSchema());
        l.started();
        l.finished(DRAW_REPLY);

        Map<String, Object> payload = applyChangesSentInDiagram();
        ChatMessage ai = aiMessage();
        assertThat(payload).containsEntry("kind", "apply_changes").containsEntry("messageId", ai.getId())
                .containsEntry("diagramId", diagramId);
        assertThat((List<?>) payload.get("suggestions")).hasSize(2);
        assertThat(ai.getStatus()).isEqualTo(ChatMessageStatus.COMPLETE);
        assertThat(ai.getContent()).isEqualTo("Luis, agregué Venta y DetalleVenta");
        assertThat(ai.getProposalJson()).contains("Venta", "DetalleVenta");
        assertThat(ai.getProposalState()).isEqualTo(ProposalState.NONE);
        assertThat(ai.getRequestedById()).isEqualTo(luis);
        assertThat(ai.getRequestedByName()).isEqualTo("Luis");
        assertThat(ai.getDiagramId()).isEqualTo(diagramId);
        assertThat(published("message")).isNotEmpty();
        assertThat(published("message_updated")).isNotEmpty();
    }

    @Test
    void drawFallsBackToPendingWhenAskerLeftTheDiagram() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(false);
        AiJobListener l = ask(luisInDiagram(), "@IA agrega Venta", false);
        l.finished(DRAW_REPLY);
        assertThat(aiMessage().getProposalState()).isEqualTo(ProposalState.PENDING);
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.COMPLETE);
    }

    @Test
    void suggestLevelStoresPendingProposal() {
        project.setHelpLevel(HelpLevel.SUGGEST);
        AiJobListener l = ask(luisInDiagram(), "@IA agrega Venta", false);
        l.finished(DRAW_REPLY);
        verify(room, never()).toUserInDiagram(any(), any(), any(), anyMap());
        ChatMessage ai = aiMessage();
        assertThat(ai.getProposalState()).isEqualTo(ProposalState.PENDING);
        MessageResponse response = chat.toResponse(ai);
        assertThat(response.proposal()).hasSize(2);
    }

    @Test
    void explainLevelIgnoresChanges() {
        project.setHelpLevel(HelpLevel.EXPLAIN);
        AiJobListener l = ask(luisInDiagram(), "@IA agrega Venta", false);
        l.finished(DRAW_REPLY);
        verify(room, never()).toUserInDiagram(any(), any(), any(), anyMap());
        assertThat(aiMessage().getProposalJson()).isNull();
        assertThat(aiMessage().getProposalState()).isEqualTo(ProposalState.NONE);
        assertThat(prompts.getLast().getFirst().get("content")).contains("`propuesta` va vacío");
    }

    @Test
    void viewerQuestionNeverDraws() {
        roles.put(luis, EffectiveRole.VIEWER);
        AiJobListener l = ask(session(luis, "Luis", diagramId, EffectiveRole.VIEWER), "@IA agrega Venta", false);
        l.finished(DRAW_REPLY);
        verify(room, never()).toUserInDiagram(any(), any(), any(), anyMap());
        assertThat(aiMessage().getProposalJson()).isNull();
        assertThat(prompts.getLast().getFirst().get("content")).contains("`propuesta` va vacío");
    }

    @Test
    void demotedToViewerWhileWaitingNeverDraws() {
        AiJobListener l = ask(luisInDiagram(), "@IA agrega Venta", false);
        roles.put(luis, EffectiveRole.VIEWER);
        l.finished(DRAW_REPLY);
        verify(room, never()).toUserInDiagram(any(), any(), any(), anyMap());
        assertThat(aiMessage().getProposalJson()).isNull();
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.COMPLETE);
    }

    @Test
    void questionFromProjectPageNeverDraws() {
        AiJobListener l = ask(session(luis, "Luis", null, EffectiveRole.EDITOR), "@IA agrega Venta", false);
        l.finished(DRAW_REPLY);
        verify(room, never()).toUserInDiagram(any(), any(), any(), anyMap());
        assertThat(aiMessage().getProposalJson()).isNull();
        assertThat(aiMessage().getContent()).isEqualTo("Luis, agregué Venta y DetalleVenta");
        assertThat(prompts.getLast().getFirst().get("content")).contains("`propuesta` va vacío");
    }

    @Test
    void chunksAreForwardedAsTheyArrive() {
        AiJobListener l = ask(luisInDiagram(), "@IA hola", false);
        l.queued(2);
        l.started();
        l.chunk("{\"mensa");
        l.chunk("je\":\"Hola ");
        l.chunk("Luis\",\"propuesta\":{");

        UUID id = aiMessage().getId();
        assertThat(published("queue_position")).containsExactly(
                Map.of("kind", "queue_position", "messageId", id, "position", 2));
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.WRITING);
        assertThat(published("chunk")).extracting(m -> m.get("text")).containsExactly("Hola ", "Luis");
        assertThat(published("chunk")).allSatisfy(m -> assertThat(m).containsEntry("messageId", id));
        verify(room, never()).toAll(any(), anyMap());
    }

    @Test
    void brokenJsonEndsAsFailedNotStuck() {
        AiJobListener l = ask(luisInDiagram(), "@IA hola", false);
        l.started();
        l.chunk("{\"mensaje\":\"Hola Lu");
        l.finished("{\"mensaje\":\"Hola Lu");
        ChatMessage ai = aiMessage();
        assertThat(ai.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(ai.getContent()).isEqualTo("Hola Lu");
        verify(room, never()).toUserInDiagram(any(), any(), any(), anyMap());
    }

    @Test
    void failedWithoutTextShowsReason() {
        AiJobListener l = ask(luisInDiagram(), "@IA hola", false);
        l.started();
        l.failed("La IA no respondió a tiempo");
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(aiMessage().getContent()).isEqualTo("La IA no respondió a tiempo");
    }

    @Test
    void offlineConnectorAnswersIADesconectada() {
        when(queue.submit(any(), any(), any())).thenThrow(
                new AiUnavailableException(AiUnavailableException.Reason.OFFLINE));
        assistant.handle(question(luis, "Luis", "@IA hola", false, diagramId), luisInDiagram());
        ChatMessage ai = aiMessage();
        assertThat(ai.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(ai.getContent()).isEqualTo("IA desconectada");
        assertThat(published("message_updated")).isNotEmpty();
    }

    @Test
    void summaryUsesSummarySchemaAndInstruction() {
        AiJobListener l = ask(luisInDiagram(), "@IA resumen", false);
        assertThat(formats.getLast()).isEqualTo(TeamAiReply.summarySchema());
        assertThat(prompts.getLast().getFirst().get("content"))
                .contains("Resume qué hizo cada integrante", "Ana: Producto");
        l.finished("{\"mensaje\":\"Ana hizo Producto\"}");
        assertThat(aiMessage().getContent()).isEqualTo("Ana hizo Producto");
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.COMPLETE);
        verify(room, never()).toUserInDiagram(any(), any(), any(), anyMap());
    }

    @Test
    void appliedPostsPublicNoticeAndRecordsActivity() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        ChatSession who = luisInDiagram();
        AiJobListener l = ask(who, "@IA agrega Venta y DetalleVenta", true);
        l.finished(DRAW_REPLY);
        UUID id = aiMessage().getId();

        assistant.applied(who, id, List.of("Venta", "DetalleVenta"), List.of());

        assertThat(aiMessage().getProposalState()).isEqualTo(ProposalState.APPLIED);
        ChatMessage notice = notices().getFirst();
        assertThat(notice.getContent()).isEqualTo("IA agregó Venta, DetalleVenta (pedido de Luis)");
        assertThat(notice.getPrivateOwnerId()).isNull();
        verify(activity).recordAiChange(projectId, diagramId, luis, "Luis", List.of("Venta", "DetalleVenta"));
        assertThat(published("message")).anySatisfy(m -> assertThat(
                ((MessageResponse) m.get("message")).content()).isEqualTo(notice.getContent()));
    }

    @Test
    void appliedFailuresArePrivateAndKeepProposalPending() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        ChatSession who = luisInDiagram();
        ask(who, "@IA agrega Venta", false).finished(DRAW_REPLY);
        UUID id = aiMessage().getId();

        assistant.applied(who, id, List.of("Venta"), List.of(new Failure("DetalleVenta", "está bloqueada")));

        assertThat(aiMessage().getProposalState()).isEqualTo(ProposalState.PENDING);
        assertThat(notices()).extracting(ChatMessage::getContent).containsExactly(
                "IA agregó Venta (pedido de Luis)", "No se pudo aplicar DetalleVenta: está bloqueada");
        assertThat(notices().get(1).getPrivateOwnerId()).isEqualTo(luis);
    }

    @Test
    void appliedForeignOrUnknownMessageIsRejected() {
        ChatSession who = luisInDiagram();
        UUID personMessage = question(ana, "Ana", "hola", false, diagramId).getId();
        assistant.applied(who, personMessage, List.of("Venta"), List.of());
        assistant.applied(who, UUID.randomUUID(), List.of("Venta"), List.of());
        project.setHelpLevel(HelpLevel.SUGGEST);
        ask(session(ana, "Ana", diagramId, EffectiveRole.EDITOR), "@IA agrega Venta", true).finished(DRAW_REPLY);
        assistant.applied(who, aiMessage().getId(), List.of("Venta"), List.of());

        assertThat(notices()).isEmpty();
        verify(activity, never()).recordAiChange(any(), any(), any(), any(), any());
        assertThat(sentToSession("error")).hasSize(3);
    }

    @Test
    void applyProposalRequiresEditorInSameDiagram() {
        project.setHelpLevel(HelpLevel.SUGGEST);
        ask(luisInDiagram(), "@IA agrega Venta", false).finished(DRAW_REPLY);
        UUID id = aiMessage().getId();

        // the current role is VIEWER, even though the session says EDITOR
        roles.put(ana, EffectiveRole.VIEWER);
        assistant.applyProposal(session(ana, "Ana", diagramId, EffectiveRole.EDITOR), id);
        roles.put(ana, EffectiveRole.EDITOR);
        assistant.applyProposal(session(ana, "Ana", UUID.randomUUID(), EffectiveRole.EDITOR), id);
        assistant.applyProposal(session(ana, "Ana", null, EffectiveRole.EDITOR), id);
        assertThat(sentToSession("apply_changes")).isEmpty();
        assertThat(sentToSession("error")).extracting(m -> m.get("message"))
                .contains("Abre el diagrama para aplicar el cambio");

        ChatSession anaHere = session(ana, "Ana", diagramId, EffectiveRole.EDITOR);
        assistant.applyProposal(anaHere, id);
        assertThat(sentToSession("apply_changes")).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("messageId", id).containsEntry("diagramId", diagramId);
            assertThat((List<?>) m.get("suggestions")).hasSize(2);
        });
        verify(room, atLeastOnce()).send(eq(anaHere), anyMap());
    }

    @Test
    void privateQuestionGetsPrivateAnswer() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        AiJobListener l = ask(luisInDiagram(), "¿está bien mi Venta?", true);
        ChatMessage ai = aiMessage();
        assertThat(ai.getPrivateOwnerId()).isEqualTo(luis);
        l.queued(1);
        l.started();
        l.chunk("{\"mensaje\":\"Sí");
        l.finished("{\"mensaje\":\"Sí, Luis\",\"propuesta\":null}");
        verify(room, atLeastOnce()).publish(
                argThat(m -> m.getId().equals(ai.getId()) && luis.equals(m.getPrivateOwnerId())), anyMap());
        verify(room, never()).publish(argThat(m -> m.getPrivateOwnerId() == null), anyMap());
        verify(room, never()).toAll(any(), anyMap());
        verify(room, never()).toEveryone(anyMap());
        assertThat(aiMessage().getContent()).isEqualTo("Sí, Luis");
    }

    @Test
    void privateHistoryOfOthersIsNotIncluded() {
        question(ana, "Ana", "secreto de Ana", true, diagramId);
        question(ana, "Ana", "hola equipo", false, diagramId);
        question(luis, "Luis", "nota privada de Luis", true, diagramId);
        ask(luisInDiagram(), "@IA ¿qué opinan?", false);

        String all = prompts.getLast().stream().map(m -> m.get("content")).reduce("", String::concat);
        assertThat(all).contains("[Ana]: hola equipo", "[Luis]: nota privada de Luis")
                .doesNotContain("secreto de Ana");
        assertThat(prompts.getLast().getLast().get("content")).isEqualTo("[Luis]: @IA ¿qué opinan?");
        assertThat(all.split("qué opinan", -1)).hasSize(2);
    }

    @Test
    void listenerNeverThrows() {
        AiJobListener l = ask(luisInDiagram(), "@IA hola", false);
        doThrow(new RuntimeException("socket")).when(room).publish(any(), anyMap());
        l.queued(1);
        l.started();
        l.chunk("{\"mensaje\":\"x");
        l.finished(DRAW_REPLY);
        l.failed("boom");
    }

    // ---- fix round 1 ----

    @Test
    void nullElementsInChangesAreSkipped() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        AiJobListener l = ask(luisInDiagram(), "@IA agrega Venta", false);
        l.finished("{\"mensaje\":\"Luis, listo\",\"propuesta\":{\"clasesNuevas\":[null,{\"nombre\":\"Venta\","
                + "\"atributos\":[null],\"metodos\":[null]}],\"atributosNuevos\":[null],"
                + "\"relacionesNuevas\":[null],\"cambiosRelacion\":[null]}}");
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.COMPLETE);
        assertThat((List<?>) applyChangesSentInDiagram().get("suggestions")).hasSize(1);
    }

    @Test
    void translatorFailureEndsAsFailedNotStuck() {
        ChangeTranslator broken = mock(ChangeTranslator.class);
        when(broken.translate(any(), any(), any())).thenThrow(new NullPointerException("boom"));
        assistant = assistantWith(broken, new TeamPromptBuilder());
        AiJobListener l = ask(luisInDiagram(), "@IA agrega Venta", false);
        l.started();
        l.chunk("{\"mensaje\":\"Luis, agrego");
        l.finished(DRAW_REPLY);
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(aiMessage().getContent()).isEqualTo("Luis, agrego");
        verify(room, never()).toUserInDiagram(any(), any(), any(), anyMap());
    }

    @Test
    void saveFailingOnceOnFinishEndsAsFailed() {
        AiJobListener l = ask(luisInDiagram(), "@IA hola", false);
        l.started();
        l.chunk("{\"mensaje\":\"Hola");
        failingSaves.set(1);
        l.finished("{\"mensaje\":\"Hola Luis\",\"propuesta\":null}");
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(aiMessage().getContent()).isEqualTo("Hola");
    }

    @Test
    void saveFailingOnceOnFailureStillEndsAsFailed() {
        AiJobListener l = ask(luisInDiagram(), "@IA hola", false);
        l.started();
        failingSaves.set(1);
        l.failed("La IA no respondió a tiempo");
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(aiMessage().getContent()).isEqualTo("La IA no respondió a tiempo");
    }

    @Test
    void unexpectedSubmitErrorEndsAsFailed() {
        when(queue.submit(any(), any(), any())).thenThrow(new IllegalStateException("queue broke"));
        assistant.handle(question(luis, "Luis", "@IA hola", false, diagramId), luisInDiagram());
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(aiMessage().getContent()).isEqualTo("La IA no pudo responder. Prueba de nuevo");
    }

    @Test
    void promptFailureEndsAsFailed() {
        TeamPromptBuilder broken = mock(TeamPromptBuilder.class);
        when(broken.build(any())).thenThrow(new IllegalArgumentException("bad"));
        assistant = assistantWith(new ChangeTranslator(new AiSuggestionValidator()), broken);
        assistant.handle(question(luis, "Luis", "@IA hola", false, diagramId), luisInDiagram());
        assertThat(aiMessage().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        verify(queue, never()).submit(any(), any(), any());
    }

    @Test
    void appliedOnlyAcceptsNamesFromTheProposal() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        ChatSession who = luisInDiagram();
        ask(who, "@IA agrega Venta", false).finished(DRAW_REPLY);
        assistant.applied(who, aiMessage().getId(), List.of("venta", "Hackeo"),
                List.of(new Failure("Inventada", "x")));
        assertThat(notices()).extracting(ChatMessage::getContent)
                .containsExactly("IA agregó Venta (pedido de Luis)");
        verify(activity).recordAiChange(projectId, diagramId, luis, "Luis", List.of("Venta"));
    }

    @Test
    void appliedIsIgnoredOnceApplied() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        ChatSession who = luisInDiagram();
        ask(who, "@IA agrega Venta", false).finished(DRAW_REPLY);
        UUID id = aiMessage().getId();
        assistant.applied(who, id, List.of("Venta"), List.of());
        assistant.applied(who, id, List.of("Venta", "DetalleVenta"), List.of());
        assertThat(notices()).hasSize(1);
        verify(activity, times(1)).recordAiChange(any(), any(), any(), any(), any());
        assertThat(aiMessage().getProposalState()).isEqualTo(ProposalState.APPLIED);
    }

    @Test
    void drawnProposalAcceptsReportsOnlyFromRequester() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        ask(luisInDiagram(), "@IA agrega Venta", false).finished(DRAW_REPLY);
        assistant.applied(session(ana, "Ana", diagramId, EffectiveRole.EDITOR), aiMessage().getId(),
                List.of("Venta"), List.of());
        assertThat(notices()).isEmpty();
        assertThat(sentToSession("error")).hasSize(1);
        assertThat(aiMessage().getProposalState()).isEqualTo(ProposalState.NONE);
    }

    @Test
    void pendingProposalAcceptsReportsOnlyFromWhoItWasSentTo() {
        project.setHelpLevel(HelpLevel.SUGGEST);
        ask(luisInDiagram(), "@IA agrega Venta", false).finished(DRAW_REPLY);
        UUID id = aiMessage().getId();
        ChatSession anaHere = session(ana, "Ana", diagramId, EffectiveRole.EDITOR);

        assistant.applied(anaHere, id, List.of("Venta"), List.of());
        assertThat(notices()).isEmpty();

        assistant.applyProposal(anaHere, id);
        assistant.applied(anaHere, id, List.of("Venta"), List.of());
        assertThat(notices()).extracting(ChatMessage::getContent)
                .containsExactly("IA agregó Venta (pedido de Luis)");
    }

    @Test
    void messageUpdatedIsPublishedBeforeApplyChanges() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        ask(luisInDiagram(), "@IA agrega Venta", false).finished(DRAW_REPLY);
        InOrder order = inOrder(room);
        order.verify(room).publish(argThat(m -> m.getKind() == ChatMessageKind.AI
                && m.getStatus() == ChatMessageStatus.COMPLETE),
                argThat(p -> "message_updated".equals(p.get("kind"))));
        order.verify(room).toUserInDiagram(any(), any(), any(), anyMap());
    }

    @Test
    void unreportedDrawingBecomesPendingAfterThirtySeconds() {
        when(room.toUserInDiagram(any(), any(), any(), anyMap())).thenReturn(true);
        ask(luisInDiagram(), "@IA agrega Venta", false).finished(DRAW_REPLY);
        UUID id = aiMessage().getId();

        time.advance(Duration.ofSeconds(20));
        assistant.releaseUnreportedDrawings();
        assertThat(aiMessage().getProposalState()).isEqualTo(ProposalState.NONE);

        time.advance(Duration.ofSeconds(11));
        assistant.releaseUnreportedDrawings();
        assertThat(aiMessage().getProposalState()).isEqualTo(ProposalState.PENDING);
        assertThat(published("message_updated")).anySatisfy(m -> {
            MessageResponse r = (MessageResponse) m.get("message");
            assertThat(r.id()).isEqualTo(id);
            assertThat(r.proposalState()).isEqualTo("PENDING");
        });

        // the asker's late report is still accepted
        assistant.applied(luisInDiagram(), id, List.of("Venta"), List.of());
        assertThat(aiMessage().getProposalState()).isEqualTo(ProposalState.APPLIED);
    }

    @Test
    void sweepAlsoReleasesDrawingsLostByARestart() {
        ChatMessage old = new ChatMessage();
        old.setProjectId(projectId);
        old.setAuthorName("IA");
        old.setKind(ChatMessageKind.AI);
        old.setDiagramId(diagramId);
        old.setContent("Luis, agregué Venta");
        old.setStatus(ChatMessageStatus.COMPLETE);
        old.setProposalJson("[]");
        old.setProposalState(ProposalState.NONE);
        old.setRequestedById(luis);
        old.setRequestedByName("Luis");
        UUID id = chat.save(old).getId();

        time.advance(Duration.ofMinutes(16));
        assistant.releaseUnreportedDrawings();
        assertThat(store.get(id).getProposalState()).isEqualTo(ProposalState.PENDING);
    }
}
