import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';

import '../api/api_client.dart';
import '../main.dart';
import '../theme.dart';
import 'comunes.dart';

/// Botón de micrófono: graba, manda el audio a `/api/speech/transcribe` (el mismo servicio
/// de voz que usa la web) y entrega el texto dictado.
class BotonDictado extends StatefulWidget {
  const BotonDictado({super.key, required this.onTexto, this.enabled = true});

  final void Function(String texto) onTexto;
  final bool enabled;

  @override
  State<BotonDictado> createState() => _BotonDictadoState();
}

class _BotonDictadoState extends State<BotonDictado> {
  final _recorder = AudioRecorder();
  bool _recording = false;
  bool _transcribing = false;
  String? _path;

  @override
  void dispose() {
    _recorder.dispose();
    super.dispose();
  }

  Future<void> _toggle() async {
    if (_recording) {
      await _stop();
      return;
    }
    if (!await _recorder.hasPermission()) {
      if (mounted) avisar(context, 'Permite el uso del micrófono para dictar.');
      return;
    }
    final dir = await getTemporaryDirectory();
    _path = '${dir.path}/dictado-${DateTime.now().millisecondsSinceEpoch}.m4a';
    await _recorder.start(const RecordConfig(encoder: AudioEncoder.aacLc, sampleRate: 16000, numChannels: 1), path: _path!);
    if (mounted) setState(() => _recording = true);
  }

  Future<void> _stop() async {
    final path = await _recorder.stop() ?? _path;
    if (!mounted) return;
    setState(() {
      _recording = false;
      _transcribing = true;
    });
    final api = SessionScope.of(context).api;
    try {
      if (path == null) throw ApiException('No se grabó audio.');
      final file = File(path);
      final bytes = await file.readAsBytes();
      file.delete().ignore();
      final data = await api.upload('/speech/transcribe', 'file', bytes, 'dictado.m4a', 'audio/mp4') as Map;
      final text = '${data['text'] ?? ''}'.trim();
      if (text.isEmpty) throw ApiException('No se entendió el audio. Intenta de nuevo.');
      widget.onTexto(text);
    } on ApiException catch (e) {
      if (mounted) avisar(context, e.message);
    } catch (_) {
      if (mounted) avisar(context, 'No se pudo transcribir el audio.');
    } finally {
      if (mounted) setState(() => _transcribing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_transcribing) {
      return const SizedBox(width: 48, height: 48, child: Padding(padding: EdgeInsets.all(14), child: CircularProgressIndicator(strokeWidth: 2)));
    }
    return IconButton(
      tooltip: _recording ? 'Detener y transcribir' : 'Dictar por voz',
      onPressed: widget.enabled ? _toggle : null,
      style: _recording ? IconButton.styleFrom(backgroundColor: Colores.dangerTint, foregroundColor: Colores.danger) : null,
      icon: Icon(_recording ? Icons.stop_circle_outlined : Icons.mic_none),
    );
  }
}
