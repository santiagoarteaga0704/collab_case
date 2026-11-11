import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../main.dart';
import '../theme.dart';
import '../widgets/comunes.dart';

/// Miembros del proyecto (solo el propietario los ve y los administra).
class MembersTab extends StatefulWidget {
  const MembersTab({super.key, required this.projectId});

  final String projectId;

  @override
  State<MembersTab> createState() => _MembersTabState();
}

class _MembersTabState extends State<MembersTab> with AutomaticKeepAliveClientMixin {
  final _email = TextEditingController();
  String _role = 'EDITOR';
  List<Integrante>? _members;
  String _error = '';
  bool _busy = false;

  ApiClient get _api => SessionScope.of(context).api;
  String get _base => '/projects/${widget.projectId}/members';

  @override
  bool get wantKeepAlive => true;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_members == null && _error.isEmpty) _load();
  }

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final data = await _api.get(_base) as List;
      if (mounted) {
        setState(() {
          _members = data.map((m) => Integrante.fromJson(Map<String, dynamic>.from(m as Map))).toList();
          _error = '';
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _do(Future<void> Function() work, String fallback) async {
    setState(() => _busy = true);
    try {
      await work();
      await _load();
    } on ApiException catch (e) {
      if (mounted) avisar(context, e.message.isEmpty ? fallback : e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _add() {
    final email = _email.text.trim();
    if (!RegExp(r'^\S+@\S+\.\S+$').hasMatch(email)) {
      avisar(context, 'Escribe el correo de un usuario registrado.');
      return;
    }
    FocusScope.of(context).unfocus();
    _do(() async {
      await _api.post(_base, {'email': email, 'role': _role});
      _email.clear();
    }, 'No se pudo agregar el miembro.');
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final members = _members;
    if (members == null) {
      return _error.isEmpty ? const Center(child: CircularProgressIndicator()) : ErrorConReintento(mensaje: _error, onRetry: _load);
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: [
          Text('Miembros (${members.length})', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          const Text('Los editores pueden modificar diagramas y versiones. Los lectores solo pueden verlos.',
              style: TextStyle(color: Colores.muted)),
          const SizedBox(height: 14),
          TextField(
            controller: _email,
            keyboardType: TextInputType.emailAddress,
            autocorrect: false,
            decoration: const InputDecoration(labelText: 'Correo del usuario', hintText: 'email@ejemplo.com'),
            onSubmitted: (_) => _add(),
          ),
          const SizedBox(height: 10),
          Row(children: [
            Expanded(
              child: SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'EDITOR', label: Text('Editor')),
                  ButtonSegment(value: 'VIEWER', label: Text('Lector')),
                ],
                selected: {_role},
                onSelectionChanged: (s) => setState(() => _role = s.first),
              ),
            ),
            const SizedBox(width: 10),
            FilledButton.icon(onPressed: _busy ? null : _add, icon: const Icon(Icons.person_add_alt, size: 18), label: const Text('Agregar')),
          ]),
          const SizedBox(height: 18),
          for (final m in members)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Card(
                child: ListTile(
                  leading: CircleAvatar(
                    backgroundColor: Colores.accentTint,
                    child: Text(iniciales(m.name), style: const TextStyle(color: Colores.accent, fontWeight: FontWeight.w700, fontSize: 13)),
                  ),
                  title: Text(m.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text(m.email, overflow: TextOverflow.ellipsis),
                  trailing: m.role == 'OWNER'
                      ? const EtiquetaRol(role: 'OWNER')
                      : PopupMenuButton<String>(
                          tooltip: 'Rol de ${m.name}',
                          enabled: !_busy,
                          child: Row(mainAxisSize: MainAxisSize.min, children: [EtiquetaRol(role: m.role), const Icon(Icons.arrow_drop_down)]),
                          itemBuilder: (_) => [
                            CheckedPopupMenuItem(value: 'EDITOR', checked: m.role == 'EDITOR', child: const Text('Editor')),
                            CheckedPopupMenuItem(value: 'VIEWER', checked: m.role == 'VIEWER', child: const Text('Lector')),
                            const PopupMenuDivider(),
                            const PopupMenuItem(value: 'quitar', child: Text('Quitar del proyecto', style: TextStyle(color: Colores.danger))),
                          ],
                          onSelected: (v) async {
                            if (v == 'quitar') {
                              final ok = await confirmar(context,
                                  titulo: 'Quitar a ${m.name}', mensaje: 'Dejará de ver el proyecto y su chat.', accion: 'Quitar');
                              if (ok) _do(() => _api.delete('$_base/${m.userId}'), 'No se pudo eliminar el miembro.');
                            } else if (v != m.role) {
                              _do(() => _api.put('$_base/${m.userId}', {'role': v}), 'No se pudo cambiar el rol.');
                            }
                          },
                        ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
