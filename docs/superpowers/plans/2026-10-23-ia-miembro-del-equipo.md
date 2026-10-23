# La IA como miembro del equipo — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un chat de equipo por proyecto donde una IA local (qwen3:4b-instruct en la laptop, vía un conector) responde a quien pregunta, modera y dibuja en el diagrama según el nivel de ayuda; todo desplegado en AWS.

**Architecture:** El backend Spring Boot suma un paquete `com.collabcase.teamai` con cuatro piezas: la cola de trabajos de IA y el endpoint WebSocket al que se engancha el conector de la laptop; el chat persistente con su canal en vivo; el asistente que arma el prompt, transmite la respuesta y traduce los cambios al formato `AiSuggestion` que ya existe; y la bitácora de actividad para el moderador. El frontend suma un panel `TeamChat` que en el editor aplica los cambios con el `applyAiSuggestion` existente (Yjs los propaga). El conector es un único archivo Node 22.

**Tech Stack:** Spring Boot 4.1.1, Java 21, JPA (`ddl-auto=update`), Spring WebSocket, Jackson 3 (`tools.jackson.*`), JUnit 5 + AssertJ + Mockito, H2 en pruebas; React 19 + Vite 8 + Apollon 5.3, `node --test`; Node 22 (conector); Ollama 0.40.9.

**Spec:** `docs/superpowers/specs/2026-10-23-ia-miembro-del-equipo-design.md`

## Global Constraints

- Modelo: `qwen3:4b-instruct`; opciones `num_ctx` 4096, `temperature` 0.3.
- Cola: máximo 5 trabajos entre el que corre y los que esperan; el 6º se rechaza con "La IA está ocupada, probá en un momento".
- Timeout: 120 s contados desde que el trabajo **empieza** a correr. Texto al vencer: "La IA no respondió a tiempo".
- Sin conector enganchado: "IA desconectada".
- Mención: `(?i)(^|\s)@ia\b` (misma regla en Java y JS). `@IA resumen` es el único comando fijo.
- Niveles: `EXPLAIN` / `SUGGEST` / `DRAW` (en pantalla: Explicar / Sugerir / Dibujar); por omisión `DRAW`; solo el OWNER lo cambia.
- La IA nunca renombra, borra ni mueve. Cambios permitidos = los de `SuggestionType` salvo `WARNING`/`IMPROVEMENT`.
- La IA nunca dibuja a pedido de un VIEWER ni desde fuera del editor (sin `diagramId`).
- Aviso público al aplicar: `IA agregó <nombres separados por coma> (pedido de <nombre>)`.
- Secreto del conector: variable `IA_CONECTOR_SECRETO` en los dos lados; vacío en el servidor = endpoint cerrado.
- Identificadores en inglés como el resto del código; textos visibles en castellano con acentos.
- Jackson 3: `tools.jackson.databind.*`. Pruebas backend con el perfil `test` (H2), sin Docker.

## Review Focus

1. **Respuesta parcial o JSON roto del modelo** (corta a la mitad, comillas sin cerrar): el mensaje queda con lo que alcanzó a escribirse y estado FAILED, nunca colgado en "escribiendo". → Task 7 (`streamerToleratesTruncatedJson`) y Task 9 (`brokenJsonEndsAsFailedNotStuck`).
2. **El conector se desconecta a mitad de un trabajo**: el trabajo en curso termina en FAILED con "IA desconectada" y los que esperan también; no quedan en cola para siempre. → Task 1 (`detachFailsRunningAndQueuedJobs`).
3. **Dos personas preguntan casi a la vez**: cada una ve su posición en la cola y las respuestas no se cruzan (cada fragmento va al mensaje correcto). → Task 1 (`chunksRouteToTheirOwnJob`) y Task 10 (`chunksForDifferentMessagesDoNotMix`).
4. **Mensaje privado**: nunca llega a otra sesión, ni por el historial ni por el canal en vivo, ni sus fragmentos. → Task 5 (`privateMessagesAreHiddenFromOthers`) y Task 6 (`privateChunksOnlyReachOwner`).
5. **La IA nombra una clase que no existe o repite una existente**: el validador la descarta y el resto del cambio se aplica. → Task 7 (`relationToUnknownClassIsDropped`).

---

## File Structure

```
backend/src/main/java/com/collabcase/teamai/
  connector/AiJobQueue.java          cola, de a uno, timeout, enrutado de fragmentos
  connector/AiJobListener.java       callbacks de un trabajo
  connector/ConnectorLink.java       cómo se le manda algo al conector (fácil de simular)
  connector/AiConnectorHandler.java  WebSocket /ws/ai-connector
  connector/AiUnavailableException.java  (OFFLINE | BUSY)
  connector/ConnectorAiProvider.java     ai.provider=conector
  chat/HelpLevel.java  chat/ChatMessage.java  chat/ChatMessageKind.java  chat/ChatMessageStatus.java
  chat/ProposalState.java  chat/ChatMessageRepository.java  chat/ChatService.java
  chat/ChatController.java  chat/dto/ChatDtos.java
  chat/ChatRoom.java  chat/ChatWebSocketHandler.java
  assistant/MessageFieldStreamer.java  assistant/ChangeTranslator.java  assistant/TeamAiReply.java
  assistant/TeamPromptBuilder.java  assistant/TeamAssistant.java
  activity/ActivityEntry.java  activity/ActivityRepository.java  activity/ActivityService.java
  activity/ElementLockGrantedEvent.java
conector-ia/conector.mjs  conector-ia/conector.test.mjs  conector-ia/README.md
frontend/src/api/chatApi.js
frontend/src/services/chatState.js  chatState.test.js  chatSocket.js  teamChanges.js  teamChanges.test.js
frontend/src/components/TeamChat.jsx  TeamChat.css
Dockerfile  compose.aws.yml  docs/despliegue-aws.md
backend/src/main/java/com/collabcase/config/SpaForwardController.java
```

Modificados: `Project.java`, `ElementLockManager.java`, `CollaborationWebSocketHandler.java`, `CollaborationWebSocketConfig.java` (o config propia), `ConfiguredAgentProvider.java`, `application.properties`, `application-aws.properties`, `application-test.properties`, `DiagramEditor.jsx`, `ProjectDetail.jsx`.

---

### Task 1: Cola de trabajos de IA

