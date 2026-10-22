import '../config/app_config.dart';
import '../models/app_definition.dart';
import '../models/assistant_command.dart';
import '../models/assistant_result.dart';
import 'command_executor.dart';
import 'command_interpreter.dart';
import 'command_validator.dart';

class MobileAssistant {
  MobileAssistant({
    required this.definition,
    required this.interpreter,
    required this.validator,
    required this.executor,
  });

  final AppDefinition definition;
  final CommandInterpreter interpreter;
  final CommandValidator validator;
  final CommandExecutor executor;
  AssistantCommand? _pendingDelete;

  Future<AssistantResult> process(String input) async {
    _log('message=$input');
    final normalized = input.trim().toLowerCase();
    if (_pendingDelete != null) {
      if ({'si', 'sí', 'confirmo', 'confirmar'}.contains(normalized)) {
        final command = _pendingDelete!;
        _pendingDelete = null;
        return executor.execute(command);
      }
      if ({'no', 'cancelar', 'cancela'}.contains(normalized)) {
        _pendingDelete = null;
        return const AssistantResult('Eliminación cancelada.');
      }
      return const AssistantResult(
        'Responde sí para confirmar o no para cancelar.',
      );
    }

    final deterministic = interpreter.interpretDeterministic(input, definition);
    _log(
      'deterministicIntent=${deterministic?.action.name.toUpperCase() ?? 'NONE'}',
    );
    _log('resolvedEntity=${deterministic?.entityType ?? 'NONE'}');
    _log('usingLlm=${deterministic == null}');
    final command =
        deterministic ?? await interpreter.interpret(input, definition);
    if (command == null) {
      _log('parsedCommand=null');
      return const AssistantResult(
        'No pude interpretar esa solicitud. Intenta decirla de otra forma.',
      );
    }
    _log('action=${command.action.name.toUpperCase()}');
    _log(
      'parsedCommand=${command.action.name.toUpperCase()} ${command.entityType}',
    );
    _log(
      'command=${command.action.name} entity=${command.entityType} selector=${command.selector} data=${command.data}',
    );
    try {
      validator.validate(command, definition);
      _log('validation=ok');
    } catch (error) {
      _log('validation=failed error=$error');
      return AssistantResult('La solicitud no es válida: $error');
    }
    if (command.action == AssistantAction.delete) {
      final targets = await executor.findTargets(command);
      if (targets.isEmpty) {
        return AssistantResult(
          'No encontré ese ${command.entityType.toLowerCase()}.',
        );
      }
      if (targets.length > 1) {
        return const AssistantResult(
          'Encontré más de un resultado. Indica cuál deseas eliminar.',
        );
      }
      _pendingDelete = command;
      final name = targets.single.data['nombre'] ?? targets.single.syncId;
      return AssistantResult(
        '¿Confirmas que deseas eliminar a $name?',
        requiresConfirmation: true,
      );
    }
    final result = await executor.execute(command);
    _log('execution=${result.message}');
    return result;
  }

  void _log(String message) {
    if (AppConfig.showDebugStatus) {
      // ignore: avoid_print
      print('[MOBILE_AI] $message');
    }
  }
}
