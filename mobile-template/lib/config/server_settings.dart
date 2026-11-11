import 'package:shared_preferences/shared_preferences.dart';

import 'app_config.dart';

/// Guarda en el teléfono la dirección del backend generado.
class ServerSettings {
  const ServerSettings._();

  static const _key = 'backend_url';

  static Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    AppConfig.serverUrl = prefs.getString(_key) ?? AppConfig.backendBaseUrl;
  }

  static Future<void> save(String url) async {
    final clean = normalize(url);
    AppConfig.serverUrl = clean;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, clean);
  }

  /// "192.168.1.10:8081" -> "http://192.168.1.10:8081"
  static String normalize(String url) {
    var value = url.trim().replaceAll(RegExp(r'/+$'), '');
    if (!value.startsWith('http://') && !value.startsWith('https://')) {
      value = 'http://$value';
    }
    return value;
  }
}
