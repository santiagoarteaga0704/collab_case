package com.collabcase.teamai.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.ObjectMapper;

class AiConnectorHandlerTests {

    private final ObjectMapper json = new ObjectMapper();
    private AiJobQueue queue;
    private AiConnectorHandler handler;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        queue = new AiJobQueue();
        handler = new AiConnectorHandler(queue, json, "secreto");
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
    }

    private void say(WebSocketSession s, String text) throws Exception {
        handler.handleMessage(s, new TextMessage(text));
    }

    private void hello(String secret) throws Exception {
        say(session, "{\"kind\":\"hello\",\"secret\":\"" + secret + "\",\"model\":\"qwen3:4b-instruct\"}");
    }

    @Test
    void wrongSecretClosesWithoutAttaching() throws Exception {
        hello("malo");
        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(4001);
        assertThat(queue.connected()).isFalse();
    }

    @Test
    void blankServerSecretRejectsEveryone() throws Exception {
        handler = new AiConnectorHandler(queue, json, "");
        hello("");
        verify(session).close(any(CloseStatus.class));
        assertThat(queue.connected()).isFalse();
    }

    @Test
    void helloAttachesAndRepliesReady() throws Exception {
        hello("secreto");
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).isEqualTo("{\"kind\":\"ready\"}");
        assertThat(queue.connected()).isTrue();
        assertThat(queue.model()).isEqualTo("qwen3:4b-instruct");
    }

    @Test
    void chunkAndDoneAreForwardedToQueue() throws Exception {
        hello("secreto");
        List<String> events = new ArrayList<>();
        UUID id = queue.submit(List.of(Map.of("role", "user", "content", "hola")), null, new AiJobListener() {
            public void queued(int position) { }
            public void started() { }
            public void chunk(String text) { events.add("chunk:" + text); }
            public void finished(String content) { events.add("done:" + content); }
            public void failed(String reason) { events.add("failed:" + reason); }
        });
        say(session, "{\"kind\":\"chunk\",\"id\":\"" + id + "\",\"text\":\"ho\"}");
        say(session, "{\"kind\":\"chunk\",\"id\":\"no-es-uuid\",\"text\":\"x\"}");
        say(session, "{\"kind\":\"done\",\"id\":\"" + id + "\",\"content\":\"hola\"}");
        assertThat(events).containsExactly("chunk:ho", "done:hola");
    }

    @Test
    void closingDetaches() throws Exception {
        hello("secreto");
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(queue.connected()).isFalse();
    }

    @Test
    void messagesBeforeHelloAreIgnoredAndClose() throws Exception {
        say(session, "{\"kind\":\"chunk\",\"id\":\"" + UUID.randomUUID() + "\",\"text\":\"x\"}");
        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(4001);
        verify(session, never()).sendMessage(any());
    }

    // ---- final fix wave ----

    @Test
    void serverPingsMoreOftenThanTheConnectorWatchdog() throws Exception {
        long rate = AiConnectorHandler.class.getDeclaredMethod("ping")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).fixedRate();
        assertThat(rate).isEqualTo(20_000L).isLessThan(50_000L);
    }

    @Test
    void silentConnectorIsDetachedAfterSixtySeconds() throws Exception {
        AiJobQueueTests.MutableClock clock = new AiJobQueueTests.MutableClock();
        handler = new AiConnectorHandler(queue, json, "secreto", clock);
        hello("secreto");

        clock.advance(java.time.Duration.ofSeconds(40));
        say(session, "{\"kind\":\"pong\"}");
        clock.advance(java.time.Duration.ofSeconds(40));
        handler.ping();
        assertThat(queue.connected()).isTrue();

        clock.advance(java.time.Duration.ofSeconds(21));
        handler.ping();
        assertThat(queue.connected()).isFalse();
        verify(session).close(any(CloseStatus.class));
    }
}
