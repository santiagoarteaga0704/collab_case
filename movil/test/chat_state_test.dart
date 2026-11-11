// Las mismas pruebas que frontend/src/services/chatState.test.js: el teléfono y la web deben
// mostrar el chat del equipo exactamente igual.
import 'package:collab_case_movil/chat/chat_state.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> msg(String id, [Map<String, dynamic> extra = const {}]) =>
    {'id': id, 'content': '', 'status': 'COMPLETE', 'kind': 'AI', ...extra};

List<String> ids(ChatState s) => s.messages.map((m) => m['id'] as String).toList();

void main() {
  const initial = ChatState();

  test('chunksForDifferentMessagesDoNotMix', () {
    var s = chatReducer(initial, {'kind': 'history', 'messages': [msg('a'), msg('b')]});
    s = chatReducer(s, {'kind': 'chunk', 'messageId': 'a', 'text': 'Ho'});
    s = chatReducer(s, {'kind': 'chunk', 'messageId': 'b', 'text': 'Adiós'});
    s = chatReducer(s, {'kind': 'chunk', 'messageId': 'a', 'text': 'la'});
    expect(s.messages.firstWhere((m) => m['id'] == 'a')['content'], 'Hola');
    expect(s.messages.firstWhere((m) => m['id'] == 'b')['content'], 'Adiós');
  });

  test('messageUpdatedReplacesContentButKeepsOrder', () {
    var s = chatReducer(initial, {'kind': 'history', 'messages': [msg('a', {'content': 'x'}), msg('b')]});
    s = chatReducer(s, {'kind': 'message_updated', 'message': msg('a', {'content': 'final'})});
    expect(ids(s), ['a', 'b']);
    expect(s.messages[0]['content'], 'final');
  });

  test('historyThenLiveMessagesDeduplicateById', () {
    var s = chatReducer(initial, {'kind': 'history', 'messages': [msg('a')]});
    s = chatReducer(s, {'kind': 'message', 'message': msg('a', {'content': 'again'})});
    s = chatReducer(s, {'kind': 'message', 'message': msg('c')});
    expect(ids(s), ['a', 'c']);
    expect(s.messages[0]['content'], 'again');
  });

  test('aiStatusLabelShowsQueuePosition', () {
    final s = chatReducer(initial, {'kind': 'queue_position', 'messageId': 'a', 'position': 2});
    expect(aiStatusLabel(s, msg('a', {'status': 'QUEUED'})), 'en cola, posición 2');
    expect(aiStatusLabel(s, msg('a', {'status': 'WRITING'})), 'escribiendo…');
    expect(aiStatusLabel(s, msg('a', {'status': 'FAILED', 'content': 'IA desconectada'})), 'IA desconectada');
    expect(aiStatusLabel(s, msg('a', {'status': 'FAILED', 'content': 'La IA no respondió a tiempo'})),
        'La IA no respondió a tiempo');
    expect(aiStatusLabel(s, msg('a')), '');
  });

  test('aiStatusAndHelpLevelEventsUpdateState', () {
    var s = chatReducer(initial, {'kind': 'ai_status', 'connected': true, 'queued': 3});
    s = chatReducer(s, {'kind': 'help_level', 'level': 'SUGGEST'});
    expect(s.aiConnected, true);
    expect(s.queued, 3);
    expect(s.helpLevel, 'SUGGEST');
  });

  test('mentionsAiMatchesOnlyTheMention', () {
    expect(mentionsAi('@IA resumen'), true);
    expect(mentionsAi('hola @ia ayuda'), true);
    expect(mentionsAi('@ia'), true);
    expect(mentionsAi('mail@ia.com'), false);
    expect(mentionsAi('@iaaa'), false);
    expect(mentionsAi('hola'), false);
  });

  test('historyMergesWithMessagesAlreadyShown', () {
    var s = chatReducer(initial, {
      'kind': 'history',
      'messages': [msg('a', {'createdAt': '2026-10-23T12:00:00.000001Z'}), msg('b', {'createdAt': '2026-10-23T12:00:01Z'})],
    });
    s = chatReducer(s, {'kind': 'message', 'message': msg('d', {'createdAt': '2026-10-23T12:00:05Z'})});
    s = chatReducer(s, {
      'kind': 'history',
      'messages': [
        msg('b', {'createdAt': '2026-10-23T12:00:01Z', 'content': 'final'}),
        msg('c', {'createdAt': '2026-10-23T12:00:03Z'}),
      ],
    });
    expect(ids(s), ['a', 'b', 'c', 'd']);
    expect(s.messages.firstWhere((m) => m['id'] == 'b')['content'], 'final');
  });

  test('liveMessageBeforeHistoryIsNotDropped', () {
    var s = chatReducer(initial, {'kind': 'message', 'message': msg('z', {'createdAt': '2026-10-23T12:00:09Z'})});
    s = chatReducer(s, {'kind': 'history', 'messages': [msg('a', {'createdAt': '2026-10-23T12:00:00Z'})]});
    expect(ids(s), ['a', 'z']);
  });

  test('historyOrdersBySubMillisecondTimeThenId', () {
    final s = chatReducer(initial, {
      'kind': 'history',
      'messages': [
        msg('q', {'createdAt': '2026-10-23T12:00:00.000300Z'}),
        msg('p', {'createdAt': '2026-10-23T12:00:00.0002Z'}),
        msg('y', {'createdAt': '2026-10-23T12:00:00.0003Z'}),
      ],
    });
    expect(ids(s), ['p', 'q', 'y']);
  });

  test('queuePositionIsClearedWhenTheAnswerStarts', () {
    var s = chatReducer(initial, {'kind': 'queue_position', 'messageId': 'a', 'position': 3});
    s = chatReducer(s, {'kind': 'message_updated', 'message': msg('a', {'status': 'WRITING'})});
    expect(s.positions.containsKey('a'), false);
  });

  test('proposalNamesUsesTheNameTheServerAccepts', () {
    expect(
      proposalNames([
        {'type': 'ADD_CLASS', 'className': 'Venta'},
        {'type': 'ADD_ATTRIBUTE', 'attributeName': 'total'},
        {'type': 'ADD_RELATIONSHIP', 'description': 'Venta - DetalleVenta'},
      ]),
      ['Venta', 'total', 'Venta - DetalleVenta'],
    );
    expect(proposalNames(null), isEmpty);
  });
}
