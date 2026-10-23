package com.collabcase.teamai.connector;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.collabcase.ai.AiProvider;
import com.collabcase.ai.AiProviderException;
import com.collabcase.ai.AiProviderRequest;
import com.collabcase.ai.AiProviderResponse;

/** Proveedor del asistente UML que usa la IA de la laptop a través de la cola del conector. */
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "conector")
public class ConnectorAiProvider implements AiProvider {
    private final AiJobQueue queue;

    public ConnectorAiProvider(AiJobQueue queue) {
        this.queue = queue;
    }

    @Override
    public AiProviderResponse generate(AiProviderRequest request) {
        try {
            String content = queue.runBlocking(
                    List.of(Map.of("role", "system", "content", request.systemPrompt()),
                            Map.of("role", "user", "content", request.userPrompt())),
                    request.jsonSchema());
            if (content == null || content.isBlank()) throw new IllegalStateException("Respuesta vacía de la IA");
            return new AiProviderResponse(content, "conector");
        } catch (Exception exception) {
            throw new AiProviderException(exception.getMessage() == null ? "IA desconectada" : exception.getMessage(), exception);
        }
    }
}
