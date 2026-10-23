package com.collabcase.teamai.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.collabcase.project.ProjectAccessService;
import com.collabcase.teamai.chat.dto.ChatDtos.HelpLevelRequest;
import com.collabcase.teamai.chat.dto.ChatDtos.MessageResponse;
import com.collabcase.teamai.chat.dto.ChatDtos.StatusResponse;
import com.collabcase.teamai.connector.AiJobQueue;
import com.collabcase.user.User;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects/{projectId}/chat")
public class ChatController {

    private final ChatService chat;
    private final ProjectAccessService access;
    private final AiJobQueue queue;
    private final ChatRoom room;

    public ChatController(ChatService chat, ProjectAccessService access, AiJobQueue queue, ChatRoom room) {
        this.room = room;
        this.chat = chat;
        this.access = access;
        this.queue = queue;
    }

    @GetMapping
    public List<MessageResponse> history(@PathVariable UUID projectId, @AuthenticationPrincipal User user) {
        return chat.history(projectId, user.getId());
    }

    @GetMapping("/status")
    public StatusResponse status(@PathVariable UUID projectId, @AuthenticationPrincipal User user) {
        var project = access.requireRead(projectId, user.getId()).project();
        return new StatusResponse(queue.connected(), queue.size(), project.effectiveHelpLevel().name());
    }

    @PutMapping("/help-level")
    public StatusResponse setHelpLevel(@PathVariable UUID projectId, @Valid @RequestBody HelpLevelRequest request,
            @AuthenticationPrincipal User user) {
        HelpLevel level = chat.setHelpLevel(projectId, user.getId(), request.level());
        room.toAll(projectId, java.util.Map.of("kind", "help_level", "level", level.name()));
        return new StatusResponse(queue.connected(), queue.size(), level.name());
    }
}
