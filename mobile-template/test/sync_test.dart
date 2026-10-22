import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_template/data/local/database_service.dart';
import 'package:mobile_template/data/remote/mobile_api_client.dart';
import 'package:mobile_template/models/pending_mutation.dart';
import 'package:mobile_template/repositories/mobile_repository.dart';
import 'package:mobile_template/sync/automatic_sync_service.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

class FakeApi extends MobileApiClient {
  FakeApi(this.handler) : super(baseUrl: 'http://unused');

  final Future<Map<String, dynamic>> Function(List<PendingMutation>) handler;
  int active = 0;
  int maxActive = 0;

  @override
  Future<Map<String, dynamic>> sync({
    required String deviceId,
    required int lastChangeId,
    required List<PendingMutation> mutations,
  }) async {
    active++;
    if (active > maxActive) maxActive = active;
    try {
      return await handler(mutations);
    } finally {
      active--;
    }
  }
}

void main() {
  late DatabaseService storage;
  late MobileRepository repository;

  setUpAll(sqfliteFfiInit);
  setUp(() async {
    storage = await DatabaseService.open(
      factory: databaseFactoryFfi,
      path: inMemoryDatabasePath,
    );
    repository = MobileRepository(storage);
  });
  tearDown(() => storage.database.close());

  test('sync accepted removes only confirmed mutation', () async {
    await repository.createRecord('Cliente', {'nombre': 'Juan'});
    final pending = (await repository.pendingMutations()).single;
    final api = FakeApi(
      (_) async => {
        'accepted': [
          {
            'mutationId': pending.mutationId,
            'entityType': 'Cliente',
            'syncId': pending.syncId,
            'version': 1,
          },
        ],
        'conflicts': [],
        'changes': [],
        'nextCursor': 1,
      },
    );
    await AutomaticSyncService(
      storage: storage,
      repository: repository,
      api: api,
    ).syncNow();
    expect(await repository.pendingMutations(), isEmpty);
    expect((await repository.listRecords('Cliente')).single.version, 1);
  });

  test('sync conflict is stored and mutation remains', () async {
    await repository.createRecord('Cliente', {'nombre': 'Juan'});
    final pending = (await repository.pendingMutations()).single;
    final api = FakeApi(
      (_) async => {
        'accepted': [],
        'conflicts': [
          {
            'mutationId': pending.mutationId,
            'entityType': 'Cliente',
            'syncId': pending.syncId,
            'serverVersion': 2,
            'serverData': {'nombre': 'Servidor'},
            'message': 'VERSION_CONFLICT',
          },
        ],
        'changes': [],
        'nextCursor': 2,
      },
    );
    await AutomaticSyncService(
      storage: storage,
      repository: repository,
      api: api,
    ).syncNow();
    expect(
      (await repository.pendingMutations()).single.mutationId,
      pending.mutationId,
    );
    expect(
      (await storage.database.query('sync_conflict')).single['mutation_id'],
      pending.mutationId,
    );
  });

  test('sync retry keeps mutationId', () async {
    await repository.createRecord('Cliente', {'nombre': 'Juan'});
    final original = (await repository.pendingMutations()).single.mutationId;
    final api = FakeApi((_) async => throw StateError('offline'));
    final service = AutomaticSyncService(
      storage: storage,
      repository: repository,
      api: api,
    );
    await service.syncNow();
    await service.syncNow();
    final pending = (await repository.pendingMutations()).single;
    expect(pending.mutationId, original);
    expect(pending.attemptCount, 2);
  });

  test('automatic sync never runs concurrently', () async {
    final completer = Completer<Map<String, dynamic>>();
    final api = FakeApi((_) => completer.future);
    final service = AutomaticSyncService(
      storage: storage,
      repository: repository,
      api: api,
    );
    final first = service.syncNow();
    while (api.active == 0) {
      await Future<void>.delayed(const Duration(milliseconds: 1));
    }
    final second = service.syncNow();
    expect(api.maxActive, 1);
    completer.complete({
      'accepted': [],
      'conflicts': [],
      'changes': [],
      'nextCursor': 0,
    });
    await Future.wait([first, second]);
    while (service.isRunning) {
      await Future<void>.delayed(const Duration(milliseconds: 1));
    }
    expect(api.maxActive, 1);
  });
}
