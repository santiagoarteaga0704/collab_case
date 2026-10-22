import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import '../config/app_config.dart';
import '../config/app_controller.dart';
import '../voice/offline_speech_service.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.controller, required this.retry});

  final AppController controller;
  final Future<void> Function() retry;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  final _input = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      widget.controller.sync.onForeground();
    }
  }

  Future<void> _submit() async {
    final text = _input.text;
    _input.clear();
    await widget.controller.submit(text);
  }

  Future<void> _microphone() async {
    final speech = widget.controller.speech;
    try {
      if (!speech.isReady) {
        await speech.initialize();
        if (!speech.isReady) {
          throw StateError(
            speech.lastError ?? 'No se pudo preparar el reconocimiento de voz.',
          );
        }
      }
      if (speech.isRecording) {
        final transcript = await speech.stopAndTranscribe();
        _input.text = transcript;
        if (transcript.isNotEmpty) await _submit();
      } else {
        await speech.start();
        if (mounted) setState(() {});
      }
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(error.toString().replaceFirst('Bad state: ', '')),
          action:
              error is MicrophonePermissionException && error.permanentlyDenied
              ? SnackBarAction(
                  label: 'Configuración',
                  onPressed: openAppSettings,
                )
              : null,
        ),
      );
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final controller = widget.controller;
      final micEnabled = !controller.busy;
      final voiceState = controller.speech.state.name.toUpperCase();
      final disabledReason = controller.busy
          ? 'Procesando otra solicitud'
          : controller.speech.lastError ?? '';
      if (AppConfig.showDebugStatus) {
        // ignore: avoid_print
        print('[VOICE] uiState=$voiceState');
        // ignore: avoid_print
        print('[VOICE] permissionStatus=${controller.speech.permissionStatus}');
        // ignore: avoid_print
        print('[VOICE] sttReady=${controller.speech.isReady}');
        // ignore: avoid_print
        print('[VOICE] recorderReady=${controller.speech.recorderReady}');
        // ignore: avoid_print
        print('[VOICE] isRecording=${controller.speech.isRecording}');
        // ignore: avoid_print
        print('[VOICE] isTranscribing=${controller.speech.isTranscribing}');
        // ignore: avoid_print
        print('[VOICE] micEnabled=$micEnabled');
        // ignore: avoid_print
        print('[VOICE] disabledReason=$disabledReason');
      }
      if (controller.initializationError != null) {
        return Scaffold(
          body: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(controller.initializationError!),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: widget.retry,
                  child: const Text('Reintentar'),
                ),
              ],
            ),
          ),
        );
      }
      return Scaffold(
        appBar: AppBar(
          title: Text(controller.definition?.name ?? 'Aplicación'),
        ),
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (controller.modelPath == null)
                  FilledButton(
                    onPressed: controller.selectModel,
                    child: const Text('Seleccionar modelo IA'),
                  ),
                TextField(
                  controller: _input,
                  decoration: const InputDecoration(
                    labelText: '¿En qué puedo ayudarte?',
                    border: OutlineInputBorder(),
                  ),
                  onSubmitted: (_) => _submit(),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton(
                        onPressed: controller.busy ? null : _submit,
                        child: const Text('Enviar'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Tooltip(
                      message:
                          controller.speech.state == VoiceUiState.initializing
                          ? 'Preparando reconocimiento de voz...'
                          : controller.speech.lastError ??
                                (controller.speech.isRecording
                                    ? 'Detener grabación'
                                    : 'Iniciar reconocimiento de voz'),
                      child: IconButton.filled(
                        onPressed: micEnabled ? _microphone : null,
                        icon: Icon(
                          controller.speech.isRecording
                              ? Icons.stop
                              : Icons.mic,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 20),
                Text(controller.response),
                if (AppConfig.showDebugStatus) ...[
                  const Spacer(),
                  const Divider(),
                  Text(
                    'Backend: ${controller.backendOnline ? 'online' : 'offline'}',
                  ),
                  Text(
                    'Modelo IA: ${controller.localAi.isLoaded ? 'cargado' : 'no cargado'}',
                  ),
                  Text(
                    'ASR: ${controller.speech.isReady ? 'preparado' : 'faltan assets'}',
                  ),
                  if (controller.speech.lastError != null)
                    Text(controller.speech.lastError!),
                  TextButton(
                    onPressed: controller.sync.request,
                    child: const Text('Sync manual'),
                  ),
                ],
              ],
            ),
          ),
        ),
      );
    },
  );

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _input.dispose();
    super.dispose();
  }
}
