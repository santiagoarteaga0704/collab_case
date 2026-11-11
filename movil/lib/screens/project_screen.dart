import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../main.dart';
import '../theme.dart';
import '../widgets/comunes.dart';
import 'agent_sheet.dart';
import 'editor_screen.dart';
import 'members_tab.dart';
import 'team_chat_view.dart';

/// Página del proyecto: diagramas por versión, chat del equipo y miembros (como `ProjectDetail.jsx`).
class ProjectScreen extends StatefulWidget {
  const ProjectScreen({super.key, required this.project});

  final Proyecto project;

  @override
  State<ProjectScreen> createState() => _ProjectScreenState();
}

class _ProjectScreenState extends State<ProjectScreen> {
  late Proyecto _project = widget.project;
  List<Version> _versions = [];
  Version? _active;
  List<DiagramaResumen>? _diagrams;
  String _error = '';

  ApiClient get _api => SessionScope.of(context).api;
  bool get _canEdit => _project.puedeEditar;
  bool get _isOwner => _project.role == 'OWNER';

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_diagrams == null && _error.isEmpty) _load();
  }

  Future<void> _load({String? versionId}) async {
    try {
      final results = await Future.wait([_api.get('/projects/${_project.id}'), _api.get('/projects/${_project.id}/versions')]);
      final project = Proyecto.fromJson(Map<String, dynamic>.from(results[0] as Map));
      final versions = (results[1] as List).map((v) => Version.fromJson(Map<String, dynamic>.from(v as Map))).toList();
      final wanted = versionId ?? _active?.id;
      final active = versions.where((v) => v.id == wanted).firstOrNull ?? versions.where((v) => v.main).firstOrNull;
      List<DiagramaResumen> diagrams = [];
      if (active != null) {
        final data = await _api.get('/projects/${_project.id}/versions/${active.id}/diagrams') as List;
        diagrams = data.map((d) => DiagramaResumen.fromJson(Map<String, dynamic>.from(d as Map))).toList();
      }
      if (!mounted) return;
      setState(() {
        _project = project;
        _versions = versions;
        _active = active;
        _diagrams = diagrams;
        _error = '';
      });
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _run(Future<void> Function() work, String success, {String? versionId}) async {
    try {
      await work();
      if (!mounted) return;
      avisar(context, success);
      await _load(versionId: versionId);
    } on ApiException catch (e) {
      if (mounted) avisar(context, e.message);
    }
  }

  Future<void> _openEditor({String? diagramId}) async {
    final version = _active;
    if (version == null) return;
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => EditorScreen(projectId: _project.id, versionId: version.id, diagramId: diagramId),
    ));
    if (mounted) _load();
  }

  Future<void> _newVersion() async {
    final result = await pedirNombreYDescripcion(context, titulo: 'Nueva versión', accion: 'Crear versión');
    if (result == null || !mounted) return;
    String? createdId;
    await _run(() async {
      final created = await _api.post('/projects/${_project.id}/versions',
          {'name': result.$1, 'description': result.$2, 'sourceVersionId': _active?.id}) as Map;
      createdId = created['id'] as String?;
    }, 'Versión creada. Copia los diagramas de «${_active?.name}».');
    if (createdId != null && mounted) await _load(versionId: createdId);
  }

  Future<void> _renameVersion(Version v) async {
    final name = await pedirTexto(context, titulo: 'Renombrar versión', etiqueta: 'Nombre', inicial: v.name);
    if (name == null || !mounted) return;
    await _run(() => _api.patch('/projects/${_project.id}/versions/${v.id}', {'name': name}), 'Nombre actualizado.');
  }

  Future<void> _deleteVersion(Version v) async {
    final ok = await confirmar(context,
        titulo: 'Eliminar versión',
        mensaje: 'Se eliminará «${v.name}» con todos sus diagramas. La versión principal no se modifica.',
        accion: 'Eliminar versión');
    if (!ok || !mounted) return;
    final main = _versions.where((x) => x.main).firstOrNull;
    await _run(() => _api.delete('/projects/${_project.id}/versions/${v.id}'), 'Versión eliminada.', versionId: main?.id);
  }

  Future<void> _renameDiagram(DiagramaResumen d) async {
    final name = await pedirTexto(context, titulo: 'Renombrar diagrama', etiqueta: 'Nombre', inicial: d.name);
    if (name == null || !mounted) return;
    await _run(() => _api.patch('/diagrams/${d.id}', {'name': name}), 'Nombre actualizado.');
  }

  Future<void> _deleteDiagram(DiagramaResumen d) async {
    final ok = await confirmar(context,
        titulo: 'Eliminar ${d.name}',
        mensaje: 'Solo se elimina de la versión «${_active?.name}». Las copias en otras versiones no cambian.',
        accion: 'Eliminar diagrama');
    if (!ok || !mounted) return;
    await _run(() => _api.delete('/diagrams/${d.id}'), 'Diagrama eliminado.');
  }

  Future<void> _renameProject() async {
    final name = await pedirTexto(context, titulo: 'Renombrar proyecto', etiqueta: 'Nombre', inicial: _project.name);
    if (name == null || !mounted) return;
    await _run(() => _api.patch('/projects/${_project.id}', {'name': name}), 'Nombre actualizado.');
  }

  Future<void> _deleteProject() async {
    final ok = await confirmar(context,
        titulo: 'Eliminar proyecto',
        mensaje: 'Se eliminará «${_project.name}» con todas sus versiones, diagramas y el chat. No se puede deshacer.',
        accion: 'Eliminar proyecto');
    if (!ok || !mounted) return;
    try {
      await _api.delete('/projects/${_project.id}');
      if (mounted) Navigator.of(context).pop();
    } on ApiException catch (e) {
      if (mounted) avisar(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tabs = [
      const Tab(icon: Icon(Icons.account_tree_outlined), text: 'Diagramas'),
      const Tab(icon: Icon(Icons.forum_outlined), text: 'Chat del equipo'),
      if (_isOwner) const Tab(icon: Icon(Icons.group_outlined), text: 'Miembros'),
    ];
    return DefaultTabController(
      key: ValueKey(tabs.length),
      length: tabs.length,
      child: Scaffold(
        appBar: AppBar(
          title: Text(_project.name, overflow: TextOverflow.ellipsis),
          actions: [
            IconButton(
              tooltip: 'Agente de ayuda',
              icon: const Icon(Icons.smart_toy_outlined),
              onPressed: () async {
                if (await abrirAgente(context, contexto: {
                'route': '/projects/${_project.id}',
                'projectId': _project.id,
                'projectName': _project.name,
                'versionId': _active?.id,
                'versionName': _active?.name,
                  'role': _project.role,
                })) {
                  _load();
                }
              },
            ),
            if (_isOwner)
              PopupMenuButton<String>(
                tooltip: 'Acciones del proyecto',
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'renombrar', child: Text('Renombrar proyecto')),
                  PopupMenuItem(value: 'eliminar', child: Text('Eliminar proyecto', style: TextStyle(color: Colores.danger))),
                ],
                onSelected: (v) => v == 'renombrar' ? _renameProject() : _deleteProject(),
              ),
          ],
          bottom: TabBar(tabs: tabs, labelColor: Colores.accent, indicatorColor: Colores.accent, labelStyle: const TextStyle(fontSize: 12)),
        ),
        body: TabBarView(
          physics: const NeverScrollableScrollPhysics(),
          children: [
            _diagramsTab(),
            TeamChatView(projectId: _project.id, role: _project.role, isOwner: _isOwner),
            if (_isOwner) MembersTab(projectId: _project.id),
          ],
        ),
      ),
    );
  }

  Widget _diagramsTab() {
    final diagrams = _diagrams;
    if (diagrams == null) {
      return _error.isEmpty
          ? const Center(child: CircularProgressIndicator())
          : ErrorConReintento(mensaje: _error, onRetry: _load);
    }
    final active = _active;
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        children: [
          Row(children: [
            EtiquetaRol(role: _project.role),
            const SizedBox(width: 10),
            Expanded(
              child: Text(_project.description?.isNotEmpty == true ? _project.description! : 'Sin descripción',
                  style: const TextStyle(color: Colores.muted), maxLines: 2, overflow: TextOverflow.ellipsis),
            ),
          ]),
          const SizedBox(height: 16),
          if (active != null)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    const Icon(Icons.call_split, size: 18, color: Colores.muted),
                    const SizedBox(width: 8),
                    const Text('Versión', style: TextStyle(fontWeight: FontWeight.w600)),
                    const SizedBox(width: 12),
                    Expanded(
                      child: DropdownButton<String>(
                        isExpanded: true,
                        value: active.id,
                        items: [
                          for (final v in _versions)
                            DropdownMenuItem(value: v.id, child: Text(v.main ? '${v.name} (principal)' : v.name, overflow: TextOverflow.ellipsis)),
                        ],
                        onChanged: (id) => _load(versionId: id),
                      ),
                    ),
                  ]),
                  Text(active.main ? 'Versión principal del proyecto' : 'Copia independiente de los diagramas',
                      style: const TextStyle(color: Colores.muted, fontSize: 13)),
                  if (_canEdit) ...[
                    const SizedBox(height: 10),
                    Wrap(spacing: 8, runSpacing: 8, children: [
                      OutlinedButton.icon(onPressed: _newVersion, icon: const Icon(Icons.add, size: 18), label: const Text('Nueva versión')),
                      if (_isOwner && !active.main) ...[
                        OutlinedButton.icon(onPressed: () => _renameVersion(active), icon: const Icon(Icons.edit_outlined, size: 18), label: const Text('Renombrar')),
                        OutlinedButton.icon(
                          onPressed: () => _deleteVersion(active),
                          style: OutlinedButton.styleFrom(foregroundColor: Colores.danger),
                          icon: const Icon(Icons.delete_outline, size: 18),
                          label: const Text('Eliminar'),
                        ),
                      ],
                    ]),
                  ],
                ]),
              ),
            ),
          const SizedBox(height: 20),
          Row(children: [
            Text('Diagramas (${diagrams.length})', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
            const Spacer(),
            if (_canEdit && active != null)
              FilledButton.icon(onPressed: () => _openEditor(), icon: const Icon(Icons.add, size: 18), label: const Text('Nuevo diagrama')),
          ]),
          const SizedBox(height: 12),
          if (diagrams.isEmpty)
            Container(
              padding: const EdgeInsets.all(24),
              decoration: BoxDecoration(border: Border.all(color: Colores.rule), borderRadius: BorderRadius.circular(10)),
              child: const Column(children: [
                Text('Esta versión no tiene diagramas', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
                SizedBox(height: 6),
                Text('Crea un diagrama de clases y edítalo junto a tu equipo y la IA.',
                    textAlign: TextAlign.center, style: TextStyle(color: Colores.muted)),
              ]),
            ),
          for (final d in diagrams)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Card(
                child: ListTile(
                  leading: const Icon(Icons.schema_outlined, color: Colores.accent),
                  title: Text(d.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text('Diagrama de clases · ${haceCuanto(d.updatedAt)}'),
                  onTap: () => _openEditor(diagramId: d.id),
                  trailing: _canEdit
                      ? PopupMenuButton<String>(
                          tooltip: 'Acciones de ${d.name}',
                          itemBuilder: (_) => const [
                            PopupMenuItem(value: 'abrir', child: Text('Abrir')),
                            PopupMenuItem(value: 'renombrar', child: Text('Renombrar')),
                            PopupMenuItem(value: 'eliminar', child: Text('Eliminar', style: TextStyle(color: Colores.danger))),
                          ],
                          onSelected: (v) => switch (v) {
                            'abrir' => _openEditor(diagramId: d.id),
                            'renombrar' => _renameDiagram(d),
                            _ => _deleteDiagram(d),
                          },
                        )
                      : const Icon(Icons.chevron_right),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

String haceCuanto(DateTime? fecha) {
  if (fecha == null) return '';
  final diff = DateTime.now().difference(fecha.toLocal());
  if (diff.inMinutes < 1) return 'hace un momento';
  if (diff.inMinutes < 60) return 'hace ${diff.inMinutes} min';
  if (diff.inHours < 24) return 'hace ${diff.inHours} h';
  if (diff.inDays < 30) return 'hace ${diff.inDays} d';
  final f = fecha.toLocal();
  return '${f.day}/${f.month}/${f.year}';
}
