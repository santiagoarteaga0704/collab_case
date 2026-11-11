import 'package:flutter/material.dart';

import '../api/models.dart';
import '../theme.dart';

String iniciales(String? name) {
  final parts = (name ?? '').trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty).toList();
  if (parts.isEmpty) return '?';
  return parts.take(2).map((p) => p[0].toUpperCase()).join();
}

void avisar(BuildContext context, String mensaje) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(mensaje), behavior: SnackBarBehavior.floating));
}

class EtiquetaRol extends StatelessWidget {
  const EtiquetaRol({super.key, required this.role});

  final String role;

  @override
  Widget build(BuildContext context) {
    final (fondo, texto) = switch (role) {
      'OWNER' => (Colores.accentTint, Colores.accent),
      'EDITOR' => (Colores.collabTint, Colores.collabInk),
      _ => (const Color(0xFFECEEE9), Colores.muted),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
      decoration: BoxDecoration(color: fondo, borderRadius: BorderRadius.circular(20)),
      child: Text(nombresRol[role] ?? role, style: TextStyle(color: texto, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}

/// Estado vacío que también sirve dentro de un RefreshIndicator (se puede arrastrar).
class EstadoVacio extends StatelessWidget {
  const EstadoVacio({super.key, required this.icono, required this.titulo, required this.texto, this.accion});

  final IconData icono;
  final String titulo;
  final String texto;
  final Widget? accion;

  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(32, 80, 32, 32),
      children: [
        Icon(icono, size: 48, color: Colores.faint),
        const SizedBox(height: 14),
        Text(titulo, textAlign: TextAlign.center, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        Text(texto, textAlign: TextAlign.center, style: const TextStyle(color: Colores.muted)),
        if (accion != null) ...[const SizedBox(height: 18), Center(child: accion)],
      ],
    );
  }
}

class ErrorConReintento extends StatelessWidget {
  const ErrorConReintento({super.key, required this.mensaje, required this.onRetry});

  final String mensaje;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return EstadoVacio(
      icono: Icons.cloud_off_outlined,
      titulo: 'No se pudo cargar',
      texto: mensaje,
      accion: OutlinedButton.icon(onPressed: onRetry, icon: const Icon(Icons.refresh), label: const Text('Reintentar')),
    );
  }
}

Future<bool> confirmar(BuildContext context,
    {required String titulo, required String mensaje, required String accion}) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(titulo),
      content: Text(mensaje),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancelar')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: Colores.danger),
          onPressed: () => Navigator.pop(context, true),
          child: Text(accion),
        ),
      ],
    ),
  );
  return ok ?? false;
}

Future<String?> pedirTexto(BuildContext context,
    {required String titulo, required String etiqueta, String inicial = '', String accion = 'Guardar', int maximo = 120}) {
  final controller = TextEditingController(text: inicial);
  return showDialog<String>(
    context: context,
    builder: (context) {
      void enviar() {
        final value = controller.text.trim();
        if (value.isNotEmpty) Navigator.pop(context, value);
      }

      return AlertDialog(
        title: Text(titulo),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLength: maximo,
          decoration: InputDecoration(labelText: etiqueta),
          onSubmitted: (_) => enviar(),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancelar')),
          FilledButton(onPressed: enviar, child: Text(accion)),
        ],
      );
    },
  );
}

/// Devuelve (nombre, descripción) o null si se canceló.
Future<(String, String)?> pedirNombreYDescripcion(BuildContext context,
    {required String titulo, required String accion}) {
  final name = TextEditingController();
  final description = TextEditingController();
  return showDialog<(String, String)>(
    context: context,
    builder: (context) {
      void enviar() {
        if (name.text.trim().isNotEmpty) Navigator.pop(context, (name.text.trim(), description.text.trim()));
      }

      return AlertDialog(
        title: Text(titulo),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(controller: name, autofocus: true, maxLength: 120, decoration: const InputDecoration(labelText: 'Nombre')),
          const SizedBox(height: 8),
          TextField(
            controller: description,
            maxLines: 3,
            minLines: 2,
            decoration: const InputDecoration(labelText: 'Descripción (opcional)'),
          ),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancelar')),
          FilledButton(onPressed: enviar, child: Text(accion)),
        ],
      );
    },
  );
}
