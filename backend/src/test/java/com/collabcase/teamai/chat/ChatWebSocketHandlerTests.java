package com.collabcase.teamai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.collabcase.auth.JwtService;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.project.ProjectAccessService.Access;
import com.collabcase.project.ProjectAccessService.EffectiveRole;
import com.collabcase.teamai.chat.dto.ChatDtos.MessageResponse;
import com.collabcase.teamai.connector.AiJobQueue;
import com.collabcase.user.User;
import com.collabcase.user.UserService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ChatWebSocketHandlerTests {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final UUID projectP = UUID.randomUUID();
    private final UUID projectQ = UUID.randomUUID();

    private JwtService jwt;
    private UserService users;
    private ProjectAccessService access;
    private ChatService chat;
    private AiJobQueue queue;
    private QuestionHandler questions;
    private ChatRoom room;
    private ChatWebSocketHandler handler;

    private static class Sock {
        final WebSocketSession session = mock(WebSocketSession.class);
        final List<String> sent = new CopyOnWriteArrayList<>();

        Sock(String id, UUID projectId) throws Exception {
            when(session.getId()).thenReturn(id);
            when(session.isOpen()).thenReturn(true);
            when(session.getUri()).thenReturn(new URI("ws://localhost/ws/chat/" + projectId));
            Mockito.doAnswer(i -> {
                sent.add(((TextMessage) i.getArgument(0)).getPayload());
                return null;
            }).when(session).sendMessage(any(TextMessage.class));
        }

        List<String> kinds() {
            return sent.stream().map(s -> s.replaceAll(".*\"kind\":\"([a-z_]+)\".*", "$1")).toList();
        }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        jwt = mock(JwtService.class);
        users = mock(UserService.class);
        access = mock(ProjectAccessService.class);
        chat = mock(ChatService.class);
        queue = mock(AiJobQueue.class);
        questions = mock(QuestionHandler.class);
        room = new ChatRoom(json);
        ObjectProvider<QuestionHandler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(questions);
        handler = new ChatWebSocketHandler(jwt, users, access, chat, room, queue, provider, json);
    }

    private UUID login(Sock sock, String name, EffectiveRole role, UUID projectId, String extraAuthJson)
            throws Exception {
        UUID userId = UUID.randomUUID();
        authAs(sock, userId, name, role, projectId, extraAuthJson);
        return userId;
    }

    private void authAs(Sock sock, UUID userId, String name, EffectiveRole role, UUID projectId,
            String extraAuthJson) throws Exception {
        User user = new User();
        user.setId(userId);
        user.setName(name);
        String token = "tok-" + sock.session.getId();
        when(jwt.extractUserId(token)).thenReturn(userId);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(access.requireRead(projectId, userId)).thenReturn(new Access(null, role));
        handler.handleTextMessage(sock.session,
                new TextMessage("{\"kind\":\"auth\",\"token\":\"" + token + "\"" + extraAuthJson + "}"));
    }

    private ChatMessage message(UUID projectId, UUID privateOwner, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setProjectId(projectId);
        m.setPrivateOwnerId(privateOwner);
        m.setContent(content);
        return m;
    }

    private void stubPost(UUID projectId, String content, boolean isPrivate, UUID userId) {
        ChatMessage saved = message(projectId, isPrivate ? userId : null, content);
        when(chat.postFromPerson(eq(projectId), eq(userId), eq(content), eq(isPrivate), any())).thenReturn(saved);
        when(chat.toResponse(saved)).thenReturn(new MessageResponse(saved.getId(), "x", userId, "PERSON", isPrivate,
                null, content, "COMPLETE", null, "NONE", null, null));
    }

    @Test
    void authWithBadTokenCloses() throws Exception {
        Sock a = new Sock("a", projectP);
        when(jwt.extractUserId(any())).thenThrow(new IllegalArgumentException("bad"));

        handler.handleTextMessage(a.session, new TextMessage("{\"kind\":\"auth\",\"token\":\"nope\"}"));

        verify(a.session).close(new CloseStatus(4001, "Authentication failed"));
        assertThat(a.kinds()).containsExactly("auth_error");
    }

    @Test
    void authSendsAuthOkAndAiStatus() throws Exception {
        when(queue.connected()).thenReturn(true);
        when(queue.size()).thenReturn(2);
        Sock a = new Sock("a", projectP);
        login(a, "Ana", EffectiveRole.EDITOR, projectP, "");
        assertThat(a.kinds()).containsExactly("auth_ok", "ai_status");
        assertThat(a.sent.get(1)).contains("\"connected\":true").contains("\"queued\":2");
    }

    @Test
    void sendPublishesToEveryoneInProject() throws Exception {
        Sock a = new Sock("a", projectP);
        Sock b = new Sock("b", projectP);
        Sock c = new Sock("c", projectQ);
        UUID ana = login(a, "Ana", EffectiveRole.OWNER, projectP, "");
        login(b, "Beto", EffectiveRole.EDITOR, projectP, "");
        login(c, "Carla", EffectiveRole.EDITOR, projectQ, "");
        stubPost(projectP, "hola", false, ana);

        handler.handleTextMessage(a.session, new TextMessage("{\"kind\":\"send\",\"content\":\"hola\"}"));

        assertThat(a.kinds()).contains("message");
        assertThat(b.kinds()).contains("message");
        assertThat(c.kinds()).doesNotContain("message");
    }

    @Test
    void privateChunksOnlyReachOwner() throws Exception {
        Sock ana1 = new Sock("a1", projectP);
        Sock ana2 = new Sock("a2", projectP);
        Sock beto = new Sock("b", projectP);
        UUID ana = login(ana1, "Ana", EffectiveRole.OWNER, projectP, "");
        authAs(ana2, ana, "Ana", EffectiveRole.OWNER, projectP, "");
        login(beto, "Beto", EffectiveRole.EDITOR, projectP, "");

        room.publish(message(projectP, ana, "secreto"), Map.of("kind", "message_updated"));
        room.toUser(projectP, ana, Map.of("kind", "chunk"));

        assertThat(ana1.kinds()).contains("message_updated", "chunk");
        assertThat(ana2.kinds()).contains("message_updated", "chunk");
        assertThat(beto.kinds()).doesNotContain("message_updated", "chunk");
    }

    @Test
    void toUserInDiagramReturnsFalseWhenUserNotInThatDiagram() throws Exception {
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        Sock a = new Sock("a", projectP);
        UUID ana = login(a, "Ana", EffectiveRole.EDITOR, projectP, ",\"diagramId\":\"" + d1 + "\"");

        assertThat(room.toUserInDiagram(projectP, ana, d2, Map.of("kind", "apply_changes"))).isFalse();
        assertThat(room.toUserInDiagram(projectP, ana, null, Map.of("kind", "apply_changes"))).isFalse();
        assertThat(a.kinds()).doesNotContain("apply_changes");
        assertThat(room.toUserInDiagram(projectP, ana, d1, Map.of("kind", "apply_changes"))).isTrue();
        assertThat(a.kinds()).contains("apply_changes");
    }

    @Test
    void mentionTriggersQuestionHandlerAndPlainChatDoesNot() throws Exception {
        Sock a = new Sock("a", projectP);
        UUID ana = login(a, "Ana", EffectiveRole.EDITOR, projectP, "");
        stubPost(projectP, "hola equipo", false, ana);
        stubPost(projectP, "@IA ayuda", false, ana);

        handler.handleTextMessage(a.session, new TextMessage("{\"kind\":\"send\",\"content\":\"hola equipo\"}"));
        verify(questions, never()).handle(any(), any());

        handler.handleTextMessage(a.session, new TextMessage("{\"kind\":\"send\",\"content\":\"@IA ayuda\"}"));
        verify(questions).handle(any(ChatMessage.class), any(ChatSession.class));
    }

    @Test
    void privateMessageTriggersQuestionHandlerWithoutMention() throws Exception {
        Sock a = new Sock("a", projectP);
        UUID ana = login(a, "Ana", EffectiveRole.EDITOR, projectP, "");
        stubPost(projectP, "dime algo", true, ana);

        handler.handleTextMessage(a.session,
                new TextMessage("{\"kind\":\"send\",\"content\":\"dime algo\",\"private\":true}"));

        verify(questions).handle(any(ChatMessage.class), any(ChatSession.class));
    }

    @Test
    void viewerCanSendAndAsk() throws Exception {
        Sock v = new Sock("v", projectP);
        UUID viewer = login(v, "Vera", EffectiveRole.VIEWER, projectP, "");
        stubPost(projectP, "@ia que es esto", false, viewer);

        handler.handleTextMessage(v.session, new TextMessage("{\"kind\":\"send\",\"content\":\"@ia que es esto\"}"));

        ArgumentCaptor<ChatSession> asker = ArgumentCaptor.forClass(ChatSession.class);
        verify(questions).handle(any(ChatMessage.class), asker.capture());
        assertThat(asker.getValue().role()).isEqualTo(EffectiveRole.VIEWER);
        assertThat(asker.getValue().userId()).isEqualTo(viewer);
    }

    @Test
    void invalidMessagesGetErrorReplyAndKeepSocketOpen() throws Exception {
        Sock a = new Sock("a", projectP);
        UUID ana = login(a, "Ana", EffectiveRole.EDITOR, projectP, "");
        when(chat.postFromPerson(eq(projectP), eq(ana), any(), anyBoolean(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "El mensaje debe tener entre 1 y 2000 caracteres"));

        handler.handleTextMessage(a.session, new TextMessage("{not json"));
        handler.handleTextMessage(a.session, new TextMessage("{\"kind\":\"zzz\"}"));
        handler.handleTextMessage(a.session, new TextMessage("{\"kind\":\"send\",\"content\":\"\"}"));
        handler.handleTextMessage(a.session,
                new TextMessage("{\"kind\":\"apply_proposal\",\"messageId\":\"no-uuid\"}"));

        assertThat(a.kinds()).containsExactly("auth_ok", "ai_status", "error", "error", "error", "error");
        assertThat(a.sent.get(4)).contains("entre 1 y 2000");
        verify(a.session, never()).close(any(CloseStatus.class));
    }

    @Test
    void changesAppliedAndApplyProposalAreDelegated() throws Exception {
        Sock a = new Sock("a", projectP);
        login(a, "Ana", EffectiveRole.EDITOR, projectP, "");
        UUID id = UUID.randomUUID();

        handler.handleTextMessage(a.session, new TextMessage("{\"kind\":\"changes_applied\",\"messageId\":\"" + id
                + "\",\"applied\":[\"Pedido\"],\"failed\":[{\"name\":\"Cliente\",\"reason\":\"ya existe\"}]}"));
        handler.handleTextMessage(a.session,
                new TextMessage("{\"kind\":\"apply_proposal\",\"messageId\":\"" + id + "\"}"));

        verify(questions).applied(any(ChatSession.class), eq(id), eq(List.of("Pedido")),
                eq(List.of(new QuestionHandler.Failure("Cliente", "ya existe"))));
        verify(questions).applyProposal(any(ChatSession.class), eq(id));
    }

    @Test
    void queueChangesBroadcastAiStatusToConnectedSessions() throws Exception {
        ArgumentCaptor<Runnable> listener = ArgumentCaptor.forClass(Runnable.class);
        verify(queue).onChange(listener.capture());
        Sock a = new Sock("a", projectP);
        login(a, "Ana", EffectiveRole.EDITOR, projectP, "");
        when(queue.connected()).thenReturn(false);

        listener.getValue().run();

        assertThat(a.kinds()).endsWith("ai_status");
        assertThat(a.sent.get(a.sent.size() - 1)).contains("\"connected\":false");
    }
}
