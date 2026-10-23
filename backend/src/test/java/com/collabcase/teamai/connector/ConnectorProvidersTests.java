package com.collabcase.teamai.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.collabcase.agent.AgentUnavailableException;
import com.collabcase.agent.ConfiguredAgentProvider;
import com.collabcase.ai.AiProviderException;
import com.collabcase.ai.AiProviderRequest;
import com.collabcase.ai.AiProviderResponse;

class ConnectorProvidersTests {

    @Test
    void aiProviderReturnsQueueContent() {
        AiJobQueue queue = mock(AiJobQueue.class);
        when(queue.runBlocking(any(), any())).thenReturn("{\"ok\":true}");
        AiProviderResponse response = new ConnectorAiProvider(queue)
                .generate(new AiProviderRequest("sys", "usr", Map.of("type", "object")));
        assertThat(response.content()).isEqualTo("{\"ok\":true}");
        assertThat(response.provider()).isEqualTo("conector");
    }

    @Test
    void aiProviderTranslatesOfflineToProviderException() {
        AiJobQueue queue = mock(AiJobQueue.class);
        when(queue.runBlocking(any(), any())).thenThrow(new AiUnavailableException(AiUnavailableException.Reason.OFFLINE));
        assertThatThrownBy(() -> new ConnectorAiProvider(queue)
                .generate(new AiProviderRequest("sys", "usr", Map.of())))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("IA desconectada");
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentProviderUsesQueueWhenConfiguredAsConector() {
        AiJobQueue queue = mock(AiJobQueue.class);
        ObjectProvider<AiJobQueue> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(queue);
        when(queue.runBlocking(any(), any())).thenReturn("{\"a\":1}");
        when(queue.connected()).thenReturn(false, true);
        ConfiguredAgentProvider agent = new ConfiguredAgentProvider("conector", "http://x", "m", "http://y", "m", "", 5, provider);

        assertThat(agent.name()).isEqualTo("conector");
        assertThat(agent.generate("sys", "usr", "{\"type\":\"object\"}")).isEqualTo("{\"a\":1}");
        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, Object>> format = ArgumentCaptor.forClass(Map.class);
        verify(queue).runBlocking(messages.capture(), format.capture());
        assertThat(messages.getValue()).containsExactly(
                Map.of("role", "system", "content", "sys"), Map.of("role", "user", "content", "usr"));
        assertThat(format.getValue()).containsEntry("type", "object");
        assertThat(agent.available()).isFalse();
        assertThat(agent.available()).isTrue();

        when(queue.runBlocking(any(), any())).thenThrow(new AiUnavailableException(AiUnavailableException.Reason.OFFLINE));
        assertThatThrownBy(() -> agent.generate("s", "u", "{}"))
                .isInstanceOf(AgentUnavailableException.class).hasMessage("IA desconectada");
    }
}
