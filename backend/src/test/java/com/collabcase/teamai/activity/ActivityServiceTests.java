package com.collabcase.teamai.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.collabcase.collaboration.ElementLockManager;
import com.collabcase.collaboration.ElementLockManager.ElementLock;
import com.collabcase.diagram.Diagram;
import com.collabcase.diagram.DiagramRepository;
import com.collabcase.project.Project;
import com.collabcase.project.ProjectRepository;
import com.collabcase.user.User;
import com.collabcase.user.UserRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class ActivityServiceTests {

    @Autowired ActivityRepository activity;
    @Autowired DiagramRepository diagrams;
    @Autowired ProjectRepository projects;
    @Autowired UserRepository users;

    /** Mutable clock so the 5-minute window and "hace N min" are testable. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final TestClock clock = new TestClock();
    private final ElementLockManager locks = mock(ElementLockManager.class);

    private ActivityService service() {
        return new ActivityService(activity, diagrams, locks, clock);
    }

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

    private Diagram diagram(Project p) {
        Diagram d = new Diagram();
        d.setProject(p);
        d.setName("D");
        d.setType("ClassDiagram");
        d.setModel(Map.of(
                "nodes", List.of(
                        Map.of("id", "n1", "type", "class", "data", Map.of("name", "Producto")),
                        Map.of("id", "n2", "type", "class", "data", Map.of("name", "Inventario"))),
                "edges", List.of(Map.of("id", "e1", "source", "n1", "target", "n2", "type", "association"))));
        return diagrams.saveAndFlush(d);
    }

    @Test
    void lockGrantRecordsElementNameFromModel() {
        User ana = user("Ana");
        Project p = project(ana);
        Diagram d = diagram(p);

        service().onLockGranted(new ElementLockGrantedEvent(d.getId(), ana.getId(), "Ana", "n1"));
        service().onLockGranted(new ElementLockGrantedEvent(d.getId(), ana.getId(), "Ana", "e1"));

        List<ActivityEntry> saved = activity.findTop40ByProjectIdOrderByAtDesc(p.getId());
        assertThat(saved).extracting(ActivityEntry::getElementName)
                .containsExactlyInAnyOrder("Producto", "Producto–Inventario");
        assertThat(saved).allSatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("EDITING");
            assertThat(e.getUserName()).isEqualTo("Ana");
            assertThat(e.getDiagramId()).isEqualTo(d.getId());
            assertThat(e.getAt()).isEqualTo(clock.instant());
        });
    }

    @Test
    void repeatedLockWithinFiveMinutesIsNotDuplicated() {
        User ana = user("Ana");
        User luis = user("Luis");
        Project p = project(ana);
        Diagram d = diagram(p);
        ElementLockGrantedEvent anaLock = new ElementLockGrantedEvent(d.getId(), ana.getId(), "Ana", "n1");

        service().onLockGranted(anaLock);
        clock.advance(Duration.ofMinutes(4));
        service().onLockGranted(anaLock);
        assertThat(activity.findTop40ByProjectIdOrderByAtDesc(p.getId())).hasSize(1);

        // another user on the same element is not a duplicate
        service().onLockGranted(new ElementLockGrantedEvent(d.getId(), luis.getId(), "Luis", "n1"));
        assertThat(activity.findTop40ByProjectIdOrderByAtDesc(p.getId())).hasSize(2);

        // past the window (from the first record) it is recorded again
        clock.advance(Duration.ofMinutes(2));
        service().onLockGranted(anaLock);
        assertThat(activity.findTop40ByProjectIdOrderByAtDesc(p.getId())).hasSize(3);
    }

    @Test
    void unknownElementIsIgnored() {
        User ana = user("Ana");
        Project p = project(ana);
        Diagram d = diagram(p);

        service().onLockGranted(new ElementLockGrantedEvent(d.getId(), ana.getId(), "Ana", "zzz"));
        service().onLockGranted(new ElementLockGrantedEvent(UUID.randomUUID(), ana.getId(), "Ana", "n1"));

        assertThat(activity.findTop40ByProjectIdOrderByAtDesc(p.getId())).isEmpty();
    }

    @Test
    void summaryGroupsByPersonAndIncludesAiChanges() {
        User ana = user("Ana");
        User luis = user("Luis");
        Project p = project(ana);
        Diagram d = diagram(p);
        ActivityService svc = service();

        svc.onLockGranted(new ElementLockGrantedEvent(d.getId(), ana.getId(), "Ana", "n1"));
        clock.advance(Duration.ofMinutes(10));
        svc.recordAiChange(p.getId(), d.getId(), luis.getId(), "Luis", List.of("Venta", "DetalleVenta"));
        svc.onLockGranted(new ElementLockGrantedEvent(d.getId(), ana.getId(), "Ana", "n2"));
        clock.advance(Duration.ofMinutes(3));

        List<String> lines = svc.summaryLines(p.getId());

        assertThat(lines).containsExactly(
                "Ana: Inventario, Producto (último cambio hace 3 min)",
                "IA (pedido de Luis): Venta, DetalleVenta");
        assertThat(svc.summaryLines(UUID.randomUUID())).isEmpty();
    }

    @Test
    void relativeTimeTextIsInSpanish() {
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        assertThat(ActivityService.ago(now.minusSeconds(30), now)).isEqualTo("hace menos de 1 min");
        assertThat(ActivityService.ago(now.minusSeconds(60 * 7), now)).isEqualTo("hace 7 min");
        assertThat(ActivityService.ago(now.minusSeconds(3600 * 2 + 5), now)).isEqualTo("hace 2 h");
    }

    @Test
    void editingNowListsOnlyThisProjectsLocks() {
        User ana = user("Ana");
        Project mine = project(ana);
        Project other = project(ana);
        Diagram dMine = diagram(mine);
        Diagram dOther = diagram(other);
        Instant now = clock.instant();
        when(locks.snapshotAll()).thenReturn(Map.of(
                dMine.getId(), List.of(new ElementLock("n1", ana.getId(), "Ana", "s1", now, now.plusSeconds(30))),
                dOther.getId(), List.of(new ElementLock("n2", ana.getId(), "Luis", "s2", now, now.plusSeconds(30))),
                UUID.randomUUID(), List.of(new ElementLock("n1", ana.getId(), "Pepe", "s3", now, now.plusSeconds(30)))));

        assertThat(service().editingNow(mine.getId())).containsExactly("Ana está editando Producto");
    }
}
