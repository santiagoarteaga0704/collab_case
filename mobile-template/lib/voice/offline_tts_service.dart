import 'package:flutter_tts/flutter_tts.dart';

class OfflineTtsService {
  OfflineTtsService({FlutterTts? engine}) : _engine = engine ?? FlutterTts();

  final FlutterTts _engine;

  Future<void> initialize() async {
    await _engine.setLanguage('es-ES');
    await _engine.setSpeechRate(0.48);
  }

  Future<void> speak(String text) async {
    try {
      await _engine.speak(text);
    } catch (_) {
      // La respuesta textual sigue disponible si Android no tiene voz local.
    }
  }
}
