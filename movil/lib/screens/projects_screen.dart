import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../main.dart';
import '../theme.dart';
import '../widgets/comunes.dart';
import '../widgets/marca.dart';
import 'agent_sheet.dart';
import 'project_screen.dart';

class ProjectsScreen extends StatefulWidget {
  const ProjectsScreen({super.key});

  @override
  State<ProjectsScreen> createState() => _ProjectsScreenState();
}

class _ProjectsScreenState extends State<ProjectsScreen> {
  List<Proyecto>? _projects;
  String _error = '';

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_projects == null && _error.isEmpty) _load();
  }

  Future<void> _load() async {
    try {
      final data = await SessionScope.of(context).api.get('/projects') as List;
      if (!mounted) return;
      setState(() {
        _projects = data.map((p) => Proyecto.fromJson(Map<String, dynamic>.from(p as Map))).toList();
        _error = '';
      });
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _create() async {
    final result = await pedirNombreYDescripcion(context, titulo: 'Nuevo proyecto', accion: 'Crear');
    if (result == null || !mounted) return;
    try {
      final data = await SessionScope.of(context).api.post('/projects', {'name': result.$1, 'description': result.$2});
      final project = Proyecto.fromJson(Map<String, dynamic>.from(data as Map));
      await _load();
      if (mounted) _open(project);
    } on ApiException catch (e) {
      if (mounted) avisar(context, e.message);
    }
  }

  Future<void> _rename(Proyecto project) async {
    final name = await pedirTexto(context, titulo: 'Renombrar proyecto', etiqueta: 'Nombre', inicial: project.name);
    if (name == null || !mounted) return;
    try {
      await SessionScope.of(context).api.patch('/projects/${project.id}', {'name': name});
      await _load();
    } on ApiException catch (e) {
      if (mounted) avisar(context, e.message);
    }
  }

  Future<void> _delete(Proyecto project) async {
    final ok = await confirmar(context,
        titulo: 'Eliminar proyecto',
        mensaje: 'Se eliminará «${project.name}» con todos sus diagramas, versiones y el chat. No se puede deshacer.',
        accion: 'Eliminar');
    if (!ok || !mounted) return;
    try {
      await SessionScope.of(context).api.delete('/projects/${project.id}');
      await _load();
    } on ApiException catch (e) {
      if (mounted) avisar(context, e.message);
    }
  }

  Future<void> _open(Proyecto project) async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => ProjectScreen(project: project)));
    if (mounted) _load();
  }

  @override
  Widget build(BuildContext context) {
    final session = SessionScope.of(context);
    final projects = _projects;
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 16,
        title: const Row(children: [Marca(size: 22), SizedBox(width: 10), Text('Collab CASE')]),
        actions: [
          IconButton(
            tooltip: 'Agente de ayuda',
            icon: const Icon(Icons.smart_toy_outlined),
            onPressed: () async {
              if (await abrirAgente(context)) _load();
            },
          ),
          PopupMenuButton<String>(
            tooltip: 'Cuenta',
            icon: CircleAvatar(
              radius: 15,
              backgroundColor: Colores.accentTint,
              child: Text(iniciales(session.user?.name), style: const TextStyle(fontSize: 12, color: Colores.accent, fontWeight: FontWeight.w700)),
            ),
            itemBuilder: (_) => [
              PopupMenuItem(enabled: false, child: Text('${session.user?.name}\n${session.user?.email}')),
              const PopupMenuItem(value: 'salir', child: Row(children: [Icon(Icons.logout, size: 18), SizedBox(width: 10), Text('Cerrar sesión')])),
            ],
            onSelected: (v) {
              if (v == 'salir') session.logout();
            },
          ),
          const SizedBox(width: 6),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _create,
        backgroundColor: Colores.accent,
        foregroundColor: Colors.white,
        icon: const Icon(Icons.add),
        label: const Text('Nuevo proyecto'),
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        child: switch ((projects, _error)) {
          (null, '') => const Center(child: CircularProgressIndicator()),
          (null, final error) => ErrorConReintento(mensaje: error, onRetry: _load),
          (final list?, _) when list.isEmpty => const EstadoVacio(
              icono: Icons.folder_open_outlined,
              titulo: 'Aún no tienes proyectos',
              texto: 'Crea un proyecto para empezar a modelar con tu equipo y la IA.',
            ),
          (final list?, _) => ListView.separated(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
              itemCount: list.length + 1,
              separatorBuilder: (_, _) => const SizedBox(height: 10),
              itemBuilder: (context, index) {
                if (index == 0) {
                  return Text('Proyectos (${list.length})',
                      style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700));
                }
                final p = list[index - 1];
                return Card(
                  child: ListTile(
                    contentPadding: const EdgeInsets.fromLTRB(16, 8, 4, 8),
                    title: Text(p.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                    subtitle: Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Wrap(spacing: 8, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
                        EtiquetaRol(role: p.role),
                        if ((p.description ?? '').isNotEmpty) Text(p.description!, maxLines: 2, overflow: TextOverflow.ellipsis),
                      ]),
                    ),
                    onTap: () => _open(p),
                    trailing: p.role == 'OWNER'
                        ? PopupMenuButton<String>(
                            tooltip: 'Más opciones',
                            itemBuilder: (_) => const [
                              PopupMenuItem(value: 'renombrar', child: Text('Renombrar')),
                              PopupMenuItem(value: 'eliminar', child: Text('Eliminar', style: TextStyle(color: Colores.danger))),
                            ],
                            onSelected: (v) => v == 'renombrar' ? _rename(p) : _delete(p),
                          )
                        : const Icon(Icons.chevron_right),
                  ),
                );
              },
            ),
        },
      ),
    );
  }
}
