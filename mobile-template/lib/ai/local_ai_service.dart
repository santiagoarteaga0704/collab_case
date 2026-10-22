import 'dart:async';
import 'dart:io';

import 'package:lib_llama_cpp/lib_llama_cpp.dart';

import '../config/app_config.dart';
import '../models/app_definition.dart';
import 'command_interpreter.dart';

abstract interface class LocalAiService implements CommandFallback {
  Future<void> load(String modelPath);
  bool get isLoaded;
}

String buildSystemPrompt(AppDefinition definition) {
  final entities = definition.entities
      .map((entity) {
        final fields = entity.attributes
            .map((field) => '- ${field.name} ${field.type}')
            .join('\n');
        return '${entity.name}:\n$fields';
      })
      .join('\n\n');
  return '''Eres el asistente local de ${definition.name}.
Convierte únicamente órdenes CRUD a JSON. Devuelve solo un objeto JSON, sin Markdown ni explicaciones.
Formato: {"action":"LIST|GET|CREATE|UPDATE|DELETE","entityType":"Entidad","selector":{},"data":{}}.
No inventes entidades ni atributos.

Entidades disponibles:
$entities''';
}

List<LlamaCommand> buildPlainTextCommands(String modelPath, String prompt) => [
  LlamaLoadModelCommand(
    modelPath: modelPath,
    contextSize: 2048,
    gpuLayerCount: 0,
  ),
  LlamaGenerateCommand(
    prompt: prompt,
    maxTokens: 192,
    temperature: 0,
    stop: const ['\n\nSolicitud del usuario:'],
  ),
  const LlamaDisposeCommand(),
];

class LlamaLocalAiService implements LocalAiService {
  String? _modelPath;
  Future<void> _inferenceQueue = Future<void>.value();

  @override
  bool get isLoaded => _modelPath != null;

  @override
  Future<void> load(String modelPath) async {
    if (!await File(modelPath).exists()) {
      throw StateError('El modelo GGUF seleccionado ya no existe.');
    }
    _modelPath = modelPath;
  }

  @override
  Future<String?> interpret(String input, AppDefinition definition) async {
    final previous = _inferenceQueue;
    final turn = Completer<void>();
    _inferenceQueue = previous.then((_) => turn.future);
    await previous;
    try {
      return await _interpretOnce(input, definition);
    } finally {
      turn.complete();
    }
  }

  Future<String?> _interpretOnce(String input, AppDefinition definition) async {
    final modelPath = _modelPath;
    if (modelPath == null) return null;
    _log('usingLlm=true');
    _log('mode=plain_text');
    _log('tools=false');
    _log('grammarEnabled=false');
    _log('jsonSchema=false');
    final prompt =
        '''${buildSystemPrompt(definition)}

Solicitud del usuario:
$input

JSON:''';
    final commands = Stream<LlamaCommand>.fromIterable(
      buildPlainTextCommands(modelPath, prompt),
    );
    final output = StringBuffer();
    await for (final response in const LibLlamaCpp().transform(commands)) {
      if (response is LlamaTokenResponse) output.write(response.text);
      if (response is LlamaErrorResponse) {
        throw StateError(response.message);
      }
    }
    final text = output.toString();
    _log('rawResponse=$text');
    return text;
  }

  void _log(String message) {
    if (AppConfig.showDebugStatus) {
      // ignore: avoid_print
      print('[MOBILE_AI] $message');
    }
  }
}
