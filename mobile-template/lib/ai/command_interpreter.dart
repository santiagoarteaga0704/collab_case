import 'dart:convert';

import '../models/app_definition.dart';
import '../models/assistant_command.dart';

abstract interface class CommandFallback {
  Future<String?> interpret(String input, AppDefinition definition);
}

class CommandInterpreter {
  CommandInterpreter({this.fallback});

  final CommandFallback? fallback;

  Future<AssistantCommand?> interpret(
    String input,
    AppDefinition definition,
  ) async {
    final deterministic = interpretDeterministic(input, definition);
    if (deterministic != null) return deterministic;
    final output = await fallback?.interpret(input, definition);
    return output == null ? null : parseStructuredOutput(output);
  }

  AssistantCommand? interpretDeterministic(
    String input,
    AppDefinition definition,
  ) {
    final normalized = _normalize(input);
    var entity = _matchEntity(normalized, definition);
    if (entity == null &&
        _startsWith(normalized, const [
          'cambia',
          'cambiar',
          'modifica',
          'modificar',
          'actualiza',
          'actualizar',
        ])) {
      final candidates = definition.entities
          .where((value) => value.attribute('nombre') != null)
          .toList();
      if (candidates.length == 1) entity = candidates.single;
    }
    if (entity == null) return null;
    if (_startsWith(normalized, const [
      'crea',
      'crear',
      'registra',
      'registrar',
      'agrega',
      'agregar',
      'anade',
      'anadir',
      'nuevo',
      'nueva',
    ])) {
      final named = RegExp(
        r'\b(?:llamad[oa]|nombre)\s+(.+?)(?:[.!?]|$)',
        caseSensitive: false,
      ).firstMatch(input.trim());
      final nameAttribute = entity.attribute('nombre');
      if (named == null || nameAttribute == null) return null;
      return AssistantCommand(
        action: AssistantAction.create,
        entityType: entity.name,
        data: {nameAttribute.name: named.group(1)!.trim()},
      );
    }
    if (_startsWith(normalized, const [
      'lista',
      'listar',
      'muestra',
      'mostrar',
      'muestrame',
      'consulta',
      'consultar',
      'que',
      'cuales',
    ])) {
      return AssistantCommand(
        action: AssistantAction.list,
        entityType: entity.name,
      );
    }
    if (_startsWith(normalized, const [
      'cambia',
      'cambiar',
      'modifica',
      'modificar',
      'actualiza',
      'actualizar',
    ])) {
      final nameAttribute = entity.attribute('nombre');
      if (nameAttribute == null) return null;
      var rest = input.trim().replaceFirst(
        RegExp(
          r'^(?:cambia|cambiar|modifica|modificar|actualiza|actualizar)\s+',
          caseSensitive: false,
        ),
        '',
      );
      rest = rest.replaceFirst(
        RegExp(r'^(?:el\s+)?nombre\s+(?:de(?:l)?\s+)?', caseSensitive: false),
        '',
      );
      final entityName = RegExp.escape(entity.name);
      rest = rest.replaceFirst(
        RegExp(
          '^(?:el |la )?(?:$entityName|${RegExp.escape(_plural(entity.name))})\\s+',
          caseSensitive: false,
        ),
        '',
      );
      final match = RegExp(
        r'^(.+?)\s+a\s+(.+?)(?:[.!?]|$)',
        caseSensitive: false,
      ).firstMatch(rest);
      if (match == null) return null;
      return AssistantCommand(
        action: AssistantAction.update,
        entityType: entity.name,
        selector: {nameAttribute.name: match.group(1)!.trim()},
        data: {nameAttribute.name: match.group(2)!.trim()},
      );
    }
    if (_startsWith(normalized, const [
      'elimina',
      'eliminar',
      'borra',
      'borrar',
    ])) {
      final nameAttribute = entity.attribute('nombre');
      if (nameAttribute == null) return null;
      final entityWord = RegExp.escape(entity.name);
      final match = RegExp(
        '(?:$entityWord|${RegExp.escape(_plural(entity.name))})\\s+(.+?)(?:[.!?]|\$)',
        caseSensitive: false,
      ).firstMatch(input.trim());
      if (match == null) return null;
      return AssistantCommand(
        action: AssistantAction.delete,
        entityType: entity.name,
        selector: {nameAttribute.name: match.group(1)!.trim()},
      );
    }
    return null;
  }

  AssistantCommand? parseStructuredOutput(String output) {
    final object = _firstJsonObject(output);
    if (object == null) return null;
    Object? decoded;
    try {
      decoded = jsonDecode(object);
    } on FormatException {
      return null;
    }
    if (decoded is! Map) return null;
    var json = Map<String, dynamic>.from(decoded);
    if (json['name'] is String && json['arguments'] is Map) {
      final action = switch (json['name']) {
        'list_records' => 'LIST',
        'get_record' => 'GET',
        'create_record' => 'CREATE',
        'update_record' => 'UPDATE',
        'delete_record' => 'DELETE',
        _ => null,
      };
      if (action == null) return null;
      json = {
        ...Map<String, dynamic>.from(json['arguments'] as Map),
        'action': action,
      };
    }
    final action = AssistantAction.values
        .where(
          (value) =>
              value.name.toUpperCase() ==
              json['action']?.toString().toUpperCase(),
        )
        .firstOrNull;
    final entityType = json['entityType']?.toString();
    if (action == null || entityType == null) return null;
    return AssistantCommand(
      action: action,
      entityType: entityType,
      selector: Map<String, dynamic>.from(
        (json['selector'] as Map?) ?? const {},
      ),
      syncId: json['syncId']?.toString(),
      data: Map<String, dynamic>.from((json['data'] as Map?) ?? const {}),
    );
  }

  EntityDefinition? _matchEntity(String input, AppDefinition definition) {
    for (final entity in definition.entities) {
      final singular = _normalize(entity.name);
      if (RegExp('\\b(?:$singular|${_plural(singular)})\\b').hasMatch(input)) {
        return entity;
      }
    }
    return null;
  }

  bool _startsWith(String input, List<String> verbs) =>
      verbs.any((verb) => RegExp('^$verb\\b').hasMatch(input));
  String _plural(String value) => value.endsWith('s')
      ? value
      : RegExp('[aeiou]\$').hasMatch(value)
      ? '${value}s'
      : '${value}es';
  String _normalize(String value) => value
      .toLowerCase()
      .replaceAll(RegExp('[áàä]'), 'a')
      .replaceAll(RegExp('[éèë]'), 'e')
      .replaceAll(RegExp('[íìï]'), 'i')
      .replaceAll(RegExp('[óòö]'), 'o')
      .replaceAll(RegExp('[úùü]'), 'u')
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();

  String? _firstJsonObject(String value) {
    var start = -1;
    var depth = 0;
    var inString = false;
    var escaped = false;
    for (var index = 0; index < value.length; index++) {
      final character = value[index];
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (character == r'\') {
          escaped = true;
        } else if (character == '"') {
          inString = false;
        }
        continue;
      }
      if (character == '"') inString = true;
      if (character == '{') {
        if (depth == 0) start = index;
        depth++;
      } else if (character == '}' && depth > 0) {
        depth--;
        if (depth == 0) return value.substring(start, index + 1);
      }
    }
    return null;
  }
}
