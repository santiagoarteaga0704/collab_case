import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../chat/chat_socket.dart';
import '../chat/chat_state.dart';
import '../main.dart';
import '../theme.dart';
import '../widgets/dictado.dart';

/// Chat del equipo con la IA, igual que `TeamChat.jsx` en la página del proyecto:
/// mensajes en vivo, mención @IA, modo privado, cola, nivel de ayuda y propuestas.
class TeamChatView extends StatefulWidget {
  const TeamChatView({super.key, required this.projectId, required this.role, required this.isOwner});

  final String projectId;
  final String role;
  final bool isOwner;

  @override
  State<TeamChatView> createState() => _TeamChatViewState();
}

class _TeamChatViewState extends State<TeamChatView> with AutomaticKeepAliveClientMixin {
  final _text = TextEditingController();
  final _scroll = ScrollController();
  ChatState _state = const ChatState();
  ChatSocket? _socket;
  bool _connected = false;
  bool _private = false;
  bool _loading = true;
  String _error = '';
  bool _started = false;

  @override
  bool get wantKeepAlive => true;

  ApiClient get _api => SessionScope.of(context).api;
  String get _base => '/projects/${widget.projectId}/chat';

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_started) {
      _started = true;
      _start();
    }
  }

  @override
  void dispose() {
    _socket?.close();
    _text.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _dispatch(Map<String, dynamic> event) {
    if (!mounted) return;
    final atBottom = !_scroll.hasClients || _scroll.position.extentAfter < 80;
    setState(() => _state = chatReducer(_state, event));
    if (atBottom) _toBottom();
  }

  void _toBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  Future<void> _start() async {
    final api = _api;
    final token = SessionScope.of(context).token!;
    try {
      // el historial se mezcla por id, así que lo que llegue en vivo antes no se pierde
      final results = await Future.wait([api.get(_base), api.get('$_base/status')]);
      final status = Map<String, dynamic>.from(results[1] as Map);
      _dispatch({'kind': 'history', 'messages': results[0]});
      _dispatch({'kind': 'ai_status', 'connected': status['aiConnected'], 'queued': status['queued']});
      _dispatch({'kind': 'help_level', 'level': status['helpLevel']});
    } on ApiException {
      if (mounted) setState(() => _error = 'No se pudo cargar el chat.');
    }
    if (!mounted) return;
    setState(() => _loading = false);
    _toBottom();
    _socket = ChatSocket(
      projectId: widget.projectId,
      token: token,
      onDisconnect: () {
        if (mounted) setState(() => _connected = false);
      },
      onReconnect: () async {
        // se recupera lo que se publicó mientras el teléfono estaba sin conexión
        try {
          _dispatch({'kind': 'history', 'messages': await api.get(_base)});
        } on ApiException {
          if (mounted) setState(() => _error = 'No se pudieron recuperar los mensajes nuevos del chat.');
        }
      },
      onEvent: (event) {
        switch (event['kind']) {
          case 'auth_ok':
            if (mounted) {
              setState(() {
                _connected = true;
                _error = '';
              });
            }
          case 'auth_error':
            if (mounted) {
              setState(() {
                _connected = false;
                _error = 'No se pudo conectar al chat.';
              });
            }
          case 'error':
            if (mounted) setState(() => _error = '${event['message'] ?? 'No se pudo enviar el mensaje.'}');
          case 'apply_changes':
            // desde la página del proyecto no se dibuja (igual que en la web)
            break;
          default:
            _dispatch(event);
        }
      },
    );
  }

  void _send() {
    final content = _text.text.trim();
    if (content.isEmpty) return;
    if (_socket?.send(content, private: _private) ?? false) {
      _text.clear();
      setState(() => _error = '');
      _toBottom();
    } else {
      setState(() => _error = 'Sin conexión con el chat. Inténtalo de nuevo en un momento.');
    }
  }

  Future<void> _changeLevel(String level) async {
    try {
      await _api.put('$_base/help-level', {'level': level});
      _dispatch({'kind': 'help_level', 'level': level});
    } on ApiException {
      if (mounted) setState(() => _error = 'No se pudo cambiar el nivel de ayuda.');
    }
  }

  void _insertMention() {
    final text = _text.text;
    if (mentionsAi(text)) return;
    _text.text = text.isEmpty ? '@IA ' : '@IA $text';
    _text.selection = TextSelection.collapsed(offset: _text.text.length);
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final me = SessionScope.of(context).user?.id;
    final aiLabel = !_state.aiConnected
        ? 'IA desconectada'
        : _state.queued > 0
            ? 'IA conectada · ${_state.queued} en cola'
            : 'IA conectada';
    return Column(children: [
      Container(
        color: Colores.sheet,
        padding: const EdgeInsets.fromLTRB(16, 6, 12, 6),
        child: Row(children: [
          Icon(Icons.smart_toy_outlined, size: 18, color: _state.aiConnected ? Colores.success : Colores.faint),
          const SizedBox(width: 6),
          Expanded(
            child: Text(aiLabel,
                style: TextStyle(fontWeight: FontWeight.w600, color: _state.aiConnected ? Colores.success : Colores.muted)),
          ),
          const Text('Ayuda ', style: TextStyle(color: Colores.muted)),
          if (widget.isOwner)
            DropdownButton<String>(
              value: nombresNivel.containsKey(_state.helpLevel) ? _state.helpLevel : null,
              underline: const SizedBox.shrink(),
              items: [for (final e in nombresNivel.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
              onChanged: (v) {
                if (v != null) _changeLevel(v);
              },
            )
          else
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Text(nombresNivel[_state.helpLevel] ?? _state.helpLevel, style: const TextStyle(fontWeight: FontWeight.w700)),
            ),
        ]),
      ),
      if (!_connected && !_loading)
        Container(
          width: double.infinity,
          color: Colores.collabTint,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
          child: const Text('Conectando con el chat…', style: TextStyle(color: Colores.collabInk, fontSize: 13)),
        ),
      const Divider(height: 1),
      Expanded(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _state.messages.isEmpty
                ? const Center(
                    child: Padding(
                      padding: EdgeInsets.all(32),
                      child: Text('Aún no hay mensajes. Escribe @IA para preguntarle a la IA.',
                          textAlign: TextAlign.center, style: TextStyle(color: Colores.muted)),
                    ),
                  )
                : ListView.builder(
                    controller: _scroll,
                    padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
                    itemCount: _state.messages.length,
                    itemBuilder: (context, i) => _Mensaje(message: _state.messages[i], state: _state, mine: _state.messages[i]['authorId'] == me),
                  ),
      ),
      if (_error.isNotEmpty)
        Container(
          width: double.infinity,
          color: Colores.dangerTint,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Text(_error, style: const TextStyle(color: Colores.danger)),
        ),
      Material(
        color: Colores.sheet,
        elevation: 4,
        child: SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Row(children: [
                FilterChip(
                  avatar: const Icon(Icons.lock_outline, size: 16),
                  label: const Text('Privado (solo tú y la IA)'),
                  selected: _private,
                  onSelected: (v) => setState(() => _private = v),
                ),
                const Spacer(),
                ActionChip(avatar: const Icon(Icons.alternate_email, size: 16), label: const Text('IA'), onPressed: _insertMention),
              ]),
              Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                BotonDictado(
                  enabled: _connected,
                  onTexto: (t) {
                    final current = _text.text.trim();
                    _text.text = current.isEmpty ? t : '$current $t';
                    _text.selection = TextSelection.collapsed(offset: _text.text.length);
                  },
                ),
                Expanded(
                  child: TextField(
                    controller: _text,
                    minLines: 1,
                    maxLines: 5,
                    maxLength: 2000,
                    textCapitalization: TextCapitalization.sentences,
                    decoration: InputDecoration(
                      hintText: _private ? 'Pregunta privada a la IA' : 'Escribe @IA para preguntarle a la IA',
                      counterText: '',
                    ),
                    onChanged: (_) => setState(() {}),
                  ),
                ),
                const SizedBox(width: 6),
                IconButton.filled(
                  tooltip: 'Enviar',
                  style: IconButton.styleFrom(backgroundColor: Colores.accent),
                  onPressed: _connected && _text.text.trim().isNotEmpty ? _send : null,
                  icon: const Icon(Icons.send),
                ),
              ]),
            ]),
          ),
        ),
      ),
    ]);
  }
}

