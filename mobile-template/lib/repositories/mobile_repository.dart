import 'dart:convert';

import 'package:sqflite/sqflite.dart';
import 'package:uuid/uuid.dart';

import '../config/app_config.dart';
import '../data/local/database_service.dart';
import '../models/local_record.dart';
import '../models/pending_mutation.dart';

typedef SyncRequester = void Function();

class MobileRepository {
  MobileRepository(this.storage, {Uuid? uuid, SyncRequester? requestSync})
    : _uuid = uuid ?? const Uuid(),
      _requestSync = requestSync;

  final DatabaseService storage;
  final Uuid _uuid;
  SyncRequester? _requestSync;

  set requestSync(SyncRequester? value) => _requestSync = value;

  Future<List<LocalRecord>> listRecords(String entityType) async {
    final rows = await storage.database.query(
      'local_record',
      where: 'entity_type = ? AND deleted = 0',
      whereArgs: [entityType],
      orderBy: 'updated_at DESC',
    );
    return rows.map(LocalRecord.fromRow).toList();
  }

  Future<List<LocalRecord>> findRecords(
    String entityType,
    Map<String, dynamic> filters,
  ) async {
    final values = await listRecords(entityType);
    return values
        .where(
          (record) => filters.entries.every((entry) {
            final actual = record.data[entry.key]?.toString().toLowerCase();
            return actual == entry.value?.toString().toLowerCase();
          }),
        )
        .toList();
  }

  Future<LocalRecord> createRecord(
    String entityType,
    Map<String, dynamic> data,
  ) async {
    final now = DateTime.now().toUtc();
    final record = LocalRecord(
      entityType: entityType,
      syncId: _uuid.v4(),
      data: Map<String, dynamic>.from(data),
      version: 0,
      deleted: false,
      syncStatus: 'PENDING',
      updatedAt: now,
    );
    final mutation = PendingMutation(
      mutationId: _uuid.v4(),
      entityType: entityType,
      syncId: record.syncId,
      baseVersion: 0,
      operation: 'UPSERT',
      data: record.data,
      createdAt: now,
    );
    await storage.database.transaction((txn) async {
      await txn.insert('local_record', record.toRow());
      await txn.insert('pending_mutation', mutation.toRow());
    });
    _log('CREATE', entityType, record.syncId);
    _requestSync?.call();
    return record;
  }

  Future<LocalRecord> updateRecord(
    String entityType,
    String syncId,
    Map<String, dynamic> patch,
  ) async {
    final current = await _required(entityType, syncId);
    final data = {...current.data, ...patch};
    final now = DateTime.now().toUtc();
    final updated = LocalRecord(
      entityType: entityType,
      syncId: syncId,
      data: data,
      version: current.version,
      deleted: false,
      syncStatus: 'PENDING',
      updatedAt: now,
    );
    final mutation = PendingMutation(
      mutationId: _uuid.v4(),
      entityType: entityType,
      syncId: syncId,
      baseVersion: current.version,
      operation: 'UPSERT',
      data: data,
      createdAt: now,
    );
    await storage.database.transaction((txn) async {
      await txn.update(
        'local_record',
        updated.toRow(),
        where: 'entity_type = ? AND sync_id = ?',
        whereArgs: [entityType, syncId],
      );
      await txn.insert('pending_mutation', mutation.toRow());
    });
    _log('UPDATE', entityType, syncId);
    _requestSync?.call();
    return updated;
  }

  Future<void> deleteRecord(String entityType, String syncId) async {
    final current = await _required(entityType, syncId);
    final now = DateTime.now().toUtc();
    final deleted = LocalRecord(
      entityType: entityType,
      syncId: syncId,
      data: current.data,
      version: current.version,
      deleted: true,
      syncStatus: 'PENDING',
      updatedAt: now,
    );
    final mutation = PendingMutation(
      mutationId: _uuid.v4(),
      entityType: entityType,
      syncId: syncId,
      baseVersion: current.version,
      operation: 'DELETE',
      data: null,
      createdAt: now,
    );
    await storage.database.transaction((txn) async {
      await txn.update(
        'local_record',
        deleted.toRow(),
        where: 'entity_type = ? AND sync_id = ?',
        whereArgs: [entityType, syncId],
      );
      await txn.insert('pending_mutation', mutation.toRow());
    });
    _log('DELETE', entityType, syncId);
    _requestSync?.call();
  }

