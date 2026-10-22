# Análisis completo de Collab CASE

Hecho el 22-oct-2026 sobre el commit `7b05a6b` ("Proyecto Collab CASE", Alex Rossel),
copiado desde `Desktop\collab-case` a `C:\dev\collab-case`. Antes de tener el código
se analizó también el jar estable descompilado. Todo lo marcado como "probado" se
ejecutó de verdad.

## Estado general (probado)

| Parte | Qué es | Resultado |
|---|---|---|
| `backend/` | Spring Boot 4.1.1, Java 21, Postgres | **82/82 tests OK**. 1 falla si `mvn` no está en el PATH, porque el test de compilación lo invoca. |
| `frontend/` | React 19 + Vite 8 + Apollon 5.3 (editor UML con Yjs) | **18/18 tests**, lint limpio, build OK. El bundle pesa 1,4 MB. |
| `mobile-template/` | Flutter offline: SQLite, `llama.cpp` (GGUF) y Whisper (sherpa-onnx) | **28/28 tests**, `flutter analyze` sin problemas. |
| `local-stt/` | FastAPI + faster-whisper en `:8090` | Leído, no ejecutado. |
| jar estable | Backend empaquetado | Corrido en `:8080` con Postgres en Docker. Prueba de punta a punta OK, salvo la IA (Ollama no está instalado). |

El `run-error.txt` del repo es de septiembre y solo dice que no existía la base
`collabcase`. Además, sin `JWT_SECRET` el backend no arranca, porque no tiene valor por defecto.

## Cómo levantarlo

1. `docker start collabcase-db` (Postgres 16 en **5446**, base `collabcase`, usuario y clave `postgres`)
2. `backend\stable\correr-backend.ps1`, que usa `backend\stable\.env` (ignorado por git)
3. `cd frontend; npm run dev` → http://localhost:5173
4. Opcional, para la IA local: Ollama con `qwen3:1.7b` y `qwen2.5vl:3b`. Para la voz: `local-stt` según su README.

## Hallazgos, de más grave a menos

### 1. El backend generado no compila si la relación 1→N se dibuja desde el lado "1" (probado)
Con `ClassBidirectional` Autor `1` → Libro `0..*` se genera `@ManyToOne(mappedBy="libroList")`.
`@ManyToOne` no tiene `mappedBy`, así que el código **no compila**. Dibujada al revés,
compila. La unidireccional, la composición, la N:M y la herencia también compilan.
- Dónde: `SpringBootProjectGenerator.relationshipFields`, rama `ClassBidirectional`.
  El `mappedBy` se pone siempre en el **target**, sin mirar qué lado es el "muchos".
- Por qué no lo detectaron los tests: `CodeGenerationTests.generatedProjectCompilesWithMaven`
  no tiene una relación bidireccional 1→N dibujada desde el "1".
- Arreglo: el dueño es el lado `@ManyToOne` (o el source si es 1:1). El `mappedBy` va en
  el otro lado y apunta al campo del dueño. Hay que agregar un test con ese caso.

### 2. Los locks solo los respeta el frontend
`CollaborationWebSocketHandler.handleYjs` reenvía cualquier update de Yjs **sin mirar
los locks**. El editor (`DiagramEditor.jsx`) sí los respeta, pero un cliente con un bug
o alguien usando la consola puede editar un elemento bloqueado.
- El control del VIEWER **está bien**. En Apollon el tipo 0 es la petición de sync, el 1
  es el update, el 2 la petición de awareness y el 3 el update de awareness (`yjsSync-*.js`).
  El servidor y `collaborationSocket.js` bloquean el tipo 1 para el VIEWER.
- Para la defensa conviene explicarlo así: los locks son "cooperativos". Si se quieren
  forzar en el servidor, hay que decodificar el update de Yjs y ver qué nodos toca.

### 3. Todos los editores guardan el modelo completo
Cada cliente conectado hace `PUT /api/diagrams/{id}` con el modelo entero (debounce en
`scheduleSave`), y `Diagram` no tiene `@Version`. Como Yjs converge casi siempre da lo
mismo, pero un cliente atrasado puede pisar un guardado más nuevo hasta el siguiente
cambio. Además, con N personas conectadas hay N escrituras por cada cambio.
- Mejora: que guarde solo el cliente que originó el cambio, o persistir el estado Yjs en
  el servidor, o agregar `@Version` y reintentar.

### 4. Sincronización móvil
- Runtime (`RuntimeSyncService`) y backend generado (`MobileSyncService` + `MobileChangeLog`)
  asignan la versión o cursor **antes** del commit. Si dos transacciones se cruzan, un
  teléfono puede saltarse un cambio para siempre.
- En el runtime, una mutación inválida revierte **todo el lote** y la cola offline queda
  trabada. En el generado, el `catch` por mutación no alcanza: una excepción de JPA deja
  la transacción marcada como *rollback-only* y el `sync` entero falla.

### 5. Rendimiento
- Cada mensaje de Yjs, incluido cada movimiento de cursor, llama a `refreshRole`, que son
  unas 3 consultas a la base. Habría que cachear el rol en la sesión; `notifyRoleChanged`
  ya existe para invalidarlo.
- La IA es síncrona, puede tardar hasta 180 s y no tiene límite de concurrencia.
- En `local-stt`, `transcribe` es CPU pesada dentro de un `async def`, así que bloquea el
  event loop: atiende de a una transcripción. Se arregla con `def` normal o `run_in_threadpool`.

### 6. IA local
- Los modelos chicos (`qwen3:1.7b`, `qwen2.5vl:3b`) sirven para la demo, pero analizan pobre.
  Groq y Bedrock ya están como alternativas configurables.