**Files:**
- Create: `backend/src/main/java/com/collabcase/teamai/connector/{AiJobQueue,AiJobListener,ConnectorLink,AiUnavailableException}.java`
- Test: `backend/src/test/java/com/collabcase/teamai/connector/AiJobQueueTests.java`

**Interfaces:**
- Produces:
  - `interface ConnectorLink { void send(Map<String,Object> payload); }`
  - `interface AiJobListener { void queued(int position); void started(); void chunk(String text); void finished(String content); void failed(String reason); }`
  - `class AiUnavailableException extends RuntimeException { enum Reason { OFFLINE, BUSY } Reason reason(); }` — mensajes "IA desconectada" / "La IA está ocupada, probá en un momento".
  - `@Component class AiJobQueue` con `static final int MAX_JOBS = 5`, `static final Duration TIMEOUT = Duration.ofSeconds(120)`, y:
    - `void attach(ConnectorLink link, String model)` / `void detach(ConnectorLink link)`
    - `boolean connected()`, `int size()`, `String model()`
    - `UUID submit(List<Map<String,String>> messages, Map<String,Object> format, AiJobListener listener)` — lanza `AiUnavailableException`
    - `void onChunk(UUID jobId, String text)`, `void onFinished(UUID jobId, String content)`, `void onFailed(UUID jobId, String reason)`
    - `@Scheduled(fixedRate = 5_000) void expire()` — usa un `Clock` inyectable (constructor `AiJobQueue(Clock clock)`; el bean usa `Clock.systemUTC()`)
    - `String runBlocking(List<Map<String,String>> messages, Map<String,Object> format)` — espera `TIMEOUT` + tiempo en cola; lanza `AiUnavailableException` o `IllegalStateException` con el motivo.
  - Mensaje que se envía al conector al arrancar un trabajo: `{"kind":"job","id":"<uuid>","messages":[...],"format":{...}|null,"options":{"num_ctx":4096,"temperature":0.3}}`.

- [ ] **Step 1: Write the failing tests** (`AiJobQueueTests`, con un `ConnectorLink` falso que guarda lo enviado y un `Clock` mutable)
  - `submitWithoutConnectorFailsOffline`: `assertThatThrownBy(() -> queue.submit(...)).isInstanceOf(AiUnavailableException.class)` y `reason()==OFFLINE`.
  - `firstJobStartsImmediatelyAndOthersWait`: 3 submits → el link recibió 1 solo `job`; listeners: #1 `started()`, #2 `queued(1)`, #3 `queued(2)`.
  - `sixthJobIsRejectedBusy`: 5 aceptados, el 6º lanza con `BUSY`.
  - `finishingStartsTheNextAndUpdatesPositions`: `onFinished(id1,"x")` → #1 `finished("x")`, link recibe `job` de #2, #3 recibe `queued(1)`.
  - `chunksRouteToTheirOwnJob`: `onChunk(id1,"ho")`,`onChunk(id1,"la")` → solo listener #1 recibe `"ho"`,`"la"`; un `onChunk` con id desconocido no lanza.
  - `timeoutCountsFromStartNotFromQueue`: #1 corre, #2 espera; avanzar reloj 100 s, `onFinished(id1)`; avanzar 100 s más, `expire()` → #2 NO falló; avanzar 21 s, `expire()` → #2 `failed("La IA no respondió a tiempo")`.
  - `detachFailsRunningAndQueuedJobs`: 2 trabajos, `detach(link)` → ambos `failed("IA desconectada")`, `size()==0`, `connected()==false`.
  - `newConnectorReplacesOld`: `attach(b)` con `a` enganchado → trabajos en curso fallan con "IA desconectada" y `a` ya no recibe nada.
  - `runBlockingReturnsFinishedContent`: en otro hilo `onFinished` → devuelve el contenido.
- [ ] **Step 2: Run** `cd backend && ./mvnw -q test -Dtest=AiJobQueueTests` → FAIL (no compila).
- [ ] **Step 3: Implement** `AiJobQueue`. Un `ReentrantLock` protege la cola (`ArrayDeque<Job>`) y el trabajo en curso; los callbacks a listeners se llaman **fuera** del lock. `runBlocking` usa un `CompletableFuture<String>` interno.
- [ ] **Step 4: Run** el mismo comando → PASS.
- [ ] **Step 5: Commit** `git commit -m "Agrega la cola de trabajos de la IA local"`

### Task 2: Endpoint del conector

**Files:**
- Create: `backend/src/main/java/com/collabcase/teamai/connector/AiConnectorHandler.java`
- Modify: `backend/src/main/java/com/collabcase/collaboration/CollaborationWebSocketConfig.java` (registrar `/ws/ai-connector` sin `setAllowedOrigins` restrictivo: el conector no es navegador → `setAllowedOriginPatterns("*")`), `backend/src/main/resources/application.properties` (+ `teamai.connector.secret=${IA_CONECTOR_SECRETO:}`), `application-test.properties` (`teamai.connector.secret=secreto-de-prueba`)
- Test: `backend/src/test/java/com/collabcase/teamai/connector/AiConnectorHandlerTests.java`

**Interfaces:**
- Consumes: `AiJobQueue` (Task 1).
- Produces: protocolo del conector.
  - conector → servidor: `{"kind":"hello","secret":"…","model":"qwen3:4b-instruct"}`, `{"kind":"chunk","id","text"}`, `{"kind":"done","id","content"}`, `{"kind":"error","id","detail"}`, `{"kind":"pong"}`
  - servidor → conector: `{"kind":"ready"}`, `{"kind":"job",…}` (Task 1), `{"kind":"ping"}` cada 20 s
  - secreto incorrecto o vacío en el servidor → cerrar con `CloseStatus(4001,"Bad secret")` sin `attach`.
  - Comparación del secreto con `MessageDigest.isEqual`.

