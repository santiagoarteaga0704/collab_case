import '../models/assistant_command.dart';
import '../models/assistant_result.dart';
import '../models/local_record.dart';
import '../repositories/mobile_repository.dart';

class CommandExecutor {
  CommandExecutor(this.repository);

  final MobileRepository repository;

  Future<AssistantResult> execute(AssistantCommand command) async {
    switch (command.action) {
      case AssistantAction.list:
        final records = await repository.listRecords(command.entityType);
        if (records.isEmpty) {
          return AssistantResult(
            'No hay ${command.entityType.toLowerCase()} registrados.',
          );
        }
        final names = records.map(_displayName).join(', ');
        return AssistantResult(
          'Hay ${records.length} ${command.entityType.toLowerCase()}: $names.',
        );
      case AssistantAction.get:
        final records = await _select(command);
        if (records.isEmpty) {
          return AssistantResult(
            'No encontré ese ${command.entityType.toLowerCase()}.',
          );
        }
        if (records.length > 1) {
          return const AssistantResult(
            'Encontré más de un resultado. Indica cuál deseas consultar.',
          );
        }
        return AssistantResult(_displayName(records.single));
      case AssistantAction.create:
        final record = await repository.createRecord(
          command.entityType,
          command.data,
        );
        return AssistantResult(
          '${command.entityType} ${_displayName(record)} registrado. '
          'Se sincronizará cuando haya conexión.',
        );
      case AssistantAction.update:
        final records = await _select(command);
        if (records.isEmpty) {
          return AssistantResult(
            'No encontré ese ${command.entityType.toLowerCase()}.',
          );
        }
        if (records.length > 1) {
          return const AssistantResult(
            'Encontré más de un resultado. Indica cuál deseas modificar.',
          );
        }
        final updated = await repository.updateRecord(
          command.entityType,
          records.single.syncId,
          command.data,
        );
        return AssistantResult(
          '${command.entityType} ${_displayName(updated)} actualizado.',
        );
      case AssistantAction.delete:
        final records = await _select(command);
        if (records.isEmpty) {
          return AssistantResult(
            'No encontré ese ${command.entityType.toLowerCase()}.',
          );
        }
        if (records.length > 1) {
          return const AssistantResult(
            'Encontré más de un resultado. Indica cuál deseas eliminar.',
          );
        }
        await repository.deleteRecord(
          command.entityType,
          records.single.syncId,
        );
        return AssistantResult(
          '${command.entityType} ${_displayName(records.single)} eliminado.',
        );
    }
  }

  Future<List<LocalRecord>> _select(AssistantCommand command) async {
    if (command.syncId != null) {
      return (await repository.listRecords(command.entityType))
          .where((value) => value.syncId == command.syncId)
          .toList();
    }
    return repository.findRecords(command.entityType, command.selector);
  }

  Future<List<LocalRecord>> findTargets(AssistantCommand command) =>
      _select(command);

  String _displayName(LocalRecord record) =>
      record.data['nombre']?.toString() ?? record.syncId;
}
