# Collab CASE Mobile Template

Cliente Android offline-first para los backends Spring Boot generados por
Collab CASE. Después de generar el proyecto, el móvil solo se comunica con
`/api/mobile/definition`, `/api/mobile/changes` y `/api/mobile/sync`.

## Ejecución

```bash
flutter pub get
adb reverse tcp:8081 tcp:8081
flutter run --dart-define=BACKEND_URL=http://127.0.0.1:8081
```

Puede añadirse `--dart-define=SHOW_DEBUG_STATUS=true` durante desarrollo y
`--dart-define=MOBILE_API_TOKEN=...` cuando el backend generado tenga token.

## Modelos offline

- IA: el usuario selecciona una vez un GGUF; se copia al almacenamiento privado.
- Voz: consulta [assets/asr/README_ASR.md](assets/asr/README_ASR.md).

La aplicación no descarga modelos durante runtime.
