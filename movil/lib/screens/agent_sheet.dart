import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../main.dart';
import '../theme.dart';
import '../widgets/dictado.dart';

const _rapidas = ['Crear un diagrama', '¿Cómo agrego una clase?', '¿Cómo hago una relación N:M?', '¿Cómo genero código?'];

/// Agente de ayuda (`CollabCaseAgent.jsx`): explica cómo usar Collab CASE y hace acciones.
/// Devuelve true si alguna acción cambió datos (para recargar la pantalla).
Future<bool> abrirAgente(BuildContext context, {Map<String, dynamic> contexto = const {}}) async {
  final changed = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: Colores.sheet,
    builder: (_) => FractionallySizedBox(heightFactor: 0.88, child: _Agente(contexto: contexto)),
  );
  return changed ?? false;
}

class _Item {
  _Item(this.user, this.text, {this.actions = const []});

  final bool user;
  final String text;
  final List<Map<String, dynamic>> actions;
  final Map<String, bool> done = {};
}

class _Agente extends StatefulWidget {
  const _Agente({required this.contexto});

  final Map<String, dynamic> contexto;

  @override
  State<_Agente> createState() => _AgenteState();
}

class _AgenteState extends State<_Agente> {
  final _text = TextEditingController();
  final _scroll = ScrollController();
  final List<_Item> _items = [];
  Map<String, dynamic>? _status;
  bool _busy = false;
  bool _changed = false;
  String _error = '';