- Lo bueno: la salida se valida en el servidor con esquema JSON, `AiSuggestionValidator`
  y una lista blanca de acciones del agente. La IA nunca toca el modelo sola y el
  frontend pide el lock antes de aplicar una sugerencia.

### 7. Repositorio (por esto probablemente el push nunca llegó)
- El pack pesa **154 MB**. Hay archivos de 58 a 86 MB: el `decoder.int8.onnx` está dos
  veces, más `barberia.zip` y dos copias de `mobile-template.zip`. GitHub avisa desde
  50 MB y estos pushes grandes suelen cortarse.
- Hay 32 archivos de basura trackeados: `backend/bin/`, `backend/target_classes/`,
  `backend/target_test-classes/`, una carpeta con nombre de ruta rota
  (`backend/.._.._…_Desktop_SW1_…`) y `run-error.txt`.
- Propuesta: sacar del repo `generated-verification/` (o solo su zip y su onnx), `bin`,
  `target_*` y la carpeta rota. Usar Git LFS o un Release para los `.onnx` y los zips.

### 8. Menores
- `ClassUnidirectional` 1→N genera `@OneToMany` sin `@JoinColumn`, así que JPA crea una
  tabla intermedia. El export SQL, en cambio, pone una FK. Los dos artefactos no coinciden.
- `GET /api/projects/{id}/diagrams` mezcla los diagramas de todas las versiones.
- El token de la app runtime va dentro del APK: quien tenga el APK puede hacer CRUD.
- `SpringBootProjectGenerator.java` tiene 74 líneas con métodos enteros en una sola línea.
  Así es muy difícil de mantener y de defender; conviene formatearlo.
- `DiagramEditor.jsx` tiene 1.226 líneas. Se puede partir en hooks: colaboración, guardado, IA y exportes.
- Usa `ddl-auto=update` y no tiene migraciones.

## Cambios aplicados (rama `fix/bugs-analisis`, 22-oct)

Backend: **88/88 tests**, con 6 tests nuevos. Lo marcado como "probado" se verificó
generando el proyecto, compilándolo y levantándolo contra Postgres.

| # | Bug | Arreglo | Verificado |
|---|---|---|---|
| 1 | El backend generado no compilaba con una relación bidireccional 1→N dibujada desde el "1" | `mappedBy` en el lado de la colección; el dueño es el `@ManyToOne` (`SpringBootProjectGenerator`) | Test + compilado + probado |
| 1b | Un atributo con el mismo nombre que una relación (`Libro.autor` + relación con `Autor`), o dos relaciones al mismo destino, generaban código que no compila | `GenerationValidator` ahora lo reporta como error claro antes de generar | Test + API |
| 8 | Unidireccional 1→N: JPA creaba una tabla intermedia mientras el SQL usaba FK | `@JoinColumn(name="<tabla>_<id>")`, igual que el export SQL | Test |
| 4 | Backend generado: un error de base (por ejemplo, borrar un Autor que tiene Libros) devolvía **500 en todo `/api/mobile/sync`** y se perdían las demás mutaciones del lote | Cada mutación corre en su propia transacción (`REQUIRES_NEW`). El error queda como conflicto solo de esa mutación, y un tipo de entidad desconocido también es un conflicto | Antes: 500 y alta perdida. Ahora: 200, conflicto y alta guardada (Postgres) |
| 4b | Backend generado: el cursor de cambios podía saltarse cambios con escrituras concurrentes | Lock de una fila (`MobileSyncLock`) al inicio de cada escritura (sync y CRUD) | 40 escrituras en paralelo + lector → 40/40 |
| 4c | Backend generado: `GET /api/mobile/changes` fallaba en Postgres ("Large Objects may not be used in auto-commit mode") por `@Lob String` | `payload` pasa a `text` y la lectura es `@Transactional(readOnly=true)` | Probado en Postgres |
| 4d | Runtime: una mutación inválida revertía todo el lote | Se rechaza solo esa mutación; nueva lista `rejected` en la respuesta | Test |
| 4e | Runtime: hueco en el cursor con escrituras concurrentes | Lock pesimista de la fila de la app en cada escritura (`authenticateForWrite`) | Tests existentes |
| 5 | Unas 3 consultas a la base por cada mensaje de Yjs | El rol se cachea 5 s para el tráfico Yjs. Los locks siguen consultando siempre, y los cambios de rol o revocaciones se siguen avisando al instante | Tests |
| 5b | `local-stt` atendía de a una transcripción | `def` en vez de `async def` (corre en el pool de hilos) | Solo sintaxis (no hay Whisper instalado acá) |

**No cambiados, a propósito:**
- **Locks en el servidor (2).** Habría que decodificar los updates de Yjs en Java. Se explican como locks cooperativos.
- **Guardado de todos los editores (3).** Cambiarlo requiere que Apollon distinga si un cambio es local o remoto. El riesgo es bajo porque Yjs converge.
- **Listado de diagramas mezclado.** La UI no lo usa; usa el listado por versión.

## Lo que está bien hecho
Protección contra XXE en el XMI y contra *zip-slip*, BCrypt y JWT enviado por mensaje
(no en la URL del WebSocket). El rol se revalida en cada operación y se cierra la
sesión si te quitan el acceso. Hay heartbeat y limpieza de locks y presencia, texto
escapado en PlantUML, verificación de la firma real de las imágenes y proveedores de IA
intercambiables (local o en la nube). La app móvil funciona offline con mutaciones
idempotentes (`mutationId`) y tiene buena cobertura de tests en las tres partes.
