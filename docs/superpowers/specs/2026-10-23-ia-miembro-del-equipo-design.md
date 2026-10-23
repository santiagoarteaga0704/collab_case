# La IA como miembro del equipo — diseño (2º parcial)

Fecha: 23-oct-2026. Base: Collab CASE, rama `main` en `177da18`.

## Por qué

El segundo parcial de Ingeniería de Software pide que la herramienta CASE colaborativa
"gire en torno a la IA": la IA actúa como un desarrollador senior más del equipo. Los
integrantes trabajan desde lugares distintos, cada uno con su sesión, y ven en vivo lo que
hacen los demás (eso Collab CASE ya lo tiene). Lo nuevo es la IA dentro de ese equipo:

- cualquiera le puede preguntar o pedirle que haga algo, y **todos ven la pregunta y la
  respuesta** (un chat de grupo);
- la IA **sabe quién le pregunta**, mantiene el contexto y responde a esa persona por su
  nombre y en relación con lo que esa persona está haciendo, nunca de forma genérica;
- también distingue **preguntas individuales**;
- **entrega soluciones cuando se le piden**, de forma **configurable**;
- actúa como **moderador** del equipo;
- el docente exige **modelo local** ("sí o sí IA local").

El examen se publica el **11-nov-2026 a las 08:00** y desde ahí no se puede tocar nada. Se
revisa que el software haga lo que tiene que hacer, "en calidad de producto, completamente
terminado". Al grupo le toca principalmente el código.

## Qué hay hoy (lo que se reaprovecha)

- Editor UML colaborativo en vivo (Apollon + Yjs) por WebSocket `/ws/collaboration/{diagramId}`,
  con bloqueos de elementos (`ElementLockManager`) y roles OWNER / EDITOR / VIEWER.
- Tres IAs separadas, ninguna social: el asistente UML (`ai/`, análisis y sugerencias),
  el agente de ayuda (`agent/`, chat privado de 6 mensajes que guarda el navegador, no sabe
  el nombre de quien habla) y la lectura de imágenes (`vision/`). Proveedores
  intercambiables: Ollama, Groq, Bedrock.
- `frontend/src/services/aiSuggestionApplier.js` ya sabe validar y aplicar al modelo de
  Apollon sugerencias `ADD_CLASS` / `ADD_ATTRIBUTE` / `ADD_METHOD` / `ADD_RELATIONSHIP` /
  `CHANGE_RELATIONSHIP_TYPE` / `CHANGE_CARDINALITY`. Aplicar en Apollon propaga por Yjs a
  todos: **así es como la IA "dibuja directo"**.
- Persistencia JPA con `ddl-auto=update` (las tablas nuevas se crean solas).

## Modelo de IA (decidido y medido)

**`qwen3:4b-instruct` en Ollama 0.40.9**, corriendo en la laptop de Santiago (RTX 3050 de
4 GB, i7-10750H, 16 GB). Ya instalado; los demás modelos se borraron. Medido el 23-oct con
un chat de grupo simulado (Ana, Luis, Carla):

| Modelo | Calidad | Tiempo |
|---|---|---|
| qwen3:1.7b (el actual del proyecto) | errores de UML ("herencia = agregación más fuerte") | 1–6 s |
| gemma3:4b | inventa clases, composición N:N | 20–45 s |
| qwen3:4b | bueno, pero piensa en inglés antes de responder | 45–90 s |
| qwen3:8b | el mejor, no entra en la GPU | 20–95 s |
| **qwen3:4b-instruct** | bueno: nombra a quien pregunta, usa el contexto, arma Venta+DetalleVenta bien conectados | **7–11 s** respuesta, ~38 s un módulo |

Opciones de inferencia: `num_ctx` 4096, `temperature` 0.3. Con Brave abierto el modelo
entra 66 % en GPU; cerrarlo lo acelera. En CPU de servidor (sin GPU) tarda 40–60 s por
respuesta corta y más de 3 min leyendo un contexto real: **por eso el modelo NO va en el
servidor**.

Observado: el JSON que devuelve a veces mete el tipo en el nombre (`"id: Long"`). El
servidor limpia y valida todo lo que devuelve antes de usarlo.

## Arquitectura

```
Integrantes (navegador) ──► SERVIDOR en AWS (Collab CASE: backend + frontend + Postgres)
                                ▲
                                │ WebSocket que abre la LAPTOP hacia el servidor
                                │
                            LAPTOP: conector + Ollama (qwen3:4b-instruct)
```

- **La app se despliega en AWS** (requisito del grupo). El QR de la carátula apunta ahí y
  debe quedar vigente hasta fin de semestre.
- **La IA corre en la laptop**. Un programa **conector** en la laptop abre un WebSocket
  hacia el servidor (`/ws/ai-connector`) autenticado con un secreto compartido
  (`IA_CONECTOR_SECRETO`, variable de entorno en los dos lados). Va de la laptop al servidor,
  no al revés, para funcionar con cualquier red (casa, universidad, datos del celular) sin
  abrir puertos ni túneles de terceros.
