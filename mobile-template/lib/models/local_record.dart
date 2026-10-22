import 'dart:convert';

class LocalRecord {
  const LocalRecord({
    required this.entityType,
    required this.syncId,
    required this.data,
    required this.version,
    required this.deleted,
    required this.syncStatus,
    required this.updatedAt,
  });

  final String entityType;
  final String syncId;
  final Map<String, dynamic> data;
  final int version;
  final bool deleted;
  final String syncStatus;
  final DateTime updatedAt;

  Map<String, Object?> toRow() => {
    'entity_type': entityType,
    'sync_id': syncId,
    'data_json': jsonEncode(data),
    'version': version,
    'deleted': deleted ? 1 : 0,
    'sync_status': syncStatus,
    'updated_at': updatedAt.toUtc().toIso8601String(),
  };

  factory LocalRecord.fromRow(Map<String, Object?> row) => LocalRecord(
    entityType: row['entity_type']! as String,
    syncId: row['sync_id']! as String,
    data: Map<String, dynamic>.from(
      jsonDecode(row['data_json']! as String) as Map,
    ),
    version: row['version']! as int,
    deleted: row['deleted'] == 1,
    syncStatus: row['sync_status']! as String,
    updatedAt: DateTime.parse(row['updated_at']! as String),
  );
}
