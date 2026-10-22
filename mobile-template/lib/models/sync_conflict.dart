class SyncConflict {
  const SyncConflict({
    required this.mutationId,
    required this.entityType,
    required this.syncId,
    required this.serverVersion,
    required this.serverData,
    required this.message,
  });

  final String mutationId;
  final String entityType;
  final String syncId;
  final int serverVersion;
  final Map<String, dynamic>? serverData;
  final String? message;
}