- El servidor manda **trabajos** al conector; el conector llama a Ollama en
  `localhost:11434` con streaming y devuelve los fragmentos al servidor, que los reenvía
  al chat para que todos vean la respuesta escribirse.
- Un solo conector a la vez; los trabajos se atienden **de a uno, en cola** (hay una sola
  GPU). Mientras espera, el chat muestra "en cola, posición N"; la cola admite como máximo
  5 trabajos y el sexto se rechaza con "la IA está ocupada, probá en un momento". Tiempo
  máximo por trabajo: 120 s **contados desde que la GPU empieza a atenderlo**, no desde
  que entra a la cola; al vencer, el mensaje queda como "la IA no respondió a tiempo".
  *(Decisión del diseñador.)*
- **Una sola llamada por pedido, con streaming igual.** Todo pedido a `@IA` usa salida
  estructurada con el esquema `{ mensaje, propuesta }`, con `mensaje` primero. El modelo
  decide si hace falta dibujar (`propuesta` con listas vacías = solo conversa). El servidor va leyendo el
  JSON a medida que llega y reenvía al chat el texto del campo `mensaje` mientras se
  escribe; la `propuesta` se procesa al terminar. El campo se llama `propuesta` y no
  `cambios` porque Ollama ordena alfabéticamente las claves del esquema: con `cambios`, el
  modelo escribía los cambios antes que el `mensaje` y no había nada que mostrar en vivo. Así no hay dos llamadas (los ~38 s medidos
  para un módulo fueron una sola llamada estructurada) y la respuesta igual aparece en vivo.
  `@IA resumen` es el único comando fijo. *(Decisión del diseñador.)*
- **Laptop apagada:** el chat muestra "IA desconectada" y los pedidos a la IA se rechazan
  con ese aviso. No se diseña nada más para ese caso (decisión del usuario: la laptop va a
  estar encendida; lo que se defiende es que la IA es local y no depende de internet).
- Las IAs que ya existen (asistente UML y agente de ayuda) también pasan a poder usar el
  conector como proveedor (`ai.provider=conector`, `agent.provider=conector`), para que en
  AWS ninguna IA de texto dependa de Groq o Bedrock. Lectura de imágenes y dictado quedan
  como están (ver No-objetivos).

Esto se documenta como arquitectura cliente-servidor + nodo de inferencia local, y sirve
para el punto de "cuatro tipos de arquitectura" del documento.

## El chat del equipo

- **Un chat por proyecto**, panel lateral visible en el editor de diagramas y en la página
  del proyecto. Mensajes en vivo con nombre, hora y (si se escribió desde el editor) el
  diagrama que el autor tenía abierto.
- **Se guarda en la base**: quien entra tarde ve toda la conversación. Los mensajes no se
  editan ni se borran.
- **A la IA se le habla mencionándola: `@IA ...`.** Los mensajes sin mención son
  conversación del equipo: la IA los recibe como contexto pero no los contesta.
- **Preguntas individuales:** se puede escribir en modo privado; esa conversación solo la
  ven el autor y la IA. La IA responde ahí sin mención.
- La IA aparece como un integrante más ("IA · senior"), con sus respuestas escribiéndose en
  vivo para todos.
- Un VIEWER puede leer el chat y preguntarle a la IA, pero la IA nunca dibuja a pedido de un
  VIEWER (misma regla que hoy tiene el agente).

### Qué contexto recibe la IA en cada pedido

1. Quién pregunta: nombre y rol en el proyecto.
2. Los últimos mensajes del chat (públicos, más los privados de ese autor), recortados para
   caber en 4096 tokens junto con lo demás.
3. El diagrama en el que está quien pregunta, en forma compacta (clases, atributos,
   métodos, relaciones con multiplicidad).
4. Quién está haciendo qué (ver Moderador).
5. El nivel de ayuda del proyecto.

Instrucción de sistema: es el desarrollador senior del equipo; siempre se dirige por su
nombre a quien preguntó y responde en relación con lo que esa persona está haciendo; nunca
respuestas genéricas; en castellano.

## Nivel de ayuda (lo "configurable")

Ajuste del proyecto que solo cambia el OWNER, visible para todos en el chat:

- **Explicar:** responde y enseña; no toca el diagrama.
- **Sugerir:** propone el cambio; en el chat aparece la propuesta con un botón **Aplicar**
  que cualquier EDITOR puede apretar.
- **Dibujar:** aplica el cambio directo en el diagrama.

Por omisión: **Dibujar** (es lo que el usuario quiere mostrar). El servidor hace cumplir el
nivel, no solo la pantalla.

## La IA dibuja

- Cuando el pedido implica cambiar el modelo, la IA devuelve, además del mensaje, una lista
  de cambios en un JSON con esquema (structured output de Ollama): clases nuevas con
  atributos y métodos, y relaciones con tipo y multiplicidades.
- El **servidor** limpia (nombres con tipo pegado, mayúsculas de tipos de relación) y valida
  (no duplicar clases existentes, relaciones solo entre clases que existen o se crean,
  multiplicidades válidas) y lo traduce al formato de sugerencias que ya entiende
  `aiSuggestionApplier.js`.
