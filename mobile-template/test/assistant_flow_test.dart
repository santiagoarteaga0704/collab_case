import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_template/ai/command_executor.dart';
import 'package:mobile_template/ai/command_interpreter.dart';
import 'package:mobile_template/ai/command_validator.dart';
import 'package:mobile_template/ai/mobile_assistant.dart';
import 'package:mobile_template/data/local/database_service.dart';
import 'package:mobile_template/models/app_definition.dart';
import 'package:mobile_template/repositories/mobile_repository.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  late DatabaseService storage;
  late MobileRepository repository;
  late MobileAssistant assistant;

  const definition = AppDefinition(
    name: 'Biblioteca',
    entities: [
      EntityDefinition(
        name: 'Cliente',
        attributes: [
          AttributeDefinition(name: 'nombre', type: 'String', required: true),
        ],
      ),
    ],
  );

  setUpAll(sqfliteFfiInit);
  setUp(() async {
    storage = await DatabaseService.open(
      factory: databaseFactoryFfi,
      path: inMemoryDatabasePath,
    );
    repository = MobileRepository(storage);
    assistant = MobileAssistant(
      definition: definition,
      interpreter: CommandInterpreter(),
      validator: CommandValidator(),
      executor: CommandExecutor(repository),
    );
  });
  tearDown(() => storage.database.close());

  test(
    'text CREATE, READ, UPDATE and confirmed DELETE execute offline',
    () async {
      expect(
        (await assistant.process('registra un cliente llamado Juan')).message,
        'Cliente Juan registrado. Se sincronizará cuando haya conexión.',
      );
      expect(
        (await assistant.process('muéstrame todos los clientes')).message,
        contains('Juan'),
      );
      expect(
        (await assistant.process('cambia el nombre de Juan a Juan Pérez'))
            .message,
        'Cliente Juan Pérez actualizado.',
      );
      final confirmation = await assistant.process(
        'elimina al cliente Juan Pérez',
      );
      expect(confirmation.requiresConfirmation, isTrue);
      expect(await repository.listRecords('Cliente'), hasLength(1));
      expect(
        (await assistant.process('sí')).message,
        'Cliente Juan Pérez eliminado.',
      );
      expect(await repository.listRecords('Cliente'), isEmpty);
      expect(await repository.pendingMutations(), hasLength(3));
    },
  );
}
