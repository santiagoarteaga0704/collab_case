package com.collabcase.teamai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.collabcase.project.Project;
import com.collabcase.project.ProjectRepository;
import com.collabcase.user.User;
import com.collabcase.user.UserRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class ChatPersistenceTests {

    @Autowired ChatMessageRepository messages;
    @Autowired ProjectRepository projects;
    @Autowired UserRepository users;
    @Autowired EntityManager em;

    private User user(String name) {
        User u = new User();
        u.setName(name);
        u.setEmail(name + UUID.randomUUID() + "@x.com");
        u.setPassword("x");
        return users.saveAndFlush(u);
    }

    private Project project(User owner) {
        Project p = new Project();
        p.setOwner(owner);
        p.setName("P");
        return projects.saveAndFlush(p);
    }

    private ChatMessage msg(UUID projectId, String content, UUID privateOwner, long second) {
        ChatMessage m = new ChatMessage();
        m.setProjectId(projectId);
        m.setKind(ChatMessageKind.PERSON);
        m.setContent(content);
        m.setPrivateOwnerId(privateOwner);
        m.setCreatedAt(Instant.ofEpochSecond(second));
        return messages.saveAndFlush(m);
    }

    @Test
    void visibleToReturnsPublicAndOwnPrivateOnlyNewestFirst() {
        User ana = user("Ana");
        User luis = user("Luis");
        Project p = project(ana);
        Project other = project(ana);
        msg(p.getId(), "pub1", null, 1);
        msg(p.getId(), "ana-priv", ana.getId(), 2);
        msg(p.getId(), "luis-priv", luis.getId(), 3);
        msg(p.getId(), "pub2", null, 4);
        msg(other.getId(), "otro-proyecto", null, 5);

        List<ChatMessage> forAna = messages.visibleTo(p.getId(), ana.getId(), PageRequest.of(0, 100));
        List<ChatMessage> forLuis = messages.visibleTo(p.getId(), luis.getId(), PageRequest.of(0, 100));
        List<ChatMessage> forNobody = messages.visibleTo(p.getId(), UUID.randomUUID(), PageRequest.of(0, 100));

        assertThat(forAna).extracting(ChatMessage::getContent).containsExactly("pub2", "ana-priv", "pub1");
        assertThat(forLuis).extracting(ChatMessage::getContent).containsExactly("pub2", "luis-priv", "pub1");
        assertThat(forNobody).extracting(ChatMessage::getContent).containsExactly("pub2", "pub1");
    }

    @Test
    void visibleToRespectsPageSize() {
        User ana = user("Ana");
        Project p = project(ana);
        for (int i = 1; i <= 105; i++) msg(p.getId(), "m" + i, null, i);

        List<ChatMessage> page = messages.visibleTo(p.getId(), ana.getId(), PageRequest.of(0, 100));

        assertThat(page).hasSize(100);
        assertThat(page.get(0).getContent()).isEqualTo("m105");
        assertThat(page.get(99).getContent()).isEqualTo("m6");
    }

    @Test
    void projectWithoutHelpLevelDefaultsToDrawAfterReload() {
        Project p = project(user("Ana"));
        em.clear();
        Project reloaded = projects.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getHelpLevel()).isNull();
        assertThat(reloaded.effectiveHelpLevel()).isEqualTo(HelpLevel.DRAW);

        reloaded.setHelpLevel(HelpLevel.EXPLAIN);
        projects.saveAndFlush(reloaded);
        em.clear();
        assertThat(projects.findById(p.getId()).orElseThrow().effectiveHelpLevel()).isEqualTo(HelpLevel.EXPLAIN);
    }

    @Test
    void chatMessageRoundTrips() {
        Project p = project(user("Ana"));
        UUID author = UUID.randomUUID();
        UUID diagram = UUID.randomUUID();
        String longText = "x".repeat(2000);
        ChatMessage m = new ChatMessage();
        m.setProjectId(p.getId());
        m.setAuthorId(author);
        m.setAuthorName("Ana");
        m.setKind(ChatMessageKind.AI);
        m.setDiagramId(diagram);
        m.setContent(longText);
        m.setStatus(ChatMessageStatus.QUEUED);
        m.setProposalJson("[{\"type\":\"ADD_CLASS\"}]".repeat(100));
        m.setProposalState(ProposalState.PENDING);
        m.setRequestedByName("Luis");
        messages.saveAndFlush(m);
        em.clear();

        ChatMessage r = messages.findById(m.getId()).orElseThrow();
        assertThat(r.getCreatedAt()).isNotNull();
        assertThat(r.getContent()).isEqualTo(longText);
        assertThat(r.getProposalJson()).isEqualTo(m.getProposalJson());
        assertThat(r.getKind()).isEqualTo(ChatMessageKind.AI);
        assertThat(r.getStatus()).isEqualTo(ChatMessageStatus.QUEUED);
        assertThat(r.getProposalState()).isEqualTo(ProposalState.PENDING);
        assertThat(r.getDiagramId()).isEqualTo(diagram);
        assertThat(r.getRequestedByName()).isEqualTo("Luis");
        assertThat(r.getPrivateOwnerId()).isNull();
    }
}
