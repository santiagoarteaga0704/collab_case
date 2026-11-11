class AppConfig {
  const AppConfig._();

  static const backendBaseUrl = String.fromEnvironment(
    'BACKEND_URL',
    defaultValue: 'http://127.0.0.1:8081',
  );

  /// Dirección del backend en uso. El APK que viene en el ZIP generado es el mismo para
  /// cualquier proyecto, así que la dirección se escribe en el teléfono y se guarda.
  static String serverUrl = backendBaseUrl;

  static const showDebugStatus = bool.fromEnvironment(
    'SHOW_DEBUG_STATUS',
    defaultValue: false,
  );

  static const mobileApiToken = String.fromEnvironment(
    'MOBILE_API_TOKEN',
    defaultValue: '',
  );
}
