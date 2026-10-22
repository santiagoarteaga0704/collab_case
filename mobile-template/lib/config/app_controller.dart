import 'package:flutter/foundation.dart';

import '../ai/local_ai_service.dart';
import '../ai/mobile_assistant.dart';
import '../ai/model_manager.dart';
import '../data/local/database_service.dart';
import '../models/app_definition.dart';
import '../repositories/mobile_repository.dart';
import '../sync/automatic_sync_service.dart';
import '../voice/offline_speech_service.dart';
import '../voice/offline_tts_service.dart';

class AppController extends ChangeNotifier {
  AppController({
    required this.storage,
    required this.repository,
    required this.sync,
    required this.speech,
    required this.tts,
    required this.modelManager,
    required this.localAi,
  });

  final DatabaseService storage;
  final MobileRepository repository;
  final AutomaticSyncService sync;
  final OfflineSpeechService speech;
  final OfflineTtsService tts;
  final ModelManager modelManager;
  final LocalAiService localAi;

  AppDefinition? definition;
  MobileAssistant? assistant;
  String? initializationError;
  String? modelPath;
  String response = '';
  bool busy = false;
  bool backendOnline = false;

  Future<void> initialize(
    MobileAssistant Function(AppDefinition) buildAssistant,
  ) async {
    initializationError = null;
    try {
      try {
        await sync.refreshDefinition();
        backendOnline = true;
      } catch (_) {
        backendOnline = false;
      }
      definition = await storage.loadDefinition();
      if (definition == null) {
        throw StateError('No existe una definición local.');
      }
      assistant = buildAssistant(definition!);
      modelPath = await modelManager.configuredPath();
      if (modelPath != null) {
        try {
          await localAi.load(modelPath!);
        } catch (_) {}
      }
      await speech.initialize();
      await tts.initialize();
      sync.start();
    } catch (_) {
      initializationError = 'No se pudo inicializar la aplicación.';
    }
    notifyListeners();
  }

  Future<void> submit(String input) async {
    if (busy || input.trim().isEmpty || assistant == null) return;
    busy = true;
    notifyListeners();
    try {
      response = (await assistant!.process(input)).message;
      await tts.speak(response);
    } catch (_) {
      response = 'No se pudo procesar la solicitud.';
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  Future<void> selectModel() async {
    final selected = await modelManager.selectAndStore();
    if (selected == null) return;
    modelPath = selected;
    try {
      await localAi.load(selected);
    } catch (error) {
      response = error.toString();
    }
    notifyListeners();
  }

  Future<void> disposeAsync() async {
    sync.dispose();
    await speech.dispose();
  }
}
