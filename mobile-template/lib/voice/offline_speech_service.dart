import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:record/record.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;

import '../config/app_config.dart';

enum VoiceUiState { initializing, ready, listening, transcribing, error }

class MicrophonePermissionException implements Exception {
  const MicrophonePermissionException({required this.permanentlyDenied});

  final bool permanentlyDenied;

  @override
  String toString() => permanentlyDenied
      ? 'Permiso de micrófono bloqueado. Habilítalo en Configuración.'
      : 'Permiso de micrófono denegado. Toca el botón para intentarlo nuevamente.';
}

class OfflineSpeechService {
  OfflineSpeechService({AudioRecorder? recorder})
    : _recorder = recorder ?? AudioRecorder();

  final AudioRecorder _recorder;
  final List<int> _pcmBytes = [];
  StreamSubscription<Uint8List>? _subscription;
  sherpa.OfflineRecognizer? _recognizer;
  String? lastError;
  String permissionStatus = 'notRequested';
  VoiceUiState state = VoiceUiState.initializing;
  int _chunks = 0;
  bool _recorderReady = false;

  bool get isReady => _recognizer != null;
  bool get isRecording => _subscription != null;
  bool get isTranscribing => state == VoiceUiState.transcribing;
  bool get recorderReady => _recorderReady;

  Future<bool> initialize() async {
    state = VoiceUiState.initializing;
    lastError = null;
    _log('initializing STT');
    _log('init:start');
    _log('sherpaVersion=1.13.8');
    try {
      final directory = Directory(
        p.join((await getApplicationSupportDirectory()).path, 'asr'),
      );
      await directory.create(recursive: true);
      final encoder = await _copyAsset('encoder.int8.onnx', directory);
      final decoder = await _copyAsset('decoder.int8.onnx', directory);
      final tokens = await _copyAsset('tokens.txt', directory);
      _log('modelPath=${directory.path}');
      _log('model path=${directory.path}');
      for (final path in [encoder, decoder, tokens]) {
        final file = File(path);
        final exists = await file.exists();
        _log(
          'modelFile=${p.basename(path)} modelExists=$exists bytes=${exists ? await file.length() : 0}',
        );
      }
      _log('modelInitialization:start');
      sherpa.initBindings();
      _recognizer = sherpa.OfflineRecognizer(
        sherpa.OfflineRecognizerConfig(
          model: sherpa.OfflineModelConfig(
            whisper: sherpa.OfflineWhisperModelConfig(
              encoder: encoder,
              decoder: decoder,
              language: 'es',
              task: 'transcribe',
            ),
            tokens: tokens,
            modelType: 'whisper',
            numThreads: 2,
            debug: false,
          ),
        ),
      );
      lastError = null;
      state = VoiceUiState.ready;
      _log('modelInitialization:success');
      _log('recognizerCreated=true');
      _log('sttInitialized=true');
      return true;
    } catch (error) {
      lastError =
          'Faltan o no se pudieron cargar los archivos del modelo de voz.';
      state = VoiceUiState.error;
      _log('recognizerCreated=false');
      _log('sttInitialized=false');
      _log('init:error=${error.runtimeType}: $error');
      return false;
    }
  }

  Future<void> start() async {
    if (!isReady) throw StateError('El modelo ASR no está instalado.');
    final permission = await Permission.microphone.request();
    permissionStatus = permission.name;
    _log('permissionStatus=$permissionStatus');
    if (!permission.isGranted) {
      throw MicrophonePermissionException(
        permanentlyDenied: permission.isPermanentlyDenied,
      );
    }
    _pcmBytes.clear();
    _chunks = 0;
    final stream = await _recorder.startStream(
      const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: 16000,
        numChannels: 1,
      ),
    );
    _recorderReady = true;
    state = VoiceUiState.listening;
    _log('recorderInitialized=true');
    _log('recording=start');
    _log('encoding=pcm16bits');
    _log('sampleRate=16000');
    _log('channels=1');
    _subscription = stream.listen(
      (bytes) {
        _chunks++;
        _pcmBytes.addAll(bytes);
      },
      onError: (Object error) {
        lastError = 'No se pudo capturar el audio.';
        _log('error=$error');
      },
    );
  }

  Future<String> stopAndTranscribe() async {
    await _recorder.stop();
    await _subscription?.cancel();
    _subscription = null;
    state = VoiceUiState.transcribing;
    _log('recording=stop');
    _log('chunks=$_chunks');
    _log('totalBytes=${_pcmBytes.length}');
    final recognizer = _recognizer;
    if (recognizer == null) throw StateError('ASR no inicializado.');
    final samples = Float32List(_pcmBytes.length ~/ 2);
    final bytes = Uint8List.fromList(_pcmBytes);
    final data = ByteData.sublistView(bytes);
    for (var index = 0; index < samples.length; index++) {
      samples[index] = data.getInt16(index * 2, Endian.little) / 32768.0;
    }
    final stream = recognizer.createStream();
    try {
      stream.acceptWaveform(samples: samples, sampleRate: 16000);
      recognizer.decode(stream);
      final raw = recognizer.getResult(stream).text;
      final transcript = raw.trim();
      _log('result.raw="$raw"');
      _log('result.text="$transcript"');
      return transcript;
    } finally {
      stream.free();
      state = VoiceUiState.ready;
    }
  }

  Future<String> _copyAsset(String name, Directory directory) async {
    final target = File(p.join(directory.path, name));
    if (!await target.exists()) {
      final bytes = await rootBundle.load('assets/asr/$name');
      await target.writeAsBytes(bytes.buffer.asUint8List(), flush: true);
    }
    return target.path;
  }

  Future<void> dispose() async {
    await _subscription?.cancel();
    await _recorder.dispose();
    _recognizer?.free();
  }

  void _log(String value) {
    if (AppConfig.showDebugStatus) {
      // ignore: avoid_print
      print('[VOICE] $value');
    }
  }
}
