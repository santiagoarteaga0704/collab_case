import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_template/ai/command_interpreter.dart';
import 'package:mobile_template/ai/local_ai_service.dart';
import 'package:mobile_template/ai/command_validator.dart';
import 'package:mobile_template/models/app_definition.dart';
import 'package:mobile_template/models/assistant_command.dart';
import 'package:lib_llama_cpp/lib_llama_cpp.dart';

class FailIfCalledFallback implements CommandFallback {
  @override
  Future<String?> interpret(String input, AppDefinition definition) {
    throw StateError('LLM must not be called');
  }
}

void main() {
  const definition = AppDefinition(
    name: 'Biblioteca',
    entities: [
      EntityDefinition(
        name: 'Cliente',
        attributes: [
          AttributeDefinition(name: 'nombre', type: 'String', required: true),
        ],
      ),
      EntityDefinition(name: 'Reserva', attributes: []),
      EntityDefinition(name: 'Barbero', attributes: []),
    ],
  );
  final interpreter = CommandInterpreter();
  final validator = CommandValidator();

  test(
    'LLM fallback uses raw text generation without message/tool grammar',
    () {
      final commands = buildPlainTextCommands('model.gguf', 'prompt');
      expect(commands.whereType<LlamaGenerateCommand>(), hasLength(1));
      expect(commands.whereType<LlamaGenerateMessagesCommand>(), isEmpty);
    },
  );

  test('CommandInterpreter CREATE uses dynamic entity', () {
    final command = interpreter.interpretDeterministic(
      'registra un cliente llamado Juan',
      definition,
    )!;
    expect(command.action, AssistantAction.create);
    expect(command.entityType, 'Cliente');
    expect(command.data, {'nombre': 'Juan'});
  });

  test('CommandInterpreter LIST supports accents and plural', () {
    final command = interpreter.interpretDeterministic(
      'muéstrame todos los clientes',
      definition,
    )!;
    expect(command.action, AssistantAction.list);
  });

  test('CommandInterpreter handles interrogative Cliente LIST without LLM', () {
    final command = interpreter.interpretDeterministic(
      'qué clientes hay',
      definition,
    )!;
    expect(command.action, AssistantAction.list);
    expect(command.entityType, 'Cliente');
  });

  test('interrogative Cliente LIST never invokes fallback', () async {
    final safeInterpreter = CommandInterpreter(
      fallback: FailIfCalledFallback(),
    );
    final command = await safeInterpreter.interpret(
      'qué clientes hay',
      definition,
    );
    expect(command!.action, AssistantAction.list);
    expect(command.entityType, 'Cliente');
  });

  test('CommandInterpreter handles interrogative Reserva LIST without LLM', () {
    final command = interpreter.interpretDeterministic(
      'qué reservas hay',
      definition,
    )!;
    expect(command.action, AssistantAction.list);
    expect(command.entityType, 'Reserva');
  });

  test('interrogative Reserva LIST never invokes fallback', () async {
    final safeInterpreter = CommandInterpreter(
      fallback: FailIfCalledFallback(),
    );
    final command = await safeInterpreter.interpret(
      'qué reservas hay',
      definition,
    );
    expect(command!.action, AssistantAction.list);
    expect(command.entityType, 'Reserva');
  });

  test('dynamic plural entity LIST never invokes fallback', () async {
    final safeInterpreter = CommandInterpreter(
      fallback: FailIfCalledFallback(),
    );
    final command = await safeInterpreter.interpret(
      'lista los barberos',
      definition,
    );
    expect(command!.action, AssistantAction.list);
    expect(command.entityType, 'Barbero');
  });

  test('CommandInterpreter UPDATE extracts selector and value', () {
    final command = interpreter.interpretDeterministic(
      'cambia el nombre del cliente Juan a Juan Pérez',
      definition,
    )!;
    expect(command.action, AssistantAction.update);
    expect(command.selector, {'nombre': 'Juan'});
    expect(command.data, {'nombre': 'Juan Pérez'});
  });

  test('CommandInterpreter DELETE extracts selector', () {
    final command = interpreter.interpretDeterministic(
      'elimina al cliente Juan',
      definition,
    )!;
    expect(command.action, AssistantAction.delete);
    expect(command.selector, {'nombre': 'Juan'});
  });

  test('structured fallback accepts fenced tool call', () {
    final command = interpreter.parseStructuredOutput('''texto
```json
{"name":"create_record","arguments":{"entityType":"Cliente","data":{"nombre":"Ana"}}}
```''')!;
    expect(command.action, AssistantAction.create);
    expect(command.data['nombre'], 'Ana');
  });

  test('structured fallback accepts plain JSON action', () {
    final command = interpreter.parseStructuredOutput(
      '{"action":"LIST","entityType":"Cliente"}',
    )!;
    expect(command.action, AssistantAction.list);
  });

  test('structured fallback safely rejects invalid JSON', () {
    expect(
      interpreter.parseStructuredOutput(
        'respuesta {"action":"LIST","entityType":} final',
      ),
      isNull,
    );
  });

  test('invalid entity is rejected', () {
    expect(
      () => validator.validate(
        const AssistantCommand(
          action: AssistantAction.list,
          entityType: 'Fantasma',
        ),
        definition,
      ),
      throwsFormatException,
    );
  });

  test('invalid field is rejected', () {
    expect(
      () => validator.validate(
        const AssistantCommand(
          action: AssistantAction.create,
          entityType: 'Cliente',
          data: {'secreto': 'x'},
        ),
        definition,
      ),
      throwsFormatException,
    );
  });

  test('required field is validated', () {
    expect(
      () => validator.validate(
        const AssistantCommand(
          action: AssistantAction.create,
          entityType: 'Cliente',
        ),
        definition,
      ),
      throwsFormatException,
    );
  });
}