- [ ] **Step 1: Write the failing tests** (handler instanciado a mano con `AiJobQueue` real y `WebSocketSession` de Mockito)
  - `wrongSecretClosesWithoutAttaching`: hello con secreto malo → `session.close(4001…)`, `queue.connected()==false`.
  - `blankServerSecretRejectsEveryone`: handler con secreto `""` → cierra aunque el hello traiga `""`.
  - `helloAttachesAndRepliesReady`: → envía `{"kind":"ready"}`, `queue.connected()`, `queue.model()=="qwen3:4b-instruct"`.
  - `chunkAndDoneAreForwardedToQueue`: tras hello, submit un trabajo; mensajes `chunk`/`done` con ese id → listener recibe texto y contenido.
  - `closingDetaches`: `afterConnectionClosed` → `queue.connected()==false`.
  - `messagesBeforeHelloAreIgnoredAndClose`: un `chunk` sin hello → cierra con 4001.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=AiConnectorHandlerTests` → FAIL.
- [ ] **Step 3: Implement** `AiConnectorHandler extends TextWebSocketHandler`; el `ConnectorLink` envuelve la sesión con `ConcurrentWebSocketSessionDecorator` (varios hilos escriben). El ping va en un `@Scheduled(fixedRate = 20_000)`.
- [ ] **Step 4: Run** `./mvnw -q test` (todo el backend) → PASS, 88 previas + nuevas.
- [ ] **Step 5: Commit** `"Agrega el endpoint donde se engancha el conector de la laptop"`

### Task 3: El conector (laptop)

**Files:**
- Create: `conector-ia/conector.mjs`, `conector-ia/conector.test.mjs`, `conector-ia/README.md`

**Interfaces:**
- Consumes: protocolo de Task 2.
- Produces: `export function startConnector({ serverUrl, secret, ollamaUrl, model, log }) → { stop(): Promise<void> }` y ejecución directa con variables `SERVIDOR_WS` (p. ej. `wss://<dominio>/ws/ai-connector`), `IA_CONECTOR_SECRETO`, `OLLAMA_URL` (por omisión `http://localhost:11434`), `MODELO` (por omisión `qwen3:4b-instruct`).
- Por cada `job`: `POST {ollamaUrl}/api/chat` con `{model, messages, stream:true, format (si viene), options}`; cada línea NDJSON con `message.content` no vacío → `chunk`; al `done:true` → `done` con todo el texto acumulado; error HTTP o de red → `error`. Atiende de a un trabajo (el servidor ya serializa).
- Reconexión: espera 1 s, 2 s, 4 s… tope 15 s; al reconectar vuelve a mandar `hello`. Responde `pong` a `ping`.
- Al arrancar verifica `GET {ollamaUrl}/api/tags` y que el modelo esté; si no, imprime "Falta el modelo: ollama pull qwen3:4b-instruct" y sale con código 1.
- Sin dependencias: `WebSocket` global y `fetch` de Node 22.

- [ ] **Step 1: Write the failing tests** (`node --test`, con un servidor HTTP falso de Ollama y un servidor WS falso hecho con `node:http` + upgrade manual **o** sin WS: inyectar `WebSocketImpl` en `startConnector` para usar un doble). Elegir la inyección: `startConnector({ ..., WebSocketImpl = globalThis.WebSocket, fetchImpl = fetch })`.
  - `sendsHelloWithSecretAndModel`
  - `streamsChunksThenDone`: Ollama falso devuelve 3 líneas NDJSON (`"Ho"`,`"la"`, done) → se envían `chunk Ho`, `chunk la`, `done "Hola"`.
  - `passesFormatAndOptionsToOllama`: el cuerpo recibido trae `format`, `options.num_ctx==4096`, `stream:true`.
  - `ollamaErrorBecomesErrorMessage`: Ollama responde 500 → `{"kind":"error","id",…}`.
  - `reconnectsWithBackoffAndSaysHelloAgain`
  - `answersPingWithPong`
- [ ] **Step 2: Run** `node --test conector-ia/` → FAIL.
- [ ] **Step 3: Implement** `conector.mjs`; el README explica en 5 pasos cómo arrancarlo (`ollama pull`, variables, `node conector.mjs`) y que se deja la laptop enchufada.
- [ ] **Step 4: Run** `node --test conector-ia/` → PASS.
- [ ] **Step 5: Commit** `"Agrega el conector que une la IA de la laptop con el servidor"`

### Task 4: La IA existente también por el conector

**Files:**
- Create: `backend/src/main/java/com/collabcase/teamai/connector/ConnectorAiProvider.java`
- Modify: `backend/src/main/java/com/collabcase/agent/ConfiguredAgentProvider.java`, `backend/src/main/resources/application-aws.properties` (`ai.provider=${AI_PROVIDER:conector}`, `agent.provider=${AGENT_PROVIDER:conector}`; voz e imagen quedan en `groq`), `application.properties` (modelos Ollama por omisión `qwen3:4b-instruct`)
- Test: `backend/src/test/java/com/collabcase/teamai/connector/ConnectorProvidersTests.java`

**Interfaces:**
- Consumes: `AiJobQueue.runBlocking`, `AiJobQueue.connected()`.
- Produces: `@ConditionalOnProperty(name="ai.provider", havingValue="conector") class ConnectorAiProvider implements AiProvider` → `AiProviderResponse(content, "conector")`; errores → `AiProviderException("IA desconectada…")`. En `ConfiguredAgentProvider`, rama `"conector"`: `generate` arma `[system, user]` y llama `runBlocking` con el schema parseado; `available()` = `queue.connected()`; `name()` = `"conector"`. Inyectar `AiJobQueue` por `ObjectProvider<AiJobQueue>` para no tocar las pruebas existentes que construyen el agente a mano.

