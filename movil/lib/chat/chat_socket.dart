import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:web_socket_channel/web_socket_channel.dart';

import '../config.dart';

/// WebSocket del chat del equipo (`/ws/chat/{projectId}`), con reconexión como en la web.
class ChatSocket {
  ChatSocket({
    required this.projectId,
    required this.token,
    required this.onEvent,
    this.onDisconnect,
    this.onReconnect,
    String? baseUrl,
  }) : _baseUrl = baseUrl ?? servidor.replaceFirst(RegExp('^http'), 'ws') {
    _connect();
  }

  final String projectId;
  final String token;
  final void Function(Map<String, dynamic> event) onEvent;
  final void Function()? onDisconnect;
  final void Function()? onReconnect;
  final String _baseUrl;

  WebSocketChannel? _channel;
  StreamSubscription? _subscription;
  Timer? _retry;
  Duration _delay = const Duration(seconds: 1);
  bool _stopped = false;
  bool _terminal = false;
  bool _authenticated = false;
  bool _everAuthenticated = false;

  bool get authenticated => _authenticated;

  Future<void> _connect() async {
    if (_stopped || _terminal) return;
    final channel = WebSocketChannel.connect(Uri.parse('$_baseUrl/ws/chat/$projectId'));
    _channel = channel;
    try {
      await channel.ready;
    } catch (_) {
      _closed(null);
      return;
    }
    if (_stopped) {
      channel.sink.close(1000);
      return;
    }
    _subscription = channel.stream.listen(
      (data) {
        if (_stopped) return;
        Map<String, dynamic> message;
        try {
          message = jsonDecode(data as String) as Map<String, dynamic>;
        } catch (_) {
          return;
        }
        final reconnected = message['kind'] == 'auth_ok' && _everAuthenticated;
        if (message['kind'] == 'auth_ok') {
          _authenticated = true;
          _everAuthenticated = true;
          _delay = const Duration(seconds: 1);
        } else if (message['kind'] == 'auth_error') {
          _terminal = true;
          _authenticated = false;
          channel.sink.close();
        }
        onEvent(message);
        if (reconnected) onReconnect?.call();
      },
      onDone: () => _closed(channel.closeCode),
      onError: (_) => _closed(channel.closeCode),
      cancelOnError: true,
    );
    channel.sink.add(jsonEncode({'kind': 'auth', 'token': token}));
  }

  void _closed(int? code) {
    _authenticated = false;
    _subscription?.cancel();
    _subscription = null;
    if (_stopped) return;
    onDisconnect?.call();
    if (code != null && const [4001, 4003, 4004].contains(code)) _terminal = true;
    if (_terminal) return;
    _retry?.cancel();
    _retry = Timer(_delay, _connect);
    _delay = Duration(milliseconds: min(_delay.inMilliseconds * 2, 10000));
  }

  bool _send(Map<String, dynamic> message) {
    if (!_authenticated || _channel == null) return false;
    _channel!.sink.add(jsonEncode(message));
    return true;
  }

  bool send(String content, {bool private = false}) =>
      _send({'kind': 'send', 'content': content, 'private': private});

  bool applyProposal(String messageId) => _send({'kind': 'apply_proposal', 'messageId': messageId});

  void close() {
    if (_stopped) return;
    _stopped = true;
    _authenticated = false;
    _retry?.cancel();
    _subscription?.cancel();
    _channel?.sink.close(1000);
  }
}
