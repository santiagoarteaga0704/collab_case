package com.collabcase.ai;

import java.time.Duration;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "bedrock")
public class BedrockAiProvider implements AiProvider {
    private final BedrockRuntimeClient client;
    private final String modelId;
    private final ObjectMapper objectMapper;

    public BedrockAiProvider(
            @Value("${ai.bedrock.region}") String region,
            @Value("${ai.bedrock.model-id}") String modelId,
            @Value("${ai.timeout-seconds}") long timeoutSeconds,
            ObjectMapper objectMapper) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalStateException("BEDROCK_MODEL_ID is required when AI_PROVIDER=bedrock");
        }
        this.modelId = modelId;
        this.objectMapper = objectMapper;
        this.client = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(Duration.ofSeconds(timeoutSeconds)))
                .build();
    }

    @Override
    public AiProviderResponse generate(AiProviderRequest request) {
        try {
            String schema = objectMapper.writeValueAsString(request.jsonSchema());
            Message message = Message.builder().role(ConversationRole.USER)
                    .content(ContentBlock.fromText(request.userPrompt()
                            + "\n\nSchema JSON obligatorio:\n" + schema)).build();
            ConverseRequest converseRequest = ConverseRequest.builder()
                    .modelId(modelId)
                    .system(SystemContentBlock.fromText(request.systemPrompt()))
                    .messages(message)
                    .build();
            String content = client.converse(converseRequest).output().message().content().stream()
                    .map(ContentBlock::text).filter(Objects::nonNull).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Empty Bedrock response"));
            return new AiProviderResponse(content, "bedrock");
        } catch (Exception exception) {
            throw new AiProviderException(
                    "No se pudo obtener respuesta de Amazon Bedrock. Comprueba IAM, región y acceso al modelo.",
                    exception);
        }
    }
}