  Future<List<PendingMutation>> pendingMutations() async =>
      (await storage.database.query(
        'pending_mutation',
        orderBy: 'created_at ASC',
      )).map(PendingMutation.fromRow).toList();

  Future<LocalRecord> _required(String entityType, String syncId) async {
    final rows = await storage.database.query(
      'local_record',
      where: 'entity_type = ? AND sync_id = ?',
      whereArgs: [entityType, syncId],
    );
    if (rows.isEmpty) throw StateError('Registro no encontrado');
    return LocalRecord.fromRow(rows.single);
  }

  void _log(String operation, String entityType, String syncId) {
    if (AppConfig.showDebugStatus) {
      // ignore: avoid_print
      print(
        '[REPOSITORY] operation=$operation entity=$entityType syncId=$syncId',
      );
    }
  }

  Future<void> applyAccepted(Map<String, dynamic> accepted) async {
    final mutationId = accepted['mutationId'].toString();
    final entityType = accepted['entityType'].toString();
    final syncId = accepted['syncId'].toString();
    final version = (accepted['version'] as num).toInt();
    await storage.database.transaction((txn) async {
      await txn.delete(
        'pending_mutation',
        where: 'mutation_id = ?',
        whereArgs: [mutationId],
      );
      final remaining =
          Sqflite.firstIntValue(
            await txn.rawQuery(
              'SELECT COUNT(*) FROM pending_mutation WHERE entity_type = ? AND sync_id = ?',
              [entityType, syncId],
            ),
          ) ??
          0;
      if (remaining == 0) {
        await txn.update(
          'local_record',
          {'version': version, 'sync_status': 'SYNCED'},
          where: 'entity_type = ? AND sync_id = ?',
          whereArgs: [entityType, syncId],
        );
      }
    });
  }

  Future<void> applyConflict(Map<String, dynamic> conflict) async {
    final mutationId = conflict['mutationId'].toString();
    final entityType = conflict['entityType'].toString();
    final syncId = conflict['syncId'].toString();
    await storage.database.transaction((txn) async {
      await txn.insert('sync_conflict', {
        'mutation_id': mutationId,
        'entity_type': entityType,
        'sync_id': syncId,
        'server_version': (conflict['serverVersion'] as num?)?.toInt() ?? 0,
        'server_data_json': conflict['serverData'] == null
            ? null
            : jsonEncode(conflict['serverData']),
        'message': conflict['message']?.toString(),
        'created_at': DateTime.now().toUtc().toIso8601String(),
      }, conflictAlgorithm: ConflictAlgorithm.replace);
      await txn.update(
        'local_record',
        {'sync_status': 'CONFLICT'},
        where: 'entity_type = ? AND sync_id = ?',
        whereArgs: [entityType, syncId],
      );
    });
  }

  Future<void> applyRemoteChange(Map<String, dynamic> change) async {
    final entityType = change['entityType'].toString();
    final syncId = change['syncId'].toString();
    final pending =
        Sqflite.firstIntValue(
          await storage.database.rawQuery(
            'SELECT COUNT(*) FROM pending_mutation WHERE entity_type = ? AND sync_id = ?',
            [entityType, syncId],
          ),
        ) ??
        0;
    if (pending > 0) return;
    final operation = change['operation'].toString();
    final data = Map<String, dynamic>.from(
      (change['data'] as Map?) ?? const {},
    );
    final record = LocalRecord(
      entityType: entityType,
      syncId: syncId,
      data: data,
      version: (change['version'] as num).toInt(),
      deleted: operation == 'DELETE',
      syncStatus: 'SYNCED',
      updatedAt: DateTime.now().toUtc(),
    );
    await storage.database.insert(
      'local_record',
      record.toRow(),
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }
}
