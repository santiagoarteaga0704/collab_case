package com.collabcase.teamai.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.diagram.Diagram;
import com.collabcase.diagram.DiagramRepository;
import com.collabcase.project.Project;
import com.collabcase.project.ProjectAccessService;
import com.collabcase.project.ProjectRepository;
import com.collabcase.teamai.chat.dto.ChatDtos.MessageResponse;
import com.collabcase.user.UserRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ChatService {

    static final int HISTORY_LIMIT = 100;
    static final int MAX_LENGTH = 2000;
    private static final Pattern MENTION = Pattern.compile("(?i)(^|\\s)@ia\\b");

    private final ChatMessageRepository messages;
    private final ProjectAccessService access;
    private final DiagramRepository diagrams;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final ObjectMapper mapper;

    public ChatService(ChatMessageRepository messages, ProjectAccessService access, DiagramRepository diagrams,
            ProjectRepository projects, UserRepository users, ObjectMapper mapper) {
        this.messages = messages;
        this.access = access;
        this.diagrams = diagrams;
        this.projects = projects;
        this.users = users;
        this.mapper = mapper;
    }

    public static boolean mentionsAi(String text) {
        return text != null && MENTION.matcher(text).find();
    }

    public List<MessageResponse> history(UUID projectId, UUID userId) {
        access.requireRead(projectId, userId);
        List<ChatMessage> latest = new ArrayList<>(
                messages.visibleTo(projectId, userId, PageRequest.of(0, HISTORY_LIMIT)));
        Collections.reverse(latest);
        return latest.stream().map(this::toResponse).toList();
    }

    public ChatMessage postFromPerson(UUID projectId, UUID userId, String content, boolean isPrivate,
            UUID diagramId) {
        access.requireRead(projectId, userId);
        String text = content == null ? "" : content.trim();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El mensaje debe tener entre 1 y " + MAX_LENGTH + " caracteres");
        }
        if (diagramId != null) {
            Diagram diagram = diagrams.findById(diagramId).orElse(null);
            if (diagram == null || diagram.getProject() == null
                    || !projectId.equals(diagram.getProject().getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El diagrama no es de este proyecto");
            }
        }
        ChatMessage m = new ChatMessage();
        m.setProjectId(projectId);
        m.setAuthorId(userId);
        m.setAuthorName(users.findById(userId).map(u -> u.getName()).orElse("Alguien"));
        m.setKind(ChatMessageKind.PERSON);
        m.setPrivateOwnerId(isPrivate ? userId : null);
        m.setDiagramId(diagramId);
        m.setContent(text);
        m.setStatus(ChatMessageStatus.COMPLETE);
        m.setProposalState(ProposalState.NONE);
        return save(m);
    }

    public ChatMessage save(ChatMessage m) {
        return messages.save(m);
    }

    public MessageResponse toResponse(ChatMessage m) {
        List<AiSuggestion> proposal = null;
        if (m.getProposalJson() != null && !m.getProposalJson().isBlank()) {
            try {
                proposal = mapper.readValue(m.getProposalJson(), new TypeReference<List<AiSuggestion>>() { });
            } catch (RuntimeException e) {
                proposal = null;
            }
        }
        return new MessageResponse(m.getId(), m.getAuthorName(), m.getAuthorId(), m.getKind().name(),
                m.getPrivateOwnerId() != null, m.getDiagramId(), m.getContent(), m.getStatus().name(), proposal,
                m.getProposalState().name(), m.getRequestedByName(), m.getCreatedAt());
    }

    public HelpLevel setHelpLevel(UUID projectId, UUID userId, HelpLevel level) {
        Project project = access.requireOwner(projectId, userId).project();
        project.setHelpLevel(level);
        projects.save(project);
        return level;
    }
}
