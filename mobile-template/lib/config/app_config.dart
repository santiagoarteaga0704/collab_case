class AppConfig {
  const AppConfig._();

  static const backendBaseUrl = String.fromEnvironment(
    'BACKEND_URL',
    defaultValue: 'http://127.0.0.1:8081',
  );

  static const showDebugStatus = bool.fromEnvironment(
    'SHOW_DEBUG_STATUS',
    defaultValue: false,
  );

  static const mobileApiToken = String.fromEnvironment(
    'MOBILE_API_TOKEN',
    defaultValue: '',
  );
}
