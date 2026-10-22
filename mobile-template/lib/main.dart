import 'package:flutter/material.dart';

import 'ai/command_executor.dart';
import 'ai/command_interpreter.dart';
import 'ai/command_validator.dart';
import 'ai/local_ai_service.dart';
import 'ai/mobile_assistant.dart';
import 'ai/model_manager.dart';
import 'config/app_controller.dart';
import 'data/local/database_service.dart';
import 'data/remote/mobile_api_client.dart';
import 'repositories/mobile_repository.dart';
import 'screens/home_screen.dart';
import 'sync/automatic_sync_service.dart';
import 'voice/offline_speech_service.dart';
import 'voice/offline_tts_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final storage = await DatabaseService.open();
  final repository = MobileRepository(storage);
  final localAi = LlamaLocalAiService();
  final sync = AutomaticSyncService(
    storage: storage,
    repository: repository,
    api: MobileApiClient(),
  );
  final controller = AppController(
    storage: storage,
    repository: repository,
    sync: sync,
    speech: OfflineSpeechService(),
    tts: OfflineTtsService(),
    modelManager: ModelManager(),
    localAi: localAi,
  );
  MobileAssistant buildAssistant(definition) => MobileAssistant(
    definition: definition,
    interpreter: CommandInterpreter(fallback: localAi),
    validator: CommandValidator(),
    executor: CommandExecutor(repository),
  );
  await controller.initialize(buildAssistant);
  runApp(
    CollabMobileApp(
      controller: controller,
      retry: () => controller.initialize(buildAssistant),
    ),
  );
}

class CollabMobileApp extends StatelessWidget {
  const CollabMobileApp({
    super.key,
    required this.controller,
    required this.retry,
  });

  final AppController controller;
  final Future<void> Function() retry;

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Collab Mobile',
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
      useMaterial3: true,
    ),
    home: HomeScreen(controller: controller, retry: retry),
  );
}