- [ ] **Step 1: Write the failing tests**
  - `aiProviderReturnsQueueContent` (queue mockeada → `"{…}"` y proveedor `"conector"`)
  - `aiProviderTranslatesOfflineToProviderException`
  - `agentProviderUsesQueueWhenConfiguredAsConector` (y `available()` refleja `connected()`)
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ConnectorProvidersTests` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `"Permite que el asistente UML y el agente usen la IA de la laptop"`

### Task 5: Chat persistente y nivel de ayuda

**Files:**
- Create: `teamai/chat/{HelpLevel,ChatMessage,ChatMessageKind,ChatMessageStatus,ProposalState,ChatMessageRepository,ChatService,ChatController}.java`, `teamai/chat/dto/ChatDtos.java`
- Modify: `backend/src/main/java/com/collabcase/project/Project.java`
- Test: `backend/src/test/java/com/collabcase/teamai/chat/ChatServiceTests.java` (`@DataJpaTest` o `@SpringBootTest` con perfil `test`, como `RenameServiceTests`)

**Interfaces:**
- Produces:
  - `enum HelpLevel { EXPLAIN, SUGGEST, DRAW }`; en `Project`: `@Enumerated(STRING) @Column(name="help_level", length=16) private HelpLevel helpLevel;` + `public HelpLevel effectiveHelpLevel()` que devuelve `DRAW` si es null. **Nullable a propósito**: `ddl-auto=update` no puede agregar una columna NOT NULL a una tabla con filas.
  - `enum ChatMessageKind { PERSON, AI, NOTICE }`, `enum ChatMessageStatus { COMPLETE, QUEUED, WRITING, FAILED }`, `enum ProposalState { NONE, PENDING, APPLIED }`
  - `@Entity @Table(name="chat_messages") ChatMessage`: `UUID id`; `UUID projectId`; `UUID authorId` (null en IA/aviso); `String authorName`; `ChatMessageKind kind`; `UUID privateOwnerId` (null = público); `UUID diagramId`; `@Column(columnDefinition="text") String content`; `ChatMessageStatus status`; `@Column(name="proposal_json", columnDefinition="text") String proposalJson`; `ProposalState proposalState`; `UUID requestedById`; `String requestedByName`; `Instant createdAt` (`@PrePersist`). Índice por `(project_id, created_at)`.
  - `ChatMessageRepository`: `@Query("select m from ChatMessage m where m.projectId=:p and (m.privateOwnerId is null or m.privateOwnerId=:u) order by m.createdAt desc") List<ChatMessage> visibleTo(UUID p, UUID u, Pageable page)`
  - `ChatDtos.MessageResponse(UUID id, String authorName, UUID authorId, String kind, boolean isPrivate, UUID diagramId, String content, String status, List<AiSuggestion> proposal, String proposalState, String requestedByName, Instant createdAt)`; `HelpLevelRequest(@NotNull HelpLevel level)`; `StatusResponse(boolean aiConnected, int queued, String helpLevel)`
  - `ChatService`:
    - `List<MessageResponse> history(UUID projectId, UUID userId)` — últimos 100 visibles, en orden ascendente; exige `requireRead`.
    - `ChatMessage postFromPerson(UUID projectId, UUID userId, String content, boolean isPrivate, UUID diagramId)` — contenido recortado, 1..2000 caracteres (si no, 400); si `diagramId` no es del proyecto → 400.
    - `ChatMessage save(ChatMessage m)`, `MessageResponse toResponse(ChatMessage m)`
    - `HelpLevel setHelpLevel(UUID projectId, UUID userId, HelpLevel level)` — `requireOwner`.
    - `static boolean mentionsAi(String text)` con el patrón de Global Constraints.
  - `ChatController`: `GET /api/projects/{projectId}/chat` → `List<MessageResponse>`; `GET /api/projects/{projectId}/chat/status` → `StatusResponse`; `PUT /api/projects/{projectId}/chat/help-level` → `StatusResponse`. El `PUT` además avisa por el canal (Task 6 agrega esa llamada).

- [ ] **Step 1: Write the failing tests**
  - `historyIsChronologicalAndCappedAt100`
  - `privateMessagesAreHiddenFromOthers`: Ana publica privado, Luis no lo ve en `history`, Ana sí.
  - `outsiderGets404` (usuario no miembro → `ResponseStatusException` 404, como `requireRead`).
  - `blankOrTooLongMessageIsRejected`
  - `onlyOwnerChangesHelpLevel` (EDITOR → 403) y `helpLevelDefaultsToDraw` (proyecto existente con null → `DRAW`).
  - `mentionsAiMatchesOnlyTheMention`: `"@IA hola"`, `"oye @ia"`, `"@Ia resumen"` → true; `"email@ia.com"`, `"IA ayuda"`, `"@iacosa"` → false.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ChatServiceTests` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `"Agrega el chat del equipo con historial y nivel de ayuda"`

### Task 6: Canal en vivo del chat

**Files:**
- Create: `teamai/chat/ChatRoom.java`, `teamai/chat/ChatWebSocketHandler.java`
- Modify: `CollaborationWebSocketConfig.java` (registrar `/ws/chat/{projectId}` con los mismos orígenes permitidos), `ChatController.java` (el `PUT` difunde `help_level`)
- Test: `backend/src/test/java/com/collabcase/teamai/chat/ChatWebSocketHandlerTests.java`

**Interfaces:**
- Consumes: `ChatService` (Task 5), `JwtService.extractUserId`, `UserService.findById`, `ProjectAccessService.requireRead`, `AiJobQueue` (Task 1) para `ai_status`.
- Produces:
  - `ChatRoom`: `void join(UUID projectId, ChatSession s)`, `void leave(String sessionId)`, `void publish(ChatMessage m, Map<String,Object> payload)` (si `m.privateOwnerId` no es null, solo a sesiones de ese usuario), `void toAll(UUID projectId, Map<String,Object> payload)`, `boolean toUserInDiagram(UUID projectId, UUID userId, UUID diagramId, Map<String,Object> payload)`, `void toUser(UUID projectId, UUID userId, Map<String,Object> payload)`. `record ChatSession(String id, UUID projectId, UUID userId, String userName, UUID diagramId, WebSocketSession socket)`.
  - Cliente → servidor: `{"kind":"auth","token","diagramId"?}` (primero, como en colaboración), `{"kind":"send","content","private","diagramId"?}`, `{"kind":"changes_applied","messageId","applied":[nombres],"failed":[{"name","reason"}]}`, `{"kind":"apply_proposal","messageId"}`.
  - Servidor → cliente: `auth_ok`, `message {message}`, `message_updated {message}`, `chunk {messageId,text}`, `queue_position {messageId,position}`, `ai_status {connected,queued}` (al entrar y cada vez que cambia: suscribir con un `Runnable` que `AiJobQueue` llama en attach/detach/submit/fin — agregar `void onChange(Runnable r)` a `AiJobQueue`), `help_level {level}`, `apply_changes {messageId, diagramId, suggestions}`, `error {message}`.
  - Al recibir `send`: `postFromPerson` → `publish`; si `mentionsAi` o es privado → `TeamAssistant.handle(...)` (Task 9; en esta tarea, un `interface QuestionHandler { void handle(ChatMessage question, ChatSession asker); }` con implementación vacía por omisión que Task 9 reemplaza).
  - `changes_applied` y `apply_proposal` se delegan al mismo interface, con métodos default vacíos: `default void applied(ChatSession who, UUID messageId, List<String> applied, List<Failure> failed) {}` y `default void applyProposal(ChatSession who, UUID messageId) {}`, con `record Failure(String name, String reason)` anidado en `QuestionHandler`.

