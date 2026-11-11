/// Estado del chat del equipo. Es la misma lógica que `frontend/src/services/chatState.js`,
/// para que el teléfono y la web muestren exactamente lo mismo.
class ChatState {
  const ChatState({
    this.messages = const [],
    this.aiConnected = false,
    this.queued = 0,
    this.helpLevel = 'DRAW',
    this.positions = const {},
  });

  final List<Map<String, dynamic>> messages;
  final bool aiConnected;
  final int queued;
  final String helpLevel;
  final Map<String, int> positions;

  ChatState copyWith({
    List<Map<String, dynamic>>? messages,
    bool? aiConnected,
    int? queued,
    String? helpLevel,
    Map<String, int>? positions,
  }) =>
      ChatState(
        messages: messages ?? this.messages,
        aiConnected: aiConnected ?? this.aiConnected,
        queued: queued ?? this.queued,
        helpLevel: helpLevel ?? this.helpLevel,
        positions: positions ?? this.positions,
      );
}

final RegExp _mention = RegExp(r'(^|\s)@ia\b', caseSensitive: false);

bool mentionsAi(String text) => _mention.hasMatch(text);

List<Map<String, dynamic>> _upsert(List<Map<String, dynamic>> messages, Map<String, dynamic> message) {
  final index = messages.indexWhere((m) => m['id'] == message['id']);
  if (index == -1) return [...messages, message];
  final next = [...messages];
  next[index] = message;
  return next;
}

const int _sinFecha = 1 << 52;

// createdAt es un instante ISO que puede traer micro o nanosegundos.
(int, int) _timeOf(dynamic value) {
  if (value is num) return (value.toInt(), 0);
  if (value is! String) return (_sinFecha, 0);
  final fraction = RegExp(r'\.(\d+)').firstMatch(value);
  final parsed = DateTime.tryParse(value.replaceFirst(RegExp(r'\.\d+'), ''));
  if (parsed == null) return (_sinFecha, 0);
  final nanos = fraction == null ? 0 : int.parse(fraction.group(1)!.padRight(9, '0').substring(0, 9));
  return (parsed.millisecondsSinceEpoch, nanos);
}

int _byTimeThenId(Map<String, dynamic> a, Map<String, dynamic> b) {
  final (ma, na) = _timeOf(a['createdAt']);
  final (mb, nb) = _timeOf(b['createdAt']);
  if (ma != mb) return ma < mb ? -1 : 1;
  if (na != nb) return na - nb;
  return '${a['id']}'.compareTo('${b['id']}');
}

ChatState chatReducer(ChatState state, Map<String, dynamic> event) {
  switch (event['kind']) {
    case 'history':
      // se mezcla, nunca se reemplaza: lo que llegó en vivo antes del historial se conserva
      final merged = ((event['messages'] as List?) ?? const [])
          .map((m) => Map<String, dynamic>.from(m as Map))
          .fold<List<Map<String, dynamic>>>([...state.messages], _upsert)
        ..sort(_byTimeThenId);
      return state.copyWith(messages: merged);
    case 'message':
    case 'message_updated':
      final raw = event['message'];
      if (raw is! Map) return state;
      final message = Map<String, dynamic>.from(raw);
      final positions = {...state.positions};
      if (message['status'] != 'QUEUED') positions.remove(message['id']);
      return state.copyWith(messages: _upsert(state.messages, message), positions: positions);
    case 'chunk':
      final id = event['messageId'];
      if (!state.messages.any((m) => m['id'] == id)) return state;
      return state.copyWith(
        messages: [
          for (final m in state.messages)
            m['id'] == id ? {...m, 'content': '${m['content'] ?? ''}${event['text'] ?? ''}'} : m,
        ],
      );
    case 'queue_position':
      return state.copyWith(
        positions: {...state.positions, '${event['messageId']}': (event['position'] as num).toInt()},
      );
    case 'ai_status':
      return state.copyWith(
        aiConnected: event['connected'] == true,
        queued: (event['queued'] as num?)?.toInt() ?? 0,
      );
    case 'help_level':
      return state.copyWith(helpLevel: '${event['level']}');
    default:
      return state;
  }
}

// Son los textos que el servidor guarda como contenido de una respuesta fallida.
const _offlineText = 'IA desconectada';
const _timeoutText = 'La IA no respondió a tiempo';

String aiStatusLabel(ChatState state, Map<String, dynamic> message) {
  switch (message['status']) {
    case 'QUEUED':
      return 'en cola, posición ${state.positions[message['id']] ?? 1}';
    case 'WRITING':
      return 'escribiendo…';
    case 'FAILED':
      final content = '${message['content'] ?? ''}'.trim();
      if (content == _offlineText || content == _timeoutText) return content;
  }
  return '';
}

/// Nombres de lo que propone la IA (mismo criterio que la web).
List<String> proposalNames(dynamic proposal) {
  if (proposal is! List) return const [];
  final names = <String>[];
  for (final s in proposal.whereType<Map>()) {
    final name = s['className'] ?? s['attributeName'] ?? s['methodName'] ?? s['description'] ?? s['title'];
    if (name is String && name.isNotEmpty) names.add(name);
  }
  return names;
}

const Map<String, String> nombresNivel = {'EXPLAIN': 'Explicar', 'SUGGEST': 'Sugerir', 'DRAW': 'Dibujar'};
