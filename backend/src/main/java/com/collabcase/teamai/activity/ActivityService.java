package com.collabcase.teamai.activity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.collabcase.collaboration.ElementLockManager;
import com.collabcase.collaboration.ElementLockManager.ElementLock;
import com.collabcase.diagram.Diagram;
import com.collabcase.diagram.DiagramRepository;

@Service
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);
    private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(5);

    private final ActivityRepository activity;
    private final DiagramRepository diagrams;
    private final ElementLockManager locks;
    private final Clock clock;

    @Autowired
    public ActivityService(ActivityRepository activity, DiagramRepository diagrams, ElementLockManager locks) {
        this(activity, diagrams, locks, Clock.systemUTC());
    }

    public ActivityService(ActivityRepository activity, DiagramRepository diagrams, ElementLockManager locks,
            Clock clock) {
        this.activity = activity;
        this.diagrams = diagrams;
        this.locks = locks;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onLockGranted(ElementLockGrantedEvent e) {
        try {
            Diagram diagram = diagrams.findById(e.diagramId()).orElse(null);
            if (diagram == null) return;
            String name = elementName(diagram.getModel(), e.elementId());
            if (name == null) return;
            UUID projectId = diagram.getProject().getId();
            Instant now = clock.instant();
            if (activity.existsByProjectIdAndUserIdAndElementNameAndActionAndAtAfter(
                    projectId, e.userId(), name, ActivityEntry.EDITING, now.minus(DEDUPE_WINDOW))) {
                return;
            }
            save(projectId, e.diagramId(), e.userId(), e.userName(), name, ActivityEntry.EDITING, now);
        } catch (RuntimeException ex) {
            log.warn("Could not record activity for lock on {}", e.elementId(), ex);
        }
    }

    @Transactional
    public void recordAiChange(UUID projectId, UUID diagramId, UUID requestedById, String requestedByName,
            List<String> elementNames) {
        try {
            Instant now = clock.instant();
            for (String name : elementNames) {
                save(projectId, diagramId, requestedById, requestedByName, name, ActivityEntry.AI_CHANGE, now);
            }
        } catch (RuntimeException ex) {
            log.warn("Could not record AI activity", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<String> summaryLines(UUID projectId) {
        List<ActivityEntry> entries = activity.findTop40ByProjectIdOrderByAtDesc(projectId);
        Map<String, Group> people = new LinkedHashMap<>();
        Map<String, Group> ai = new LinkedHashMap<>();
        for (ActivityEntry entry : entries) {
            String who = entry.getUserName() == null ? "Alguien" : entry.getUserName();
            Map<String, Group> target = ActivityEntry.AI_CHANGE.equals(entry.getAction()) ? ai : people;
            Group group = target.computeIfAbsent(who, k -> new Group(entry.getAt()));
            group.elements.add(entry.getElementName());
        }
        List<String> lines = new ArrayList<>();
        Instant now = clock.instant();
        people.forEach((who, g) -> lines.add(
                who + ": " + String.join(", ", g.elements) + " (último cambio " + ago(g.latest, now) + ")"));
        ai.forEach((who, g) -> lines.add("IA (pedido de " + who + "): " + String.join(", ", g.elements)));
        return lines;
    }

    @Transactional(readOnly = true)
    public List<String> editingNow(UUID projectId) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<UUID, List<ElementLock>> entry : locks.snapshotAll().entrySet()) {
            Diagram diagram = diagrams.findById(entry.getKey()).orElse(null);
            if (diagram == null || !projectId.equals(diagram.getProject().getId())) continue;
            for (ElementLock lock : entry.getValue()) {
                String name = elementName(diagram.getModel(), lock.elementId());
                if (name != null) lines.add(lock.userName() + " está editando " + name);
            }
        }
        return lines;
    }

    private void save(UUID projectId, UUID diagramId, UUID userId, String userName, String element, String action,
            Instant at) {
        ActivityEntry entry = new ActivityEntry();
        entry.setProjectId(projectId);
        entry.setDiagramId(diagramId);
        entry.setUserId(userId);
        entry.setUserName(userName);
        entry.setElementName(element);
        entry.setAction(action);
        entry.setAt(at);
        activity.save(entry);
    }

    private static final class Group {
        final Instant latest;
        final Set<String> elements = new LinkedHashSet<>();

        Group(Instant latest) {
            this.latest = latest;
        }
    }

    static String ago(Instant then, Instant now) {
        long minutes = Duration.between(then, now).toMinutes();
        if (minutes < 1) return "hace menos de 1 min";
        if (minutes < 60) return "hace " + minutes + " min";
        return "hace " + (minutes / 60) + " h";
    }

    static String elementName(Map<String, Object> model, String elementId) {
        if (model == null || elementId == null) return null;
        Map<String, String> classNames = new LinkedHashMap<>();
        if (model.get("nodes") instanceof List<?> nodes) {
            for (Object o : nodes) {
                if (!(o instanceof Map<?, ?> node) || node.get("id") == null) continue;
                if (!"class".equals(node.get("type"))) continue;
                if (node.get("data") instanceof Map<?, ?> d && d.get("name") != null) {
                    classNames.put(String.valueOf(node.get("id")), String.valueOf(d.get("name")));
                }
            }
        }
        if (classNames.containsKey(elementId)) return classNames.get(elementId);
        if (model.get("edges") instanceof List<?> edges) {
            for (Object o : edges) {
                if (!(o instanceof Map<?, ?> edge) || !elementId.equals(String.valueOf(edge.get("id")))) continue;
                String source = classNames.get(String.valueOf(edge.get("source")));
                String target = classNames.get(String.valueOf(edge.get("target")));
                if (source != null && target != null) return source + "–" + target;
            }
        }
        return null;
    }
}
