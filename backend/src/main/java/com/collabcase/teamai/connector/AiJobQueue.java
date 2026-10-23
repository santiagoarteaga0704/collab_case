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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AiJobQueue.class);

    static final int MAX_JOBS = 5;
    static final Duration TIMEOUT = Duration.ofSeconds(120);
    static final String OFFLINE_REASON = "IA desconectada";
    static final String TIMEOUT_REASON = "La IA no respondió a tiempo";
    /**
     * Lo que espera {@link #runBlocking}: menos que los 60 s de CloudFront, para que el panel de
     * análisis y el agente reciban un error claro en vez de un 504. El chat del equipo no espera y
     * mantiene {@link #TIMEOUT}.
     */
    static final Duration BLOCKING_WAIT = Duration.ofSeconds(55);

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
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private volatile Duration blockingWait = BLOCKING_WAIT;

    @Autowired
    public AiJobQueue() {
        this(Clock.systemUTC());
    }

    public AiJobQueue(Clock clock) {
        this.clock = clock;
    }

    /** Solo para pruebas: cuánto espera {@link #runBlocking}. */
    void blockingWait(Duration wait) {
        this.blockingWait = wait;
    }

    /** Registra un listener que se avisa cuando cambia el estado (conexión, cola). Se llama fuera del lock. */
    public void onChange(Runnable listener) {
        changeListeners.add(listener);
    }

    private void fireChange() {
        for (Runnable l : changeListeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                log.warn("Falló un listener de cambios de la cola de IA", e);
            }
        }
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
        actions.add(this::fireChange);
        runAll(actions);
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
        actions.add(this::fireChange);
        runAll(actions);
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
        actions.add(this::fireChange);
        runAll(actions);
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
                actions.add(this::fireChange);
            }
        } finally {
            lock.unlock();
        }
        runAll(actions);
    }

    /**
     * Ejecuta un trabajo y espera su resultado completo (sin streaming), como mucho
     * {@link #BLOCKING_WAIT}. Si se rinde, el trabajo sale de la cola o, si ya corría, se cancela
     * en el conector y libera el turno.
     */
    public String runBlocking(List<Map<String, String>> messages, Map<String, Object> format) {
        CompletableFuture<String> future = new CompletableFuture<>();
        UUID id = submit(messages, format, new AiJobListener() {
            @Override public void queued(int position) { }
            @Override public void started() { }
            @Override public void chunk(String text) { }
            @Override public void finished(String content) { future.complete(content); }
            @Override public void failed(String reason) {
                future.completeExceptionally(new IllegalStateException(reason));
            }
        });
        try {
            return future.get(blockingWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            abandon(id);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido mientras se esperaba a la IA", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IllegalStateException ise ? ise : new IllegalStateException(cause);
        } catch (TimeoutException e) {
            abandon(id);
            throw new IllegalStateException(TIMEOUT_REASON, e);
        }
    }

    /** Quien esperaba ya no quiere el resultado: saca el trabajo de la cola o lo cancela si corre. */
    private void abandon(UUID jobId) {
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            if (running != null && running.id.equals(jobId)) {
                running = null;
                ConnectorLink target = link;
                if (target != null) {
                    Map<String, Object> cancel = new LinkedHashMap<>();
                    cancel.put("kind", "cancel");
                    cancel.put("id", jobId.toString());
                    actions.add(() -> target.send(cancel));
                }
                startNext(actions);
            } else if (waiting.removeIf(job -> job.id.equals(jobId))) {
                int position = 1;
                for (Job w : waiting) {
                    int p = position++;
                    actions.add(() -> w.listener.queued(p));
                }
            } else {
                return;
            }
        } finally {
            lock.unlock();
        }
        actions.add(this::fireChange);
        runAll(actions);
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
        actions.add(this::fireChange);
        runAll(actions);
    }

    /** Ejecuta cada acción aislada: si una falla, las demás se ejecutan igual. */
    private static void runAll(List<Runnable> actions) {
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Falló un callback de la cola de IA", e);
            }
        }
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
            actions.add(() -> {
                try {
                    target.send(payload);
                } catch (RuntimeException e) {
                    log.warn("No se pudo enviar el trabajo {} al conector", job.id, e);
                    onFailed(job.id, OFFLINE_REASON);
                }
            });
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
