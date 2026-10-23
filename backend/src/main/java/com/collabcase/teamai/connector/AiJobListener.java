package com.collabcase.teamai.connector;

/** Recibe el avance de un trabajo de IA. */
public interface AiJobListener {
    void queued(int position);

    void started();

    void chunk(String text);

    void finished(String content);

    void failed(String reason);
}
