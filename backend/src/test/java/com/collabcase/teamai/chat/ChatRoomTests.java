package com.collabcase.teamai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.collabcase.project.ProjectAccessService.EffectiveRole;

import tools.jackson.databind.json.JsonMapper;

class ChatRoomTests {

    private final ChatRoom room = new ChatRoom(JsonMapper.builder().build());
    private final UUID projectId = UUID.randomUUID();
    private final UUID diagramId = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();

    private WebSocketSession join(UUID diagram) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        room.join(projectId, new ChatSession(UUID.randomUUID().toString(), projectId, luis, "Luis", diagram,
                EffectiveRole.EDITOR, socket));
        return socket;
    }

    @Test
    void applyChangesGoesToOnlyTheMostRecentTabOnThatDiagram() throws IOException {
        WebSocketSession older = join(diagramId);
        WebSocketSession newer = join(diagramId);
        WebSocketSession otherDiagram = join(UUID.randomUUID());

        assertThat(room.toUserInDiagram(projectId, luis, diagramId, Map.of("kind", "apply_changes"))).isTrue();

        verify(newer).sendMessage(any(TextMessage.class));
        verify(older, never()).sendMessage(any(TextMessage.class));
        verify(otherDiagram, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void fallsBackToAnOlderTabWhenTheNewestIsBroken() throws IOException {
        WebSocketSession older = join(diagramId);
        WebSocketSession newer = join(diagramId);
        doThrow(new IOException("gone")).when(newer).sendMessage(any(TextMessage.class));

        assertThat(room.toUserInDiagram(projectId, luis, diagramId, Map.of("kind", "apply_changes"))).isTrue();

        verify(older).sendMessage(any(TextMessage.class));
    }

    @Test
    void noTabOnTheDiagramMeansNotSent() {
        join(UUID.randomUUID());
        assertThat(room.toUserInDiagram(projectId, luis, diagramId, Map.of("kind", "apply_changes"))).isFalse();
    }
}
