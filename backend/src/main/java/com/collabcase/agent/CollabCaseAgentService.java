package com.collabcase.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.collabcase.agent.dto.AgentDtos.Action;
import com.collabcase.agent.dto.AgentDtos.ChatRequest;
import com.collabcase.agent.dto.AgentDtos.ChatResponse;
import com.collabcase.agent.dto.AgentDtos.Context;
import com.collabcase.agent.dto.AgentDtos.Status;
import com.collabcase.project.ProjectAccessService;
import tools.jackson.databind.ObjectMapper;

@Service
public class CollabCaseAgentService {
    private static final Logger log = LoggerFactory.getLogger(CollabCaseAgentService.class);
    private final AgentProvider provider;
    private final AgentActionRegistry registry;
    private final ProjectAccessService access;
    private final ObjectMapper json;
    private final String guide;

    public CollabCaseAgentService(AgentProvider provider, AgentActionRegistry registry,
            ProjectAccessService access, ObjectMapper json) {
        this.provider = provider;
        this.registry = registry;
        this.access = access;
        this.json = json;
        try (InputStream in = new ClassPathResource("agent/collab-case-guide.md").getInputStream()) {
            guide = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo cargar la guía del agente", exception);
        }
    }

    public ChatResponse chat(ChatRequest request, UUID userId) {
        String verifiedRole = verify(request.context(), userId);
        String system = """
            Eres el Agente de Ayuda de Collab CASE, separado del asistente IA UML.
            Enseña a usar la aplicación o propone acciones exclusivamente del registro disponible.
            No inventes botones, endpoints ni funcionalidades. Usa GUIDE para explicar y ACTION si
            el usuario pide ejecutar algo. Nunca propongas acciones destructivas. Responde solo JSON
            con type, message y actions. Cada action contiene name, arguments y confirmationRequired.
            Contratos canónicos obligatorios:
            CREATE_PROJECT {name}; CREATE_VERSION {name}; CREATE_DIAGRAM {name};
            RENAME_PROJECT {name}; RENAME_DIAGRAM {name}; ADD_CLASS {name};
            ADD_ATTRIBUTE {name}; ADD_METHOD {name}.
            En esos contratos `name` es el nombre visible solicitado por el usuario y nunca puede ser null o vacío.
            No uses diagramName, projectName, versionName o title como sustituto de name para el objeto creado.
            """ + "\nACCIONES: " + registry.names() + "\nROL VERIFICADO: " + verifiedRole
                + "\nMANUAL:\n" + guide;
        List<Map<String, String>> history = request.history() == null ? List.of()
                : request.history().stream().skip(Math.max(0, request.history().size() - 6)).toList();
        String input = json.writeValueAsString(Map.of(
                "message", request.message(),
                "context", request.context() == null ? Map.of() : request.context(),
                "history", history));
        String schema = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"type\":{\"type\":\"string\",\"enum\":[\"GUIDE\",\"ACTION\"]},"
                + "\"message\":{\"type\":\"string\"},\"actions\":{\"type\":\"array\",\"items\":{"
                + "\"oneOf\":[{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"name\":{\"enum\":[\"CREATE_PROJECT\",\"CREATE_VERSION\",\"CREATE_DIAGRAM\",\"RENAME_PROJECT\",\"RENAME_DIAGRAM\",\"ADD_CLASS\",\"ADD_ATTRIBUTE\",\"ADD_METHOD\"]},"
                + "\"arguments\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1,\"description\":\"Nombre visible solicitado por el usuario\"}},\"required\":[\"name\"]},"
                + "\"confirmationRequired\":{\"type\":\"boolean\"}},\"required\":[\"name\",\"arguments\",\"confirmationRequired\"]},"
                + "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"name\":{\"enum\":[\"NAVIGATE\",\"SWITCH_VERSION\",\"ADD_RELATIONSHIP\",\"SET_MULTIPLICITY\",\"GENERATE_SEQUENCE\",\"EXPORT_JSON\",\"EXPORT_SQL\",\"EXPORT_PLANTUML\",\"GENERATE_BACKEND\",\"OPEN_AI_PANEL\"]},\"arguments\":{\"type\":\"object\"},\"confirmationRequired\":{\"type\":\"boolean\"}},\"required\":[\"name\",\"arguments\",\"confirmationRequired\"]}]}}},"
                + "\"required\":[\"type\",\"message\",\"actions\"]}";
        try {
            String raw = provider.generate(system, input, schema).trim();
            log.debug("[AGENT] rawResponse={}", raw);
            if (raw.startsWith("```")) raw = raw.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            ChatResponse parsed = json.readValue(raw, ChatResponse.class);
            log.debug("[AGENT] parsedAction={}", parsed.actions());
            if (parsed.actions() != null) parsed.actions().forEach(action ->
                    log.debug("[AGENT] actionArguments={} {}", action.name(), action.arguments()));
            if (!Set.of("GUIDE", "ACTION").contains(parsed.type())) throw new IllegalArgumentException();
            List<Action> actions = registry.validate(parsed.actions(), verifiedRole);
            return new ChatResponse(actions.isEmpty() ? "GUIDE" : parsed.type(), parsed.message(), actions);
        } catch (ResponseStatusException | AgentUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "El agente devolvió una respuesta inválida", exception);
        }
    }

    public Status status() { return new Status(provider.name(), provider.available()); }
    private String verify(Context context, UUID userId) {
        if (context == null || context.projectId() == null) return "USER";
        return access.requireRead(context.projectId(), userId).role().name();
    }
}
