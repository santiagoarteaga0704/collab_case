import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';

import '../config.dart';

class ApiException implements Exception {
  ApiException(this.message, {this.status});

  final String message;
  final int? status;

  @override
  String toString() => message;
}

/// Cliente REST del backend. Mismo contrato que `frontend/src/api`.
class ApiClient {
  ApiClient({http.Client? client, String? baseUrl})
      : _client = client ?? http.Client(),
        baseUrl = baseUrl ?? servidor;

  final http.Client _client;
  final String baseUrl;
  String? token;

  /// Se llama cuando el servidor responde 401 (sesión vencida).
  void Function()? onUnauthorized;

  Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        if (token != null) 'Authorization': 'Bearer $token',
      };

  Uri _uri(String path) => Uri.parse('$baseUrl/api$path');

  Future<dynamic> get(String path) => _send(() => _client.get(_uri(path), headers: _headers));

  Future<dynamic> post(String path, [Object? body]) =>
      _send(() => _client.post(_uri(path), headers: _headers, body: jsonEncode(body ?? {})));

  Future<dynamic> put(String path, [Object? body]) =>
      _send(() => _client.put(_uri(path), headers: _headers, body: jsonEncode(body ?? {})));

  Future<dynamic> patch(String path, [Object? body]) =>
      _send(() => _client.patch(_uri(path), headers: _headers, body: jsonEncode(body ?? {})));

  Future<dynamic> delete(String path) => _send(() => _client.delete(_uri(path), headers: _headers));

  /// Sube un archivo (multipart), por ejemplo el audio a `/speech/transcribe`.
  Future<dynamic> upload(String path, String field, List<int> bytes, String filename, String contentType) {
    return _send(() async {
      final request = http.MultipartRequest('POST', _uri(path))
        ..files.add(http.MultipartFile.fromBytes(field, bytes, filename: filename, contentType: MediaType.parse(contentType)));
      if (token != null) request.headers['Authorization'] = 'Bearer $token';
      return http.Response.fromStream(await _client.send(request));
    });
  }

  Future<dynamic> _send(Future<http.Response> Function() call) async {
    http.Response response;
    try {
      response = await call().timeout(const Duration(seconds: 70));
    } catch (_) {
      throw ApiException('No se pudo conectar con el servidor.');
    }
    final text = utf8.decode(response.bodyBytes);
    dynamic data;
    if (text.isNotEmpty) {
      try {
        data = jsonDecode(text);
      } catch (_) {
        data = text;
      }
    }
    if (response.statusCode >= 200 && response.statusCode < 300) return data;
    if (response.statusCode == 401) onUnauthorized?.call();
    throw ApiException(errorMessage(response.statusCode, data), status: response.statusCode);
  }

  static String errorMessage(int status, dynamic data) {
    if (data is Map) {
      final detail = data['detail'] ?? data['message'];
      if (detail is String && detail.isNotEmpty) return detail;
    }
    switch (status) {
      case 400:
        return 'La solicitud contiene datos inválidos.';
      case 401:
        return 'Tu sesión expiró. Inicia sesión nuevamente.';
      case 403:
        return 'No tienes permisos para realizar esta acción.';
      case 404:
        return 'No se encontró el recurso solicitado.';
      case 409:
        return 'La operación entra en conflicto con el estado actual.';
    }
    return status >= 500 ? 'El servidor no pudo completar la operación.' : 'No se pudo completar la operación.';
  }
}
