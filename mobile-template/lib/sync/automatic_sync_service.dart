import 'dart:async';

import 'package:uuid/uuid.dart';

import '../config/app_config.dart';
import '../data/local/database_service.dart';
import '../data/remote/mobile_api_client.dart';
import '../repositories/mobile_repository.dart';

class AutomaticSyncService {
  AutomaticSyncService({
    required this.storage,
    required this.repository,
    required this.api,
    this.interval = const Duration(seconds: 20),
  });

  final DatabaseService storage;
  final MobileRepository repository;
  final MobileApiClient api;
  final Duration interval;

  Timer? _timer;
  bool _running = false;
  bool _queued = false;
  bool get isRunning => _running;

  void start() {
    repository.requestSync = request;
    request();
    _timer ??= Timer.periodic(interval, (_) => request());
  }

  void request() {
    _log('requested');
    if (_running) {
      _queued = true;
      return;
    }
    unawaited(syncNow());
  }

  Future<void> syncNow() async {
    if (_running) {
      _queued = true;
      return;
    }
    _running = true;
    var succeeded = false;
    try {
      final deviceId = await _deviceId();
      final cursor =
          int.tryParse(await storage.readState('last_change_id') ?? '') ?? 0;
      final pending = await repository.pendingMutations();
      final response = await api
          .sync(deviceId: deviceId, lastChangeId: cursor, mutations: pending)
          .timeout(const Duration(seconds: 10));
      for (final accepted in (response['accepted'] as List?) ?? const []) {
        await repository.applyAccepted(
          Map<String, dynamic>.from(accepted as Map),
        );
      }
      for (final conflict in (response['conflicts'] as List?) ?? const []) {
        await repository.applyConflict(
          Map<String, dynamic>.from(conflict as Map),
        );
      }
      for (final change in (response['changes'] as List?) ?? const []) {
        await repository.applyRemoteChange(
          Map<String, dynamic>.from(change as Map),
        );
      }
      final nextCursor = (response['nextCursor'] as num?)?.toInt() ?? cursor;
      await storage.writeState('last_change_id', nextCursor.toString());
      succeeded = true;
      _log('success cursor=$nextCursor');
    } catch (error) {
      final pending = await repository.pendingMutations();
      for (final mutation in pending) {
        await storage.database.update(
          'pending_mutation',
          {
            'attempt_count': mutation.attemptCount + 1,
            'last_error': error.toString(),
          },
          where: 'mutation_id = ?',
          whereArgs: [mutation.mutationId],
        );
      }
      _log('failure error=$error');
    } finally {
      _running = false;
      if (_queued && succeeded) {
        _queued = false;
        request();
      } else {
        _queued = false;
      }
    }
  }

  Future<void> refreshDefinition() async {
    final definition = await api.fetchDefinition();
    await storage.saveDefinition(definition);
  }

  Future<String> _deviceId() async {
    final current = await storage.readState('device_id');
    if (current != null) return current;
    final generated = const Uuid().v4();
    await storage.writeState('device_id', generated);
    return generated;
  }

  void onForeground() => request();

  void dispose() {
    _timer?.cancel();
    repository.requestSync = null;
  }

  void _log(String value) {
    if (AppConfig.showDebugStatus) {
      // ignore: avoid_print
      print('[SYNC] $value');
    }
  }
}
