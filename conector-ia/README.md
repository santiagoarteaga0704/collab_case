# Conector de la IA (laptop)

Este programa conecta la IA que corre en tu laptop (Ollama) con el servidor de Collab CASE. No necesita instalar paquetes: solo Node 22 u otra versión más nueva.

1. Instala [Ollama](https://ollama.com) y descarga el modelo: `ollama pull qwen3:4b-instruct`.
2. Define el secreto, que debe ser el mismo valor que `IA_CONECTOR_SECRETO` del servidor: `export IA_CONECTOR_SECRETO=tu-secreto`.
3. Define la dirección del servidor: `export SERVIDOR_WS=wss://<dominio>/ws/ai-connector`.
4. Opcional: `OLLAMA_URL` (por omisión `http://localhost:11434`) y `MODELO` (por omisión `qwen3:4b-instruct`).
5. Arranca el conector: `node conector.mjs`. Debe decir «Listo: la IA ... está conectada al servidor».

Deja la laptop enchufada y despierta mientras el equipo use la IA. Si se corta la conexión, el conector reintenta solo (1 s, 2 s, 4 s... hasta 15 s). Si ves «Secreto rechazado por el servidor», revisa `IA_CONECTOR_SECRETO`.

Pruebas: `node --test conector-ia/*.test.mjs` desde la raíz del repositorio.
