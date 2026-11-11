# Collab CASE — app de teléfono

App Android en Flutter que usa el mismo backend que la web (AWS:
`https://d2x41sl49sltgo.cloudfront.net`). Tiene los mismos requisitos que la web:

| Pantalla | Qué hace | Cómo |
|---|---|---|
| Ingreso / registro | Cuenta y sesión guardada en el teléfono | Nativo (`/api/auth`) |
| Proyectos | Listar, crear, renombrar, eliminar | Nativo (`/api/projects`) |
| Proyecto → Diagramas | Versiones (cambiar, crear, renombrar, eliminar) y diagramas | Nativo |
| Proyecto → Chat del equipo | Chat en vivo con la IA: `@IA`, modo privado, cola, nivel de ayuda (Explicar / Sugerir / Dibujar), `@IA resumen`, dictado por voz | Nativo (`/ws/chat/{id}`), misma lógica que `TeamChat.jsx` |
| Proyecto → Miembros | Agregar por correo, cambiar rol, quitar (solo propietario) | Nativo |
| Agente de ayuda | Explica cómo usar la herramienta y hace acciones | Nativo (`/api/agent`) |
| Editor UML | Edición en vivo, la IA dibuja, asistente de modelado, foto de pizarra, voz, exportar, generar código | El editor web dentro de la app (Apollon y Yjs solo existen para la web), con `assets/editor_movil.js` para pantalla de celular y descargas |

## Compilar e instalar

```bash
flutter pub get
flutter test
flutter build apk --release        # build/app/outputs/flutter-apk/app-release.apk
```

Otro servidor: `flutter build apk --release --dart-define=SERVIDOR=https://otro-servidor`.

La IA sigue corriendo en la laptop: para que responda, el conector (`conector-ia/`) tiene
que estar encendido, igual que para la web.