class _Mensaje extends StatelessWidget {
  const _Mensaje({required this.message, required this.state, required this.mine});

  final Map<String, dynamic> message;
  final ChatState state;
  final bool mine;

  @override
  Widget build(BuildContext context) {
    final m = message;
    if (m['kind'] == 'NOTICE') {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Center(
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(color: Colores.accentTint, borderRadius: BorderRadius.circular(14)),
            child: Text('${m['content'] ?? ''}', textAlign: TextAlign.center, style: const TextStyle(color: Colores.accent, fontSize: 13)),
          ),
        ),
      );
    }
    final isAi = m['kind'] == 'AI';
    final private = m['isPrivate'] == true || m['private'] == true;
    final failed = m['status'] == 'FAILED';
    final content = '${m['content'] ?? ''}';
    final rawLabel = isAi ? aiStatusLabel(state, m) : '';
    final label = rawLabel == content.trim() ? '' : rawLabel;
    final names = proposalNames(m['proposal']);
    final proposalState = m['proposalState'];

    final Color fondo = failed
        ? Colores.dangerTint
        : isAi
            ? Colores.accentTint
            : mine
                ? const Color(0xFFE9ECE6)
                : Colores.sheet;
    return Align(
      alignment: mine && !isAi ? Alignment.centerRight : Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.86),
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 4),
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 10),
          decoration: BoxDecoration(
            color: fondo,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: isAi ? const Color(0xFFC5DDD8) : Colores.rule),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Wrap(spacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
              if (isAi) const Icon(Icons.smart_toy, size: 15, color: Colores.accent),
              Text(isAi ? 'IA · senior' : '${m['authorName'] ?? ''}',
                  style: TextStyle(fontWeight: FontWeight.w700, color: isAi ? Colores.accent : Colores.ink, fontSize: 13)),
              if (isAi && m['requestedByName'] != null)
                Text('para ${m['requestedByName']}', style: const TextStyle(color: Colores.muted, fontSize: 12)),
              Text(_hora(m['createdAt']), style: const TextStyle(color: Colores.faint, fontSize: 12)),
              if (private)
                const Row(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.lock_outline, size: 12, color: Colores.collabInk),
                  Text(' privado', style: TextStyle(color: Colores.collabInk, fontSize: 12)),
                ]),
            ]),
            if (content.isNotEmpty) ...[
              const SizedBox(height: 4),
              SelectableText(content, style: TextStyle(color: failed ? Colores.danger : Colores.ink, height: 1.35)),
            ],
            if (label.isNotEmpty) ...[
              const SizedBox(height: 4),
              Row(mainAxisSize: MainAxisSize.min, children: [
                if (m['status'] == 'WRITING' || m['status'] == 'QUEUED')
                  const Padding(
                    padding: EdgeInsets.only(right: 6),
                    child: SizedBox(width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 1.5)),
                  ),
                Text(label, style: const TextStyle(color: Colores.muted, fontSize: 12, fontStyle: FontStyle.italic)),
              ]),
            ],
            if (names.isNotEmpty) ...[
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(8), border: Border.all(color: Colores.rule)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(proposalState == 'APPLIED' ? 'La IA agregó:' : 'La IA propone agregar:',
                      style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
                  for (final n in names) Text('• $n', style: const TextStyle(fontSize: 13)),
                  if (proposalState == 'PENDING')
                    const Padding(
                      padding: EdgeInsets.only(top: 4),
                      child: Text('Ábrelo en el editor del diagrama para aplicarlo.', style: TextStyle(color: Colores.muted, fontSize: 12)),
                    ),
                  if (proposalState == 'NONE') const Text('Aplicando…', style: TextStyle(color: Colores.muted, fontSize: 12)),
                  if (proposalState == 'APPLIED') const Text('Aplicado', style: TextStyle(color: Colores.success, fontSize: 12)),
                ]),
              ),
            ],
          ]),
        ),
      ),
    );
  }

  static String _hora(dynamic value) {
    final date = value is String ? DateTime.tryParse(value) : null;
    if (date == null) return '';
    final local = date.toLocal();
    return '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';
  }
}
