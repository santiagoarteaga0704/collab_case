package com.collabcase.teamai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.collabcase.diagram.Diagram;
import com.collabcase.diagram.DiagramRepository;
import com.collabcase.project.Project;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.project.ProjectAccessService.Access;
import com.collabcase.project.ProjectAccessService.EffectiveRole;
import com.collabcase.project.ProjectRepository;
import com.collabcase.teamai.chat.dto.ChatDtos.MessageResponse;
import com.collabcase.user.User;
import com.collabcase.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

class ChatServiceTests {

    ChatMessageRepository messages = mock(ChatMessageRepository.class);
    ProjectAccessService access = mock(ProjectAccessService.class);
    DiagramRepository diagrams = mock(DiagramRepository.class);
    ProjectRepository projects = mock(ProjectRepository.class);
    UserRepository users = mock(UserRepository.class);
    ChatService service;
    UUID projectId = UUID.randomUUID();
    UUID ana = UUID.randomUUID();
    UUID luis = UUID.randomUUID();
    Project project = new Project();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(project, "id", projectId);
        service = new ChatService(messages, access, diagrams, projects, users, new ObjectMapper());
        when(access.requireRead(eq(projectId), any())).thenReturn(new Access(project, EffectiveRole.EDITOR));
        when(messages.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        User u = new User();
        u.setName("Ana");
        when(users.findById(any())).thenReturn(Optional.of(u));
    }

    private ChatMessage msg(int n) {
        ChatMessage m = new ChatMessage();
        m.setProjectId(projectId);
        m.setKind(ChatMessageKind.PERSON);
        m.setContent("m" + n);
        m.setStatus(ChatMessageStatus.COMPLETE);
        m.setProposalState(ProposalState.NONE);
        m.setCreatedAt(Instant.ofEpochSecond(n));
        return m;
    }

    @Test
    void historyIsChronologicalAndCappedAt100() {
        List<ChatMessage> desc = new ArrayList<>();
        for (int i = 100; i >= 1; i--) desc.add(msg(i));
        when(messages.visibleTo(eq(projectId), eq(ana), any(Pageable.class))).thenReturn(desc);

        List<MessageResponse> h = service.history(projectId, ana);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messages).visibleTo(eq(projectId), eq(ana), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(100);
        assertThat(h).hasSize(100);
        assertThat(h.get(0).content()).isEqualTo("m1");
        assertThat(h.get(99).content()).isEqualTo("m100");
    }

    @Test
    void privateMessagesAreHiddenFromOthers() {
        ChatMessage priv = service.postFromPerson(projectId, ana, "secreto", true, null);
        assertThat(priv.getPrivateOwnerId()).isEqualTo(ana);
        when(messages.visibleTo(eq(projectId), any(), any(Pageable.class))).thenAnswer(i -> {
            UUID u = i.getArgument(1);
            return List.of(priv).stream()
                    .filter(m -> m.getPrivateOwnerId() == null || m.getPrivateOwnerId().equals(u)).toList();
        });
        assertThat(service.history(projectId, luis)).isEmpty();
        assertThat(service.history(projectId, ana)).hasSize(1);
        assertThat(service.history(projectId, ana).get(0).isPrivate()).isTrue();
    }

    @Test
    void outsiderGets404() {
        UUID outsider = UUID.randomUUID();
        when(access.requireRead(projectId, outsider))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.history(projectId, outsider))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void blankOrTooLongMessageIsRejected() {
        for (String bad : new String[] { "   ", null, "x".repeat(2001) }) {
            assertThatThrownBy(() -> service.postFromPerson(projectId, ana, bad, false, null))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(service.postFromPerson(projectId, ana, "  hola  ", false, null).getContent()).isEqualTo("hola");
        assertThat(service.postFromPerson(projectId, ana, "x".repeat(2000), false, null)).isNotNull();
    }

    @Test
    void diagramFromAnotherProjectIsRejected() {
        UUID d = UUID.randomUUID();
        Project other = new Project();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        Diagram diagram = new Diagram();
        diagram.setProject(other);
        when(diagrams.findById(d)).thenReturn(Optional.of(diagram));
        assertThatThrownBy(() -> service.postFromPerson(projectId, ana, "hola", false, d))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        diagram.setProject(project);
        assertThat(service.postFromPerson(projectId, ana, "hola", false, d).getDiagramId()).isEqualTo(d);
    }

    @Test
    void onlyOwnerChangesHelpLevel() {
        when(access.requireOwner(projectId, luis))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.setHelpLevel(projectId, luis, HelpLevel.EXPLAIN))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        when(access.requireOwner(projectId, ana)).thenReturn(new Access(project, EffectiveRole.OWNER));
        assertThat(service.setHelpLevel(projectId, ana, HelpLevel.EXPLAIN)).isEqualTo(HelpLevel.EXPLAIN);
        assertThat(project.getHelpLevel()).isEqualTo(HelpLevel.EXPLAIN);
        verify(projects).save(project);
    }

    @Test
    void helpLevelDefaultsToDraw() {
        assertThat(new Project().effectiveHelpLevel()).isEqualTo(HelpLevel.DRAW);
    }

    @Test
    void mentionsAiMatchesOnlyTheMention() {
        for (String s : new String[] { "@IA hola", "oye @ia", "@Ia resumen" })
            assertThat(ChatService.mentionsAi(s)).as(s).isTrue();
        for (String s : new String[] { "email@ia.com", "IA ayuda", "@iacosa" })
            assertThat(ChatService.mentionsAi(s)).as(s).isFalse();
    }
}
