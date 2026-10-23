package com.collabcase.teamai.connector;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cola en memoria de trabajos para la IA local. Un solo trabajo corre a la vez;
 * los demás esperan en orden. Los callbacks a listeners y el envío al conector
 * se hacen siempre fuera del lock.
 */
@Component
public class AiJobQueue {

    static final int MAX_JOBS = 5;
    static final Duration TIMEOUT = Duration.ofSeconds(120);
    static final String OFFLINE_REASON = "IA desconectada";
    static final String TIMEOUT_REASON = "La IA no respondió a tiempo";

    private static final class Job {
        final UUID id = UUID.randomUUID();
        final List<Map<String, String>> messages;
        final Map<String, Object> format;
        final AiJobListener listener;
        Instant startedAt;

        Job(List<Map<String, String>> messages, Map<String, Object> format, AiJobListener listener) {
            this.messages = messages;
            this.format = format;
            this.listener = listener;
        }
    }

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Job> waiting = new ArrayDeque<>();
    private Job running;
    private ConnectorLink link;
    private String model;

    @Autowired
    public AiJobQueue() {
        this(Clock.systemUTC());
    }

    public AiJobQueue(Clock clock) {
        this.clock = clock;
    }

    public void attach(ConnectorLink newLink, String newModel) {
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            if (link != null) {
                failAll(OFFLINE_REASON, actions);
            }
            link = newLink;
            model = newModel;
        } finally {
            lock.unlock();
        }
        actions.forEach(Runnable::run);
    }

    public void detach(ConnectorLink oldLink) {
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            if (link != oldLink) {
                return;
            }
            failAll(OFFLINE_REASON, actions);
            link = null;
            model = null;
        } finally {
            lock.unlock();
        }
        actions.forEach(Runnable::run);
    }

    public boolean connected() {
        lock.lock();
        try {
            return link != null;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return waiting.size() + (running != null ? 1 : 0);
        } finally {
            lock.unlock();
        }
    }

    public String model() {
        lock.lock();
        try {
            return model;
        } finally {
            lock.unlock();
        }
    }

    public UUID submit(List<Map<String, String>> messages, Map<String, Object> format, AiJobListener listener) {
        List<Runnable> actions = new ArrayList<>();
        UUID id;
        lock.lock();
        try {
            if (link == null) {
                throw new AiUnavailableException(AiUnavailableException.Reason.OFFLINE);
            }
            if (size() >= MAX_JOBS) {
                throw new AiUnavailableException(AiUnavailableException.Reason.BUSY);
            }
            Job job = new Job(messages, format, listener);
            id = job.id;
            waiting.addLast(job);
            if (running == null) {
                startNext(actions);
            } else {
                int position = waiting.size();
                actions.add(() -> listener.queued(position));
            }
        } finally {
            lock.unlock();
        }
        actions.forEach(Runnable::run);
        return id;
    }

    public void onChunk(UUID jobId, String text) {
        AiJobListener listener = null;
        lock.lock();
        try {
            if (running != null && running.id.equals(jobId)) {
                listener = running.listener;
            }
        } finally {
            lock.unlock();
        }
        if (listener != null) {
            listener.chunk(text);
        }
    }

    public void onFinished(UUID jobId, String content) {
        complete(jobId, listener -> listener.finished(content));
    }

    public void onFailed(UUID jobId, String reason) {
        complete(jobId, listener -> listener.failed(reason));
    }

    @Scheduled(fixedRate = 5_000)
    void expire() {
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            if (running != null && running.startedAt.plus(TIMEOUT).isBefore(clock.instant())) {
                AiJobListener listener = running.listener;
                running = null;
                actions.add(() -> listener.failed(TIMEOUT_REASON));
                startNext(actions);
            }
        } finally {
            lock.unlock();
        }
        actions.forEach(Runnable::run);
    }

    /** Ejecuta un trabajo y espera su resultado completo (sin streaming). */
    public String runBlocking(List<Map<String, String>> messages, Map<String, Object> format) {
        CompletableFuture<String> future = new CompletableFuture<>();
        submit(messages, format, new AiJobListener() {
            @Override public void queued(int position) { }
            @Override public void started() { }
            @Override public void chunk(String text) { }
            @Override public void finished(String content) { future.complete(content); }
            @Override public void failed(String reason) {
                future.completeExceptionally(new IllegalStateException(reason));
            }
        });
        try {
            return future.get(TIMEOUT.toMillis() * (MAX_JOBS + 1), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido mientras se esperaba a la IA", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IllegalStateException ise ? ise : new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException(TIMEOUT_REASON, e);
        }
    }

    private void complete(UUID jobId, Consumer<AiJobListener> notify) {
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            if (running == null || !running.id.equals(jobId)) {
                return;
            }
            AiJobListener listener = running.listener;
            running = null;
            actions.add(() -> notify.accept(listener));
            startNext(actions);
        } finally {
            lock.unlock();
        }
        actions.forEach(Runnable::run);
    }

    /** Debe llamarse con el lock tomado. Arranca el siguiente y renumera a los que esperan. */
    private void startNext(List<Runnable> actions) {
        if (running == null && link != null && !waiting.isEmpty()) {
            Job job = waiting.pollFirst();
            job.startedAt = clock.instant();
            running = job;
            ConnectorLink target = link;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", "job");
            payload.put("id", job.id.toString());
            payload.put("messages", job.messages);
            payload.put("format", job.format);
            payload.put("options", Map.of("num_ctx", 4096, "temperature", 0.3));
            actions.add(job.listener::started);
            actions.add(() -> target.send(payload));
            int position = 1;
            for (Job w : waiting) {
                int p = position++;
                actions.add(() -> w.listener.queued(p));
            }
        }
    }

    /** Debe llamarse con el lock tomado. */
    private void failAll(String reason, List<Runnable> actions) {
        List<Job> all = new ArrayList<>();
        if (running != null) {
            all.add(running);
        }
        all.addAll(waiting);
        running = null;
        waiting.clear();
        for (Job job : all) {
            actions.add(() -> job.listener.failed(reason));
        }
    }
}