- **Quién lo aplica:** el navegador de quien pidió, que tiene el diagrama abierto, lo aplica
  en Apollon pidiendo los bloqueos como cualquier edición; Yjs lo propaga a todos. Si ese
  navegador ya no está, la propuesta queda en el chat con el botón Aplicar para cualquier
  EDITOR conectado. *(Decisión de diseño: Yjs vive en los clientes y el servidor no tiene una
  implementación de Yjs en Java.)*
- **Qué puede cambiar:** exactamente lo que ya sabe aplicar `aiSuggestionApplier.js` —
  crear clases (con atributos y métodos), agregar atributos y métodos a clases existentes,
  crear relaciones y cambiar el tipo o la multiplicidad de una relación existente. **No
  renombra, no borra y no mueve**: si se lo piden, lo explica y deja que lo haga una
  persona. Borrar es lo único destructivo y una IA de 4B equivocándose ahí delante del
  docente no vale el riesgo. *(Decisión del diseñador.)*
- Pedir un dibujo a la IA solo se puede desde el editor de un diagrama; desde la página del
  proyecto la IA conversa pero no dibuja.
- **Dibujar desde el modo privado sí se puede.** Como el diagrama lo ve todo el equipo, el
  aviso "IA agregó X (pedido de Luis)" se publica en el chat público aunque la conversación
  haya sido privada. En nivel Sugerir, una propuesta pedida en privado solo la ve (y la
  aplica) su autor. *(Decisión del diseñador.)*
- En el chat queda un aviso: "IA agregó Venta y DetalleVenta (pedido de Luis)".

## La IA como moderador (versión acotada)

- **Sabe quién está haciendo qué**: el servidor lleva una bitácora de actividad por
  proyecto a partir de lo que ya ve — los bloqueos concedidos (quién tomó qué elemento),
  los mensajes del chat y los cambios que aplicó la IA a pedido de quién. Los ids de
  elemento se traducen a nombres de clase con el modelo guardado.
- **Avisa choques**: si alguien le pide algo sobre un elemento que otra persona tiene
  bloqueado o tocó hace poco, la respuesta lo dice ("Luis, ojo: Ana está editando Producto").
- **`@IA resumen`**: responde qué hizo cada integrante y qué falta. "Qué falta" lo infiere
  el modelo del chat (lo que cada uno dijo que iba a hacer) y del diagrama; no hay lista de
  tareas ni pantalla nueva para eso. *(Decisión del diseñador.)*
- No interviene sola sin que nadie le hable.

## Objetivos

- Chat de equipo por proyecto, en vivo, guardado, con mención `@IA` y modo privado.
- La IA nombra a quien pregunta, usa su contexto, y lo demuestra en la defensa.
- La IA dibuja en el diagrama según el nivel de ayuda del proyecto.
- Moderador: conoce la actividad de cada uno, avisa choques, hace resumen.
- Todo con el modelo local de la laptop vía conector; la app en AWS con HTTPS.
- Calidad de producto: sin pantallas a medio hacer, con estados de carga y error, probado.

## No-objetivos

- La app móvil (`mobile-template/`) no se toca.
- Lectura de imágenes y dictado no se migran al conector: en AWS siguen con Groq, como ya
  está en `application-aws.properties`. Decisión del usuario: la IA local es la que
  "desarrolla" (chat, dibujo, moderador, asistente UML y agente de ayuda); voz e imagen son
  vías de entrada y pueden ir en la nube.
- Que la IA intervenga sin que le pregunten.
- Funcionar con la laptop apagada.
- Editar o borrar mensajes del chat.

## Anti-objetivos (fallaría aunque "ande")

- Respuestas genéricas que no nombran ni usan el contexto de quien pregunta.
- Que la IA rompa el diagrama (clases duplicadas, relaciones a clases que no existen) o se
  salte los bloqueos de otro.
- Un chat que se cuelga esperando a la IA: siempre hay estado visible (pensando, escribiendo,
  sin conexión, no respondió a tiempo).
- Depender de un servicio de IA en la nube en lo que se demuestra.

## Restricciones

- Fecha dura: 11-nov-2026 08:00; congelado después.
- Stack existente: Spring Boot 4.1.1 / Java 21 / Postgres; React 19 + Vite + Apollon 5.3;
  Jackson 3 (`tools.jackson.*`).
- Despliegue en AWS. Experiencia previa útil (FORJA): EC2 + Docker Compose + CloudFront para
  HTTPS sin dominio; el WebSocket atraviesa CloudFront.
- 4096 tokens de contexto: el historial y el diagrama se recortan para caber.

## Lo que queda a criterio del que construye

- Lenguaje del conector (propuesta: Node 22, que ya trae cliente WebSocket y `fetch`; un solo
  archivo, se arranca con `node conector.mjs`).
- Si el chat usa un WebSocket nuevo por proyecto o se monta sobre el existente.
- Forma exacta de las tablas (mensajes, actividad, nivel de ayuda en `Project`).
- Recorte del historial y formato compacto del diagrama.
- Detalles del despliegue en AWS (plan aparte si conviene).
