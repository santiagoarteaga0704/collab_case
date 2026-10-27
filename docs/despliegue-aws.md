# Despliegue en AWS

URL pública: **https://d2x41sl49sltgo.cloudfront.net**

## Qué hay

| Pieza | Detalle |
|---|---|
| Servidor | EC2 `t3.micro` (capa gratuita), Amazon Linux 2023, región `us-east-2`, instancia `collab-case` (`i-0398bfa8c0217281f`), 20 GB gp3, 2 GB de swap |
| Grupo de seguridad | `collab-case`: HTTP 80 y SSH 22 desde 0.0.0.0/0 |
| HTTPS | CloudFront `E18KFOZ92MSIRJ` → origen `ec2-13-58-79-94.us-east-2.compute.amazonaws.com`, HTTP only 80, Redirect HTTP→HTTPS, todos los métodos, `CachingDisabled`, `AllViewer`, timeout de lectura 60 s |
| App | `/opt/collab-case/` en el servidor: `compose.yml` (= `compose.aws.yml`), `compose.override.yml`, `.env` (secretos, no versionado) |
| IA | No corre en AWS: el conector (`conector-ia/`) corre en la laptop con Ollama y se conecta a `wss://d2x41sl49sltgo.cloudfront.net/ws/ai-connector` |

HTTPS es obligatorio: `crypto.randomUUID()` del editor solo existe en contexto seguro.

## Volver a desplegar una versión nueva

Desde la raíz del repo, en la PC (la imagen no se construye en el servidor: 1 GB de RAM no alcanza):

```bash
docker build -t collab-case:local .
docker save collab-case:local | gzip -1 > imagen.tar.gz
scp -i ~/.ssh/collabcase_key imagen.tar.gz ec2-user@13.58.79.94:/opt/collab-case/
ssh -i ~/.ssh/collabcase_key ec2-user@13.58.79.94 \
  'cd /opt/collab-case && gunzip -c imagen.tar.gz | docker load && rm imagen.tar.gz &&
   docker compose -f compose.yml -f compose.override.yml up -d --no-build'
rm imagen.tar.gz
```

Si cambió `compose.aws.yml`, copiarlo también: `scp compose.aws.yml ec2-user@13.58.79.94:/opt/collab-case/compose.yml`.

## Conectar la IA de la laptop

```bash
export IA_CONECTOR_SECRETO=<valor de .env.aws.servidor>
export SERVIDOR_WS=wss://d2x41sl49sltgo.cloudfront.net/ws/ai-connector
node conector-ia/conector.mjs
```

## Voz y foto de pizarra (Gemini)

El perfil `aws` transcribe la voz y lee la foto de la pizarra con Gemini (`gemini-2.5-flash`); la IA miembro del
equipo sigue siendo local (conector + Ollama). La clave sale gratis de https://aistudio.google.com/apikey y va en
`/opt/collab-case/.env` como `GEMINI_API_KEY=...`; tras cambiarla, `docker compose -f compose.yml -f compose.override.yml up -d`.
Otro modelo: `GEMINI_MODEL=...` en el mismo `.env`.

## Trampas

- `t3.micro` tiene 1 GB: la JVM va con `-Xmx512m` y Postgres con `shared_buffers=64MB` (en `compose.aws.yml`); sin el swap no arranca.
- `CORS_ALLOWED_ORIGINS` debe ser exactamente la URL de CloudFront: también gobierna los orígenes del WebSocket.
- Si la IP pública del servidor cambia (por ejemplo, al detenerlo y volver a prenderlo), hay que actualizar el origen de CloudFront con el nuevo DNS público.
- Antes de diagnosticar un error en producción, comparar el bundle servido con el construido.
- Probar todo de punta a punta: `BASE=https://d2x41sl49sltgo.cloudfront.net node scripts/prueba-chat-ia.mjs` (con el conector corriendo).
