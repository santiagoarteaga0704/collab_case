import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api/api_client.dart';
import 'api/models.dart';

/// Sesión del usuario: token guardado en el teléfono y el usuario actual.
class Session extends ChangeNotifier {
  Session(this.api) {
    api.onUnauthorized = () {
      if (user != null) logout();
    };
  }

  static const _tokenKey = 'collab-case-token';

  final ApiClient api;
  Usuario? user;
  bool loading = true;

  String? get token => api.token;

  Future<void> restore() async {
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getString(_tokenKey);
    if (saved != null) {
      api.token = saved;
      try {
        user = Usuario.fromJson(Map<String, dynamic>.from(await api.get('/auth/me') as Map));
      } on ApiException catch (e) {
        // sin red se mantiene la sesión guardada; solo se borra si el token ya no vale
        if (e.status == 401) {
          api.token = null;
          await prefs.remove(_tokenKey);
        }
      }
    }
    loading = false;
    notifyListeners();
  }

  Future<void> login(String email, String password) =>
      _authenticate('/auth/login', {'email': email.trim(), 'password': password});

  Future<void> register(String name, String email, String password) =>
      _authenticate('/auth/register', {'name': name.trim(), 'email': email.trim(), 'password': password});

  Future<void> _authenticate(String path, Map<String, String> body) async {
    final data = Map<String, dynamic>.from(await api.post(path, body) as Map);
    api.token = data['token'] as String;
    user = Usuario.fromJson(Map<String, dynamic>.from(data['user'] as Map));
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_tokenKey, api.token!);
    notifyListeners();
  }

  Future<void> logout() async {
    api.token = null;
    user = null;
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_tokenKey);
    notifyListeners();
  }
}
