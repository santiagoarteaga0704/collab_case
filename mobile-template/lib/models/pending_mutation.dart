import 'dart:convert';

class PendingMutation {
  const PendingMutation({
    required this.mutationId,
    required this.entityType,
    required this.syncId,
    required this.baseVersion,
    required this.operation,
    required this.data,
    required this.createdAt,
    this.attemptCount = 0,
    this.lastError,
  });

  final String mutationId;
  final String entityType;
  final String syncId;
  final int baseVersion;
  final String operation;
  final Map<String, dynamic>? data;
  final DateTime createdAt;
  final int attemptCount;
  final String? lastError;

  Map<String, Object?> toRow() => {
    'mutation_id': mutationId,
    'entity_type': entityType,
    'sync_id': syncId,
    'base_version': baseVersion,
    'operation': operation,
    'data_json': data == null ? null : jsonEncode(data),
    'created_at': createdAt.toUtc().toIso8601String(),
    'attempt_count': attemptCount,
    'last_error': lastError,
  };

  Map<String, dynamic> toApiJson() => {
    'mutationId': mutationId,
    'entityType': entityType,
    'syncId': syncId,
    'baseVersion': baseVersion,
    'operation': operation,
    'data': data,
  };

  factory PendingMutation.fromRow(Map<String, Object?> row) => PendingMutation(
    mutationId: row['mutation_id']! as String,
    entityType: row['entity_type']! as String,
    syncId: row['sync_id']! as String,
    baseVersion: row['base_version']! as int,
    operation: row['operation']! as String,
    data: row['data_json'] == null
        ? null
        : Map<String, dynamic>.from(
            jsonDecode(row['data_json']! as String) as Map,
          ),
    createdAt: DateTime.parse(row['created_at']! as String),
    attemptCount: row['attempt_count']! as int,
    lastError: row['last_error'] as String?,
  );
}
