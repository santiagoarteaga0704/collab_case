import 'dart:convert';

import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import '../../models/app_definition.dart';

class DatabaseService {
  DatabaseService._(this.database);

  final Database database;

  static Future<DatabaseService> open({
    DatabaseFactory? factory,
    String? path,
  }) async {
    final selectedFactory = factory ?? databaseFactory;
    final selectedPath =
        path ?? p.join(await getDatabasesPath(), 'collab_mobile.db');
    final db = await selectedFactory.openDatabase(
      selectedPath,
      options: OpenDatabaseOptions(version: 1, onCreate: _createSchema),
    );
    return DatabaseService._(db);
  }

  static Future<void> _createSchema(Database db, int version) async {
    await db.execute(
      'CREATE TABLE app_definition (id INTEGER PRIMARY KEY CHECK (id = 1), data_json TEXT NOT NULL)',
    );
    await db.execute('''CREATE TABLE local_record (
      entity_type TEXT NOT NULL,
      sync_id TEXT NOT NULL,
      data_json TEXT NOT NULL,
      version INTEGER NOT NULL,
      deleted INTEGER NOT NULL,
      sync_status TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      PRIMARY KEY (entity_type, sync_id)
    )''');
    await db.execute('''CREATE TABLE pending_mutation (
      mutation_id TEXT PRIMARY KEY,
      entity_type TEXT NOT NULL,
      sync_id TEXT NOT NULL,
      base_version INTEGER NOT NULL,
      operation TEXT NOT NULL,
      data_json TEXT,
      created_at TEXT NOT NULL,
      attempt_count INTEGER NOT NULL,
      last_error TEXT
    )''');
    await db.execute('''CREATE TABLE sync_conflict (
      mutation_id TEXT PRIMARY KEY,
      entity_type TEXT NOT NULL,
      sync_id TEXT NOT NULL,
      server_version INTEGER NOT NULL,
      server_data_json TEXT,
      message TEXT,
      created_at TEXT NOT NULL
    )''');
    await db.execute(
      'CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)',
    );
  }

  Future<void> saveDefinition(Map<String, dynamic> json) => database.insert(
    'app_definition',
    {'id': 1, 'data_json': jsonEncode(json)},
    conflictAlgorithm: ConflictAlgorithm.replace,
  );

  Future<AppDefinition?> loadDefinition() async {
    final rows = await database.query('app_definition', where: 'id = 1');
    if (rows.isEmpty) return null;
    return AppDefinition.fromJson(
      Map<String, dynamic>.from(
        jsonDecode(rows.single['data_json']! as String) as Map,
      ),
    );
  }

  Future<String?> readState(String key) async {
    final rows = await database.query(
      'sync_state',
      where: 'key = ?',
      whereArgs: [key],
    );
    return rows.isEmpty ? null : rows.single['value'] as String;
  }

  Future<void> writeState(String key, String value) => database.insert(
    'sync_state',
    {'key': key, 'value': value},
    conflictAlgorithm: ConflictAlgorithm.replace,
  );
}
