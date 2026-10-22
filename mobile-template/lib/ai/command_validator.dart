import '../models/app_definition.dart';
import '../models/assistant_command.dart';

class CommandValidator {
  void validate(AssistantCommand command, AppDefinition definition) {
    final entity = definition.entity(command.entityType);
    if (entity == null) throw const FormatException('Entidad desconocida');
    for (final field in {...command.selector.keys, ...command.data.keys}) {
      if (entity.attribute(field) == null) {
        throw FormatException('Atributo desconocido: $field');
      }
    }
    if (command.action == AssistantAction.create) {
      for (final attribute in entity.attributes.where(
        (value) => value.required,
      )) {
        if (!command.data.containsKey(attribute.name)) {
          throw FormatException(
            'Falta el atributo requerido: ${attribute.name}',
          );
        }
      }
    }
    if ({
          AssistantAction.update,
          AssistantAction.delete,
          AssistantAction.get,
        }.contains(command.action) &&
        command.syncId == null &&
        command.selector.isEmpty) {
      throw const FormatException('Se requiere selector o syncId');
    }
    for (final entry in {...command.selector, ...command.data}.entries) {
      final attribute = entity.attribute(entry.key)!;
      if (!_compatible(attribute.type, entry.value)) {
        throw FormatException('Tipo inválido para ${entry.key}');
      }
    }
    if (command.syncId != null &&
        !RegExp(
          r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
        ).hasMatch(command.syncId!)) {
      throw const FormatException('syncId inválido');
    }
  }

  bool _compatible(String type, Object? value) {
    final normalized = type.toLowerCase();
    if (value == null) return true;
    if (normalized.contains('bool')) return value is bool;
    if (normalized.contains('int') || normalized.contains('long')) {
      return value is int;
    }
    if (normalized.contains('double') ||
        normalized.contains('float') ||
        normalized.contains('decimal')) {
      return value is num;
    }
    return value is String;
  }
}