- [ ] **Step 1: Write the failing tests** (sesiones Mockito, `ChatService`/`JwtService` mockeados)
  - `authWithBadTokenCloses`
  - `sendPublishesToEveryoneInProject`: dos sesiones en el proyecto P y una en Q → solo las de P reciben `message`.
  - `privateChunksOnlyReachOwner`: `publish` de un mensaje privado de Ana y un `chunk` asociado → solo sesiones de Ana.
  - `toUserInDiagramReturnsFalseWhenUserNotInThatDiagram`
  - `mentionTriggersQuestionHandlerAndPlainChatDoesNot`
  - `viewerCanSendAndAsk` (un VIEWER manda y el handler se llama con su rol).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ChatWebSocketHandlerTests` → FAIL.
- [ ] **Step 3: Implement** (sesiones envueltas en `ConcurrentWebSocketSessionDecorator`, como en colaboración).
- [ ] **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `"Agrega el canal en vivo del chat del equipo"`

### Task 7: Leer la respuesta mientras se escribe y traducir los cambios

**Files:**
- Create: `teamai/assistant/{MessageFieldStreamer,ChangeTranslator,TeamAiReply}.java`
- Test: `backend/src/test/java/com/collabcase/teamai/assistant/{MessageFieldStreamerTests,ChangeTranslatorTests}.java`

**Interfaces:**
- Consumes: `AiDiagramContext`, `AiSuggestion`, `SuggestionType`, `AiSuggestionValidator` (existentes).
- Produces:
  - `class MessageFieldStreamer { String feed(String chunk); String text(); }` — extrae progresivamente el valor del campo `"mensaje"` del JSON que llega en pedazos; `feed` devuelve solo lo nuevo (decodificado: `\"`, `\\`, `\n`, `\t`, `\uXXXX`, aunque el escape venga partido entre dos pedazos). Deja de emitir al cerrar la comilla del valor.
  - `record TeamAiReply(String mensaje, Changes propuesta)` (el campo es `propuesta` y no `cambios` porque Ollama ordena alfabéticamente las claves del esquema y `mensaje` debe salir primero) con `record Changes(List<NewClass> clasesNuevas, List<NewAttribute> atributosNuevos, List<NewRelation> relacionesNuevas, List<RelationChange> cambiosRelacion)`, `NewClass(String nombre, List<Member> atributos, List<Method> metodos)`, `Member(String nombre, String tipo)`, `Method(String nombre, String retorno)`, `NewAttribute(String clase, String nombre, String tipo)`, `NewRelation(String origen, String destino, String tipo, String multOrigen, String multDestino)`, `RelationChange(String origen, String destino, String tipoNuevo, String multOrigen, String multDestino)`. Listas null → vacías. `static Map<String,Object> jsonSchema()` con `mensaje` como **primera** propiedad y `tipo` con enum `ASOCIACION, AGREGACION, COMPOSICION, HERENCIA, DEPENDENCIA, REALIZACION`. `static Map<String,Object> summarySchema()` solo con `mensaje`.
  - `class ChangeTranslator { List<AiSuggestion> translate(TeamAiReply.Changes c, AiDiagramContext ctx, String requestedBy); }` — limpia y traduce, y pasa el resultado por `AiSuggestionValidator.validate`. Reglas:
    - nombre `"id: Long"` → nombre `id`, tipo `Long` (si el tipo venía vacío o igual); quita prefijos `+ - # ~`.
    - tipos de relación: ASOCIACION→`ASSOCIATION`, AGREGACION→`AGGREGATION`, COMPOSICION→`COMPOSITION`, HERENCIA→`INHERITANCE`, DEPENDENCIA→`DEPENDENCY`, REALIZACION→`REALIZATION`.
    - multiplicidad: `N`, `n`, `muchos`, `*` → `*`; `1..N`/`1..n` → `1..*`; `0..N` → `0..*`; vacía → sin multiplicidad.
    - `cambiosRelacion` → busca en `ctx.relationships()` por nombres de clase (en cualquier sentido); con `tipoNuevo` → `CHANGE_RELATIONSHIP_TYPE`; con multiplicidades → `CHANGE_CARDINALITY`; si no la encuentra, se descarta.
    - `title` = `"Pedido de " + requestedBy`, `description`/`reason` = el nombre del elemento (el validador y la UI los usan).

