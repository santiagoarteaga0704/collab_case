/// Servidor de Collab CASE (el mismo que usa la web). Se cambia al compilar con
/// `--dart-define=SERVIDOR=https://otro-servidor`.
const String servidor = String.fromEnvironment(
  'SERVIDOR',
  defaultValue: 'https://d2x41sl49sltgo.cloudfront.net',
);

/// Clave con la que la web guarda el token en localStorage; el editor la lee de ahí.
const String claveTokenWeb = 'collab-case-token';
