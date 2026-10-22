import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../config/app_config.dart';
import '../../models/pending_mutation.dart';

class MobileApiClient {
  MobileApiClient({http.Client? client, String? baseUrl})
    : _client = client ?? http.Client(),
      _baseUrl = (baseUrl ?? AppConfig.backendBaseUrl).replaceAll(
        RegExp(r'/$'),
        '',
      );

  final http.Client _client;
  final String _baseUrl;
  static const _requestTimeout = Duration(seconds: 10);

  Map<String, String> get _headers => {
    'Accept': 'application/json',
    'Content-Type': 'application/json',
    if (AppConfig.mobileApiToken.isNotEmpty)
      'X-Mobile-Token': AppConfig.mobileApiToken,
  };

  Future<Map<String, dynamic>> fetchDefinition() async {
    final response = await _client
        .get(Uri.parse('$_baseUrl/api/mobile/definition'), headers: _headers)
        .timeout(_requestTimeout);
    return _decode(response);
  }

  Future<Map<String, dynamic>> fetchChanges(int after) async {
    final response = await _client
        .get(
          Uri.parse('$_baseUrl/api/mobile/changes?after=$after'),
          headers: _headers,
        )
        .timeout(_requestTimeout);
    return _decode(response);
  }

  Future<Map<String, dynamic>> sync({
    required String deviceId,
    required int lastChangeId,
    required List<PendingMutation> mutations,
  }) async {
    final response = await _client
        .post(
          Uri.parse('$_baseUrl/api/mobile/sync'),
          headers: _headers,
          body: jsonEncode({
            'deviceId': deviceId,
            'lastChangeId': lastChangeId,
            'mutations': mutations.map((value) => value.toApiJson()).toList(),
          }),
        )
        .timeout(_requestTimeout);
    return _decode(response);
  }

  Map<String, dynamic> _decode(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw http.ClientException(
        'HTTP ${response.statusCode}',
        response.request?.url,
      );
    }
    return Map<String, dynamic>.from(jsonDecode(response.body) as Map);
  }
}