  ApiClient get _api => SessionScope.of(context).api;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_status == null) {
      _api.get('/agent/status').then((s) {
        if (mounted) setState(() => _status = Map<String, dynamic>.from(s as Map));
      }).catchError((_) {
        if (mounted) setState(() => _status = {'available': false});
      });
    }
  }

  @override
  void dispose() {
    _text.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _toBottom() => WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scroll.hasClients) _scroll.animateTo(_scroll.position.maxScrollExtent, duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
      });

  Future<void> _send([String? preset]) async {
    final value = (preset ?? _text.text).trim();
    if (value.isEmpty || _busy) return;
    final history = _items.reversed.take(6).toList().reversed
        .map((i) => {'role': i.user ? 'USER' : 'AGENT', 'content': i.text})
        .toList();
    setState(() {
      _text.clear();
      _error = '';
      _items.add(_Item(true, value));
      _busy = true;
    });
    _toBottom();
    try {
      final data = Map<String, dynamic>.from(await _api.post('/agent/chat', {
        'message': value,
        'context': {'route': '/', ...widget.contexto},
        'history': history,
      }) as Map);
      final actions = ((data['actions'] as List?) ?? const []).map((a) => Map<String, dynamic>.from(a as Map)).toList();
      final item = _Item(false, '${data['message'] ?? ''}', actions: actions);
      setState(() => _items.add(item));
      for (final action in actions) {
        if (action['confirmationRequired'] != true) await _execute(item, action);
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message.isEmpty ? 'No se pudo contactar con el agente.' : e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
      _toBottom();
    }
  }

  Future<void> _execute(_Item item, Map<String, dynamic> action) async {
    final name = '${action['name']}';
    final args = Map<String, dynamic>.from((action['arguments'] as Map?) ?? const {});
    final projectId = widget.contexto['projectId'] as String?;
    String result;
    bool ok = false;
    try {
      switch (name) {
        case 'CREATE_PROJECT':
          final p = await _api.post('/projects', {'name': args['name'], 'description': args['description'] ?? ''}) as Map;
          result = 'Proyecto ${p['name']} creado correctamente.';
          ok = true;
        case 'RENAME_PROJECT' when projectId != null:
          final p = await _api.patch('/projects/$projectId', {'name': args['name']}) as Map;
          result = 'Proyecto renombrado a ${p['name']}.';
          ok = true;
        case 'CREATE_VERSION' when projectId != null:
          final v = await _api.post('/projects/$projectId/versions',
              {'name': args['name'], 'description': args['description'] ?? '', 'sourceVersionId': widget.contexto['versionId']}) as Map;
          result = 'Versión ${v['name']} creada correctamente.';
          ok = true;
        case 'CREATE_DIAGRAM' when projectId != null && widget.contexto['versionId'] != null:
          final diagramName = '${args['name'] ?? 'Nuevo diagrama'}'.trim();
          final model = {
            'id': '${DateTime.now().microsecondsSinceEpoch}',
            'version': '4.2.0',
            'title': diagramName,
            'type': 'ClassDiagram',
            'nodes': [],
            'edges': [],
            'assessments': [],
          };
          final d = await _api.post('/projects/$projectId/versions/${widget.contexto['versionId']}/diagrams',
              {'name': diagramName, 'type': 'ClassDiagram', 'model': model}) as Map;
          result = 'Diagrama ${d['name']} creado correctamente.';
          ok = true;
        case 'CREATE_PROJECT' || 'RENAME_PROJECT' || 'CREATE_VERSION' || 'CREATE_DIAGRAM' || 'SWITCH_VERSION':
          result = 'Abre el proyecto donde quieres hacerlo y vuelve a pedírmelo.';
        case 'NAVIGATE':
          result = 'Usa las pantallas de la app para ir ahí.';
        default:
          result = 'Esa acción se hace dentro del editor del diagrama: ábrelo y pídesela al agente desde ahí.';
      }
    } on ApiException catch (e) {
      result = 'No pude completar la acción: ${e.message}';
    }
    if (!mounted) return;
    setState(() {
      item.done[name] = ok;
      if (ok) _changed = true;
      _items.add(_Item(false, ok ? '✓ $result' : result));
    });
    _toBottom();
  }

  @override
  Widget build(BuildContext context) {
    final available = _status?['available'] == true;
    final statusText = _status == null
        ? 'Comprobando…'
        : available
            ? (_status!['provider'] == 'ollama' ? 'Modelo local conectado' : 'Modelo conectado')
            : 'Sin modelo disponible';
    final contextLabel = [widget.contexto['projectName'], widget.contexto['versionName']].whereType<String>().join(' / ');
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) Navigator.of(context).pop(_changed);
      },
      child: Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
        child: Column(children: [
          ListTile(
            title: const Text('Agente de ayuda', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 18)),
            subtitle: Row(children: [
              Icon(Icons.circle, size: 9, color: available ? Colores.success : Colores.faint),
              const SizedBox(width: 6),
              Text(statusText),
            ]),
            trailing: IconButton(tooltip: 'Cerrar agente', icon: const Icon(Icons.close), onPressed: () => Navigator.of(context).pop(_changed)),
          ),
          Container(
            width: double.infinity,
            margin: const EdgeInsets.symmetric(horizontal: 16),
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(color: Colores.paper, borderRadius: BorderRadius.circular(8)),
            child: Text.rich(TextSpan(children: [
              const TextSpan(text: 'Contexto actual: ', style: TextStyle(color: Colores.muted)),
              TextSpan(text: contextLabel.isEmpty ? 'Todos los proyectos' : contextLabel, style: const TextStyle(fontWeight: FontWeight.w600)),
            ])),
          ),
          Expanded(
            child: ListView(
              controller: _scroll,
              padding: const EdgeInsets.all(16),
              children: [
                if (_items.isEmpty) ...[
                  const Text('Te explico cómo usar Collab CASE o hago acciones por ti. Por ejemplo:', style: TextStyle(color: Colores.muted)),
                  const SizedBox(height: 10),
                  Wrap(spacing: 8, runSpacing: 8, children: [for (final q in _rapidas) ActionChip(label: Text(q), onPressed: () => _send(q))]),
                ],
                for (final item in _items)
                  Align(
                    alignment: item.user ? Alignment.centerRight : Alignment.centerLeft,
                    child: Container(
                      margin: const EdgeInsets.symmetric(vertical: 4),
                      padding: const EdgeInsets.all(10),
                      constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.85),
                      decoration: BoxDecoration(
                        color: item.user ? const Color(0xFFE9ECE6) : Colores.accentTint,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(item.user ? 'Tú' : 'Agente', style: const TextStyle(fontSize: 12, color: Colores.muted, fontWeight: FontWeight.w600)),
                        const SizedBox(height: 2),
                        SelectableText(item.text),
                        for (final a in item.actions)
                          Padding(
                            padding: const EdgeInsets.only(top: 6),
                            child: Row(children: [
                              Expanded(child: Text('${a['name']}'.replaceAll('_', ' '), style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13))),
                              if (item.done.containsKey('${a['name']}'))
                                Icon(item.done['${a['name']}']! ? Icons.check_circle : Icons.info_outline,
                                    size: 18, color: item.done['${a['name']}']! ? Colores.success : Colores.muted)
                              else
                                TextButton(
                                  onPressed: _busy ? null : () => _execute(item, a),
                                  child: Text(a['confirmationRequired'] == true ? 'Confirmar' : 'Ejecutar'),
                                ),
                            ]),
                          ),
                      ]),
                    ),
                  ),
                if (_busy)
                  const Padding(
                    padding: EdgeInsets.all(8),
                    child: Row(children: [
                      SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                      SizedBox(width: 8),
                      Text('Pensando…', style: TextStyle(color: Colores.muted)),
                    ]),
                  ),
              ],
            ),
          ),
          if (_error.isNotEmpty)
            Container(
              width: double.infinity,
              color: Colores.dangerTint,
              padding: const EdgeInsets.all(10),
              child: Text(_error, style: const TextStyle(color: Colores.danger)),
            ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(8, 6, 8, 8),
              child: Row(children: [
                BotonDictado(onTexto: (t) => setState(() => _text.text = t)),
                Expanded(
                  child: TextField(
                    controller: _text,
                    minLines: 1,
                    maxLines: 4,
                    maxLength: 2000,
                    decoration: const InputDecoration(hintText: 'Escribe tu pregunta...', counterText: ''),
                    onChanged: (_) => setState(() {}),
                  ),
                ),
                const SizedBox(width: 6),
                IconButton.filled(
                  tooltip: 'Enviar',
                  style: IconButton.styleFrom(backgroundColor: Colores.accent),
                  onPressed: _busy || _text.text.trim().isEmpty ? null : _send,
                  icon: const Icon(Icons.send),
                ),
              ]),
            ),
          ),
        ]),
      ),
    );
  }
}
