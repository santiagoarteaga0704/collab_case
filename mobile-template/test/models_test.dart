import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_template/models/app_definition.dart';
import 'package:mobile_template/models/local_record.dart';
import 'package:mobile_template/models/pending_mutation.dart';

void main() {
  test('AppDefinition parses dynamic entities and attributes', () {
    final definition = AppDefinition.fromJson({
      'name': 'Biblioteca',
      'entities': [
        {
          'name': 'Cliente',
          'attributes': [
            {'name': 'nombre', 'type': 'String', 'nullable': false},
          ],
        },
      ],
    });
    expect(definition.name, 'Biblioteca');
    expect(definition.entity('cliente')?.attribute('nombre')?.required, isTrue);
  });

  test('LocalRecord serializes and restores all fields', () {
    final value = LocalRecord(
      entityType: 'Cliente',
      syncId: 'record-1',
      data: const {'nombre': 'Juan'},
      version: 2,
      deleted: false,
      syncStatus: 'PENDING',
      updatedAt: DateTime.utc(2026),
    );
    final restored = LocalRecord.fromRow(value.toRow());
    expect(restored.data, {'nombre': 'Juan'});
    expect(restored.version, 2);
  });

  test('PendingMutation keeps the same mutationId in API payload', () {
    final value = PendingMutation(
      mutationId: 'mutation-1',
      entityType: 'Cliente',
      syncId: 'record-1',
      baseVersion: 0,
      operation: 'UPSERT',
      data: const {'nombre': 'Juan'},
      createdAt: DateTime.utc(2026),
    );
    expect(PendingMutation.fromRow(value.toRow()).mutationId, 'mutation-1');
    expect(value.toApiJson()['mutationId'], 'mutation-1');
  });
}
