package com.collabcase.teamai.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiJobQueueTests {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static class FakeLink implements ConnectorLink {
        final List<Map<String, Object>> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void send(Map<String, Object> payload) { sent.add(payload); }

        UUID idOf(int index) { return UUID.fromString((String) sent.get(index).get("id")); }
    }

    static class Recorder implements AiJobListener {
        final List<String> events = new ArrayList<>();

        @Override public void queued(int position) { events.add("queued(" + position + ")"); }
        @Override public void started() { events.add("started"); }
        @Override public void chunk(String text) { events.add("chunk(" + text + ")"); }
        @Override public void finished(String content) { events.add("finished(" + content + ")"); }
        @Override public void failed(String reason) { events.add("failed(" + reason + ")"); }
    }

    private static final List<Map<String, String>> MSGS = List.of(Map.of("role", "user", "content", "hola"));

    private MutableClock clock;
    private AiJobQueue queue;
    private FakeLink link;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        queue = new AiJobQueue(clock);
        link = new FakeLink();
    }

    private UUID submit(Recorder r) {
        return queue.submit(MSGS, null, r);
    }

    @Test
    void submitWithoutConnectorFailsOffline() {
        assertThatThrownBy(() -> submit(new Recorder()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("IA desconectada")
                .satisfies(e -> assertThat(((AiUnavailableException) e).reason())
                        .isEqualTo(AiUnavailableException.Reason.OFFLINE));
    }

    @Test
    void firstJobStartsImmediatelyAndOthersWait() {
        queue.attach(link, "qwen3:4b-instruct");
        Recorder r1 = new Recorder(), r2 = new Recorder(), r3 = new Recorder();
        UUID id1 = submit(r1);
        submit(r2);
        submit(r3);

        assertThat(link.sent).hasSize(1);
        Map<String, Object> job = link.sent.get(0);
        assertThat(job.get("kind")).isEqualTo("job");
        assertThat(job.get("id")).isEqualTo(id1.toString());
        assertThat(job.get("messages")).isEqualTo(MSGS);
        assertThat(job.get("format")).isNull();
        assertThat(job.get("options")).isEqualTo(Map.of("num_ctx", 4096, "temperature", 0.3));
        assertThat(r1.events).containsExactly("started");
        assertThat(r2.events).containsExactly("queued(1)");
        assertThat(r3.events).containsExactly("queued(2)");
        assertThat(queue.size()).isEqualTo(3);
        assertThat(queue.model()).isEqualTo("qwen3:4b-instruct");
        assertThat(queue.connected()).isTrue();
    }

    @Test
    void sixthJobIsRejectedBusy() {
        queue.attach(link, "m");
        for (int i = 0; i < 5; i++) {
            submit(new Recorder());
        }
        assertThatThrownBy(() -> submit(new Recorder()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage("La IA está ocupada, prueba en un momento")
                .satisfies(e -> assertThat(((AiUnavailableException) e).reason())
                        .isEqualTo(AiUnavailableException.Reason.BUSY));
    }

    @Test
    void finishingStartsTheNextAndUpdatesPositions() {
        queue.attach(link, "m");
        Recorder r1 = new Recorder(), r2 = new Recorder(), r3 = new Recorder();
        UUID id1 = submit(r1);
        UUID id2 = submit(r2);
        submit(r3);

        queue.onFinished(id1, "x");

        assertThat(r1.events).containsExactly("started", "finished(x)");
        assertThat(link.sent).hasSize(2);
        assertThat(link.idOf(1)).isEqualTo(id2);
        assertThat(r2.events).containsExactly("queued(1)", "started");
        assertThat(r3.events).containsExactly("queued(2)", "queued(1)");
        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    void chunksRouteToTheirOwnJob() {
        queue.attach(link, "m");
        Recorder r1 = new Recorder(), r2 = new Recorder();
        UUID id1 = submit(r1);
        submit(r2);

        queue.onChunk(id1, "ho");
        queue.onChunk(id1, "la");

        assertThat(r1.events).containsExactly("started", "chunk(ho)", "chunk(la)");
        assertThat(r2.events).containsExactly("queued(1)");
        assertThatCode(() -> queue.onChunk(UUID.randomUUID(), "x")).doesNotThrowAnyException();
    }

    @Test
    void timeoutCountsFromStartNotFromQueue() {
        queue.attach(link, "m");
        Recorder r1 = new Recorder(), r2 = new Recorder();
        UUID id1 = submit(r1);
        submit(r2);

        clock.advance(Duration.ofSeconds(100));
        queue.onFinished(id1, "x");
        clock.advance(Duration.ofSeconds(100));
        queue.expire();
        assertThat(r2.events).doesNotContain("failed(La IA no respondió a tiempo)");

        clock.advance(Duration.ofSeconds(21));
        queue.expire();
        assertThat(r2.events).contains("failed(La IA no respondió a tiempo)");
        assertThat(queue.size()).isZero();
    }

    @Test
    void detachFailsRunningAndQueuedJobs() {
        queue.attach(link, "m");
        Recorder r1 = new Recorder(), r2 = new Recorder();
        submit(r1);
        submit(r2);

        queue.detach(link);

        assertThat(r1.events).contains("failed(IA desconectada)");
        assertThat(r2.events).contains("failed(IA desconectada)");
        assertThat(queue.size()).isZero();
        assertThat(queue.connected()).isFalse();
    }

    @Test
    void newConnectorReplacesOld() {
        queue.attach(link, "m");
        Recorder r1 = new Recorder(), r2 = new Recorder();
        submit(r1);
        submit(r2);
        FakeLink other = new FakeLink();

        queue.attach(other, "m2");

        assertThat(r1.events).contains("failed(IA desconectada)");
        assertThat(r2.events).contains("failed(IA desconectada)");
        assertThat(link.sent).hasSize(1);
        assertThat(other.sent).isEmpty();
        assertThat(queue.connected()).isTrue();
        assertThat(queue.model()).isEqualTo("m2");

        queue.detach(link); // el viejo ya no cuenta
        assertThat(queue.connected()).isTrue();
    }

    @Test
    void runBlockingReturnsFinishedContent() throws Exception {
        queue.attach(link, "m");
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> queue.runBlocking(MSGS, null));

        long deadline = System.currentTimeMillis() + 5000;
        while (link.sent.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        queue.onFinished(link.idOf(0), "respuesta");

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("respuesta");
    }
}