- [ ] **Step 1: Write the failing tests**
  - Streamer: `emitsMessageAsItArrives` (`{"mens` + `aje":"Ho` + `la Luis",` + `"propuesta":{…}}` → `""`,`"Ho"`,`"la Luis"`, luego nada); `decodesEscapesSplitAcrossChunks` (`"a\` + `"b"` → `a"b`; `\u00` + `e1` → `á`); `streamerToleratesTruncatedJson` (corta en medio → `text()` devuelve lo emitido sin lanzar); `ignoresMensajeInsideOtherStrings` (un atributo con valor `"mensaje"` antes del campo real no confunde).
  - Translator (contexto con `Cliente` y `Producto`, relación Cliente–Producto existente): `cleansTypeGluedToName` (`"id: Long"` → `id`/`Long`); `mapsSpanishRelationTypesAndMultiplicities` (`N`→`*`, `1..N`→`1..*`, COMPOSICION→`ClassComposition` tras validar); `createsVentaAndDetalleConnectedToExisting` (el ejemplo medido el 23-oct: 2 `ADD_CLASS` + 3 `ADD_RELATIONSHIP`); `existingClassIsNotRecreated` (`Cliente` en `clasesNuevas` no produce `ADD_CLASS`); `relationToUnknownClassIsDropped` (y lo demás sí sale); `relationChangeFindsRelationInEitherDirection`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='MessageFieldStreamerTests,ChangeTranslatorTests'` → FAIL.
- [ ] **Step 3: Implement.** El streamer es una máquina de estados sobre caracteres (buscar la clave `"mensaje"` a profundidad 1 del objeto raíz, luego `:` y la comilla de apertura).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `"Lee la respuesta de la IA mientras se escribe y traduce sus cambios"`

### Task 8: Bitácora de actividad (moderador)

**Files:**
- Create: `teamai/activity/{ActivityEntry,ActivityRepository,ActivityService,ElementLockGrantedEvent}.java`
- Modify: `CollaborationWebSocketHandler.java` (publicar el evento al conceder un bloqueo; inyectar `ApplicationEventPublisher`), `ElementLockManager.java` (+ `Map<UUID, List<ElementLock>> snapshotAll()`)
- Test: `backend/src/test/java/com/collabcase/teamai/activity/ActivityServiceTests.java`

**Interfaces:**
- Produces:
  - `record ElementLockGrantedEvent(UUID diagramId, UUID userId, String userName, String elementId)`
  - `@Entity @Table(name="project_activity") ActivityEntry`: `UUID id, UUID projectId, UUID diagramId, UUID userId, String userName, String elementName, String action ("EDITING"|"AI_CHANGE"), Instant at`.
  - `ActivityService`:
    - `@EventListener void onLockGranted(ElementLockGrantedEvent e)` — resuelve proyecto y nombre del elemento con el `model_json` del diagrama (nodo `class` → `data.name`; arista → `"<origen>–<destino>"`); si no lo encuentra, no guarda; no duplica el mismo usuario+elemento dentro de 5 min.
    - `void recordAiChange(UUID projectId, UUID diagramId, UUID requestedById, String requestedByName, List<String> elementNames)`
    - `List<String> summaryLines(UUID projectId)` — últimas 40 entradas agrupadas por persona: `"Ana: Producto, Inventario (último cambio hace 3 min)"`; los cambios de la IA como `"IA (pedido de Luis): Venta, DetalleVenta"`.
    - `List<String> editingNow(UUID projectId)` — de `snapshotAll()` filtrado por diagramas del proyecto: `"Ana está editando Producto"`.
  - Un fallo al registrar nunca rompe el bloqueo (try/catch con log).

- [ ] **Step 1: Write the failing tests**
  - `lockGrantRecordsElementNameFromModel`
  - `repeatedLockWithinFiveMinutesIsNotDuplicated`
  - `unknownElementIsIgnored`
  - `summaryGroupsByPersonAndIncludesAiChanges`
  - `editingNowListsOnlyThisProjectsLocks`
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ActivityServiceTests` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -q test` → PASS (incluidas las de colaboración existentes).
- [ ] **Step 5: Commit** `"Registra quién trabaja en qué para que la IA modere"`

### Task 9: El asistente del equipo

**Files:**
- Create: `teamai/assistant/{TeamPromptBuilder,TeamAssistant}.java`
- Modify: `ChatWebSocketHandler`/`QuestionHandler` (Task 6) para que `TeamAssistant` sea el `QuestionHandler`
- Test: `backend/src/test/java/com/collabcase/teamai/assistant/{TeamPromptBuilderTests,TeamAssistantTests}.java`

**Interfaces:**
- Consumes: `AiJobQueue` (1), `ChatService`/`ChatRoom` (5–6), `MessageFieldStreamer`/`ChangeTranslator`/`TeamAiReply` (7), `ActivityService` (8), `DiagramService.findModelForAnalysis`, `AiDiagramContextMapper.map`.
- Produces:
  - `record PromptInput(String askerName, String askerRole, String question, boolean isPrivate, boolean summary, HelpLevel level, boolean canDraw, List<ChatMessage> history, AiDiagramContext diagram, List<String> activity, List<String> editingNow)`
  - `TeamPromptBuilder.build(PromptInput in) → List<Map<String,String>>` — un `system` + historial (personas como `user` con `"[Nombre]: texto"`, IA como `assistant`) + la pregunta. Presupuesto en caracteres (≈4 por token, 3072 tokens de entrada): diagrama ≤ 4000, actividad ≤ 1500, historial lo que sobre hasta 10000 en total, del más nuevo al más viejo, máx. 30 mensajes. El system dice, con estas ideas: sos el desarrollador senior del equipo en un chat de grupo; cada mensaje dice quién lo escribe; respondé SIEMPRE dirigiéndote por su nombre a `askerName` y en relación con lo que esa persona está haciendo; nunca respuestas genéricas; castellano; si `editingNow` muestra que otra persona edita algo que te piden, avisalo; nivel: EXPLAIN/`!canDraw` → "no propongas cambios, `propuesta` va vacío"; SUGGEST/DRAW → "si te piden construir o corregir, completá `propuesta`; nunca renombres, borres ni muevas"; `summary` → "resumí qué hizo cada integrante y qué falta según lo que dijeron en el chat y el diagrama".
  - `TeamAssistant implements QuestionHandler`:
    - `handle(ChatMessage q, ChatSession asker)`: crea mensaje IA (`kind=AI`, `status=QUEUED`, misma visibilidad que la pregunta, `requestedBy*`, `diagramId`), lo publica, arma el prompt (resumen si el texto sin la mención es `resumen`), `submit` con `TeamAiReply.jsonSchema()` (o `summarySchema()`). Listener: `queued(n)` → `queue_position`; `started` → `WRITING` + `message_updated`; `chunk` → streamer → `chunk` con lo nuevo; `finished` → parsear `TeamAiReply` (fallo de parseo → contenido = lo transmitido, `FAILED`), `content = mensaje`, `COMPLETE`, y según el nivel:
      - `EXPLAIN`, VIEWER o sin `diagramId` → ignora cambios.
      - `SUGGEST` → `proposalJson` + `PENDING`.
      - `DRAW` → `ChatRoom.toUserInDiagram(asker, diagramId, apply_changes)`; si devuelve false → `PENDING`.
      - `failed(reason)` → `FAILED` y `content` = lo transmitido + reason si no había nada.
      - `AiUnavailableException` al hacer `submit` → mensaje IA `FAILED` con el texto de la excepción.
    - `applied(ChatSession who, UUID messageId, List<String> applied, List<Failure> failed)`: si `applied` no está vacío → `APPLIED`, aviso público `IA agregó … (pedido de …)`, `ActivityService.recordAiChange`; por cada fallo → aviso visible para quien aplicó `No se pudo aplicar <name>: <reason>` y la propuesta queda `PENDING`.
    - `applyProposal(ChatSession who, UUID messageId)`: exige EDITOR/OWNER, que el mensaje sea visible para `who`, `PENDING`, y que `who.diagramId` sea el `diagramId` del mensaje (si no: `error "Abrí el diagrama para aplicar el cambio"`); entonces `apply_changes` a esa sesión.
- [ ] **Step 1: Write the failing tests**
  - Prompt: `systemNamesTheAskerAndForbidsGenericAnswers`; `historyIsTaggedWithAuthorsAndTrimmedNewestFirst` (60 mensajes → los últimos que entran, en orden); `privateHistoryOfOthersIsNotIncluded` (el `history` que llega ya viene filtrado, pero el builder no agrega nada de otros privados: se prueba desde `TeamAssistant`); `explainLevelForbidsChanges`; `summaryUsesSummaryInstruction`; `editingNowIsIncluded`.
  - Assistant (cola, sala y servicios falsos): `drawLevelSendsApplyChangesToAsker`; `drawFallsBackToPendingWhenAskerLeftTheDiagram`; `suggestLevelStoresPendingProposal`; `viewerQuestionNeverDraws`; `questionFromProjectPageNeverDraws`; `chunksAreForwardedAsTheyArrive`; `brokenJsonEndsAsFailedNotStuck`; `offlineConnectorAnswersIADesconectada`; `appliedPostsPublicNoticeAndRecordsActivity` (texto exacto `IA agregó Venta, DetalleVenta (pedido de Luis)`); `applyProposalRequiresEditorInSameDiagram`; `privateQuestionGetsPrivateAnswer`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='TeamPromptBuilderTests,TeamAssistantTests'` → FAIL.
- [ ] **Step 3: Implement.** Las escrituras a la base desde los callbacks del listener van por `ChatService` (cada una en su transacción).
- [ ] **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `"Agrega la IA que responde, modera y dibuja en el chat del equipo"`

### Task 10: Estado y canal del chat en el frontend

**Files:**
- Create: `frontend/src/api/chatApi.js`, `frontend/src/services/{chatState.js,chatState.test.js,chatSocket.js,teamChanges.js,teamChanges.test.js}`

**Interfaces:**
- Consumes: protocolo de Task 6, `SUGGESTION_ORDER`/`validateAiSuggestion` de `aiSuggestionApplier.js`.
- Produces:
  - `chatApi`: `getChatHistory(projectId)`, `getChatStatus(projectId)`, `setHelpLevel(projectId, level)` (axios `api` existente).
  - `chatState.js`: `initialChatState = { messages: [], aiConnected: false, queued: 0, helpLevel: 'DRAW', positions: {} }`; `chatReducer(state, event)` para `history`, `message`, `message_updated`, `chunk` (concatena al `content` del mensaje de ese id), `queue_position`, `ai_status`, `help_level`; `mentionsAi(text)` (mismo patrón que Java); `aiStatusLabel(state, message)` → `'en cola, posición N'` | `'escribiendo…'` | `'IA desconectada'` | `'La IA no respondió a tiempo'` | `''`.
  - `chatSocket.js`: `createChatSocket({ projectId, diagramId, token, onEvent }) → { send(content, isPrivate), reportApplied(messageId, applied, failed), applyProposal(messageId), close() }` — misma reconexión que `collaborationSocket.js` (1 s → 10 s), `auth` como primer mensaje.
  - `teamChanges.js`: `async function applyTeamChanges({ suggestions, applyOne, getModel }) → { applied: string[], failed: {name, reason}[] }` — ordena por `SUGGESTION_ORDER`, valida cada una contra el modelo actual con `suggestedClassNames` de las `ADD_CLASS` del lote, llama `applyOne(suggestion)` (que devuelve el `result` de `applyAiSuggestion`) y junta nombres (`className`, `attributeName`, `"<origen>–<destino>"`).
- [ ] **Step 1: Write the failing tests**
  - `chunksForDifferentMessagesDoNotMix`; `messageUpdatedReplacesContentButKeepsOrder`; `historyThenLiveMessagesDeduplicateById`; `aiStatusLabelShowsQueuePosition`; `mentionsAiMatchesOnlyTheMention` (mismos casos que Java).
  - `appliesClassesBeforeRelations` (lote desordenado → `applyOne` recibe primero `ADD_CLASS`); `lockedSuggestionIsReportedAsFailedWithReason` (applyOne devuelve `{status:'locked', message:'Este elemento está siendo editado por Ana'}`).
- [ ] **Step 2: Run** `cd frontend && npm test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test` → PASS (18 previas + nuevas) y `npm run lint` limpio.
- [ ] **Step 5: Commit** `"Agrega el estado y el canal del chat en el frontend"`

### Task 11: El panel del chat

**Files:**
- Create: `frontend/src/components/TeamChat.jsx`, `frontend/src/components/TeamChat.css`
- Modify: `frontend/src/pages/DiagramEditor.jsx`, `frontend/src/pages/ProjectDetail.jsx`

**Interfaces:**
- Consumes: Task 10; en el editor, `applyAiSuggestion` con `editorRef`, `roleRef`, `remoteLocksRef`, `acquireElementLock`, `releaseProgrammaticLock`, `createdClassIdsRef` (ya existen en `DiagramEditor.jsx`).
- Produces: `<TeamChat projectId diagramId? role isOwner onApplyChanges? />`. `onApplyChanges(suggestions) → Promise<{applied, failed}>`; sin la prop, el botón Aplicar no aparece y `apply_changes` se ignora.
- Comportamiento visible:
  - Lista de mensajes (nombre, hora, "privado" si lo es); los de la IA con su estado (`aiStatusLabel`) y el texto apareciendo en vivo; los avisos en estilo distinto.
  - Caja de texto con indicación "Escribí @IA para preguntarle a la IA"; interruptor **Privado (solo vos y la IA)**; enviar con Enter, nueva línea con Shift+Enter.
  - Arriba: estado de la IA (conectada / desconectada / N en cola) y el nivel de ayuda: selector para el OWNER, texto para los demás.
  - Propuesta `PENDING`: lista corta de lo que agrega + botón **Aplicar** (solo EDITOR/OWNER y solo dentro del editor).
  - Al llegar `apply_changes` en el editor: `onApplyChanges` → `reportApplied`.
  - Estilo con los tokens del rediseño "lámina técnica" que ya tiene el frontend (`index.css`); panel plegable a la derecha en el editor, columna en la página del proyecto; utilizable a 360 px de ancho.
- En `DiagramEditor.jsx`: extraer de `applySuggestion` una función `applyOneSuggestion(suggestion)` (llama `applyAiSuggestion`, `releaseProgrammaticLock`, actualiza `createdClassIdsRef`) que usan tanto el panel de IA existente como `applyTeamChanges`.

- [ ] **Step 1:** Implementar el panel y montarlo en las dos páginas.
- [ ] **Step 2: Verify** `npm test`, `npm run lint`, `npm run build` → sin errores.
- [ ] **Step 3: Verify en navegador** (Playwright o a mano): backend + `npm run dev`; dos cuentas en dos ventanas en el mismo proyecto; un mensaje sin mención aparece en las dos; un mensaje privado aparece solo en una; cambiar el nivel como OWNER se ve en la otra ventana; con el conector apagado, `@IA hola` termina en "IA desconectada". Capturas en el scratchpad.
- [ ] **Step 4: Commit** `"Agrega el panel del chat del equipo al editor y al proyecto"`

### Task 12: Prueba de punta a punta con el modelo real

**Files:**
- Create: `scripts/prueba-chat-ia.mjs` (dos usuarios por REST + WebSocket, como el banco del 23-oct)

- [ ] **Step 1:** Levantar Postgres (`docker start collabcase-db`), backend con `IA_CONECTOR_SECRETO=dev-secreto`, Ollama y `node conector-ia/conector.mjs` con `SERVIDOR_WS=ws://localhost:8080/ws/ai-connector`.
- [ ] **Step 2:** El guion crea proyecto, invita a Luis como EDITOR, crea un diagrama con `Cliente` y `Producto`, y en el chat: Ana "Yo hago inventario", Luis "Yo hago clientes y después ventas", Luis `@IA ¿qué me falta?`, Luis (con el diagrama abierto) `@IA armá el módulo de ventas conectado a Cliente y Producto`, Ana `@IA resumen`, Carla privado `@IA ¿diferencia entre agregación y composición?`.
- [ ] **Step 3: Verify**, con el texto de cada respuesta impreso: las respuestas nombran a quien preguntó; llegan `chunk` antes del final (el campo `mensaje` se transmite primero — si Ollama no respeta el orden del esquema, mover `mensaje` y ajustar `MessageFieldStreamer` aquí); el pedido de Luis produce `apply_changes` con `Venta`/`DetalleVenta` y 3 relaciones; el resumen menciona a Ana y a Luis; la pregunta de Carla no aparece en la sesión de Ana. Tiempo de cada respuesta < 60 s.
- [ ] **Step 4:** Ajustar el texto del system prompt si alguna respuesta sale genérica; volver a correr. Commit `"Agrega la prueba de punta a punta del chat con la IA local"`.

### Task 13: Imagen única para producción

**Files:**
- Create: `Dockerfile` (multi-etapa: `node:22` construye `frontend/dist`; `eclipse-temurin:21` construye el jar con `frontend/dist` copiado a `backend/src/main/resources/static`; imagen final JRE 21), `compose.aws.yml` (app + `postgres:16` con volumen; variables `JWT_SECRET`, `IA_CONECTOR_SECRETO`, `CORS_ALLOWED_ORIGINS`, `GROQ_API_KEY`, `SPRING_PROFILES_ACTIVE=aws`), `backend/src/main/java/com/collabcase/config/SpaForwardController.java`
- Test: `backend/src/test/java/com/collabcase/config/SpaForwardControllerTests.java`

**Interfaces:**
- `SpaForwardController`: rutas sin punto y fuera de `/api` y `/ws` (`/projects/**`, `/login`, `/register`, `/diagrams/**`, según las rutas de `App.jsx`) → `forward:/index.html`.
- [ ] **Step 1: Test** `deepLinkForwardsToIndex` (`GET /projects/abc` → forward a `/index.html`) y `apiIsNotForwarded` → FAIL → implementar → PASS.
- [ ] **Step 2: Verify** `docker build -t collab-case:local .` y `docker compose -f compose.aws.yml up` en local: la web abre en `http://localhost:8080`, login funciona, `/projects/<id>` recargado no da 404, el chat conecta.
- [ ] **Step 3: Commit** `"Empaqueta backend y frontend en una sola imagen"`

### Task 14: Despliegue en AWS

**Files:**
- Create: `docs/despliegue-aws.md`

- [ ] **Step 1:** EC2 (Amazon Linux 2023, al menos `t3.small`: Spring + Postgres no caben cómodos en 1 GB), grupo de seguridad con **HTTP 80 desde 0.0.0.0/0** y SSH 0.0.0.0/0 (EC2 Instance Connect entra desde AWS, no desde tu IP). Docker + compose; imagen publicada en Docker Hub **pública**.
- [ ] **Step 2:** CloudFront delante (lo aprendido en FORJA): origen = DNS público de la instancia, HTTP only 80, Redirect HTTP→HTTPS, métodos GET/HEAD/OPTIONS/PUT/POST/PATCH/DELETE, `CachingDisabled`, `AllViewer`. HTTPS es obligatorio: `crypto.randomUUID()` de `aiSuggestionApplier.js` solo existe en contexto seguro.
- [ ] **Step 3:** `CORS_ALLOWED_ORIGINS=https://<dist>.cloudfront.net` (también gobierna los orígenes del WebSocket).
- [ ] **Step 4: Verify** desde el celular con datos móviles: abrir la URL, entrar con dos cuentas, conector de la laptop con `SERVIDOR_WS=wss://<dist>.cloudfront.net/ws/ai-connector`, repetir el guion de Task 12 contra producción. Comparar el bundle servido con el construido antes de diagnosticar nada (lección de FORJA).
- [ ] **Step 5:** `docs/despliegue-aws.md` con los pasos y las trampas; commit `"Documenta el despliegue en AWS"`.

### Task 15: Subir el repositorio a GitHub

El pack pesa 154 MB por archivos de 58–86 MB (`.onnx` duplicado, `barberia.zip`, dos `mobile-template.zip`) y basura trackeada; por eso el push nunca llegó. El QR de la carátula necesita el enlace al repo.

- [ ] **Step 1:** Listar con `git rev-list --objects --all | git cat-file --batch-check` los blobs > 20 MB y mostrárselos al usuario.
- [ ] **Step 2: PEDIR CONFIRMACIÓN AL USUARIO** antes de reescribir la historia (`git filter-repo --strip-blobs-bigger-than 20M` más las rutas basura de `ANALISIS-BACKEND.md` §7). Es irreversible sobre las ramas locales; antes, respaldo con `git bundle create ../collab-case-respaldo.bundle --all`.
- [ ] **Step 3:** Si el `.onnx` hace falta para la app móvil, publicarlo como Release del repo y dejar el enlace en `mobile-template/README.md`.
- [ ] **Step 4: Verify** `git count-objects -vH` < 50 MB; `git push origin main`; el repo en GitHub muestra el último commit.
