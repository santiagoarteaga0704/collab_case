import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_template/data/local/database_service.dart';
import 'package:mobile_template/repositories/mobile_repository.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  late DatabaseService storage;
  late MobileRepository repository;
  var syncRequests = 0;

  setUpAll(sqfliteFfiInit);
  setUp(() async {
    storage = await DatabaseService.open(
      factory: databaseFactoryFfi,
      path: inMemoryDatabasePath,
    );
    syncRequests = 0;
    repository = MobileRepository(storage, requestSync: () => syncRequests++);
  });
  tearDown(() => storage.database.close());

  test('repository CREATE is offline and creates pending mutation', () async {
    final record = await repository.createRecord('Cliente', {'nombre': 'Juan'});
    expect(record.version, 0);
    expect(
      (await repository.listRecords('Cliente')).single.data['nombre'],
      'Juan',
    );
    expect((await repository.pendingMutations()).single.operation, 'UPSERT');
    expect(syncRequests, 1);
  });

  test('repository UPDATE preserves syncId and creates mutation', () async {
    final created = await repository.createRecord('Cliente', {
      'nombre': 'Juan',
    });
    final updated = await repository.updateRecord('Cliente', created.syncId, {
      'nombre': 'Juan Pérez',
    });
    expect(updated.syncId, created.syncId);
    expect(
      (await repository.listRecords('Cliente')).single.data['nombre'],
      'Juan Pérez',
    );
    expect(await repository.pendingMutations(), hasLength(2));
  });

  test('repository DELETE creates tombstone and hides local record', () async {
    final created = await repository.createRecord('Cliente', {
      'nombre': 'Juan',
    });
    await repository.deleteRecord('Cliente', created.syncId);
    expect(await repository.listRecords('Cliente'), isEmpty);
    expect((await repository.pendingMutations()).last.operation, 'DELETE');
  });

  test('LIST and filters always read local SQLite', () async {
    await repository.createRecord('Cliente', {'nombre': 'Ana'});
    await repository.createRecord('Cliente', {'nombre': 'Pedro'});
    expect(await repository.listRecords('Cliente'), hasLength(2));
    expect(
      (await repository.findRecords('Cliente', {
        'nombre': 'ana',
      })).single.data['nombre'],
      'Ana',
    );
  });
}
