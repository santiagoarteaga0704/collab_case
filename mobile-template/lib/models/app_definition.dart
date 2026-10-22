class AttributeDefinition {
  const AttributeDefinition({
    required this.name,
    required this.type,
    this.required = false,
  });

  final String name;
  final String type;
  final bool required;

  factory AttributeDefinition.fromJson(Map<String, dynamic> json) =>
      AttributeDefinition(
        name: json['name'] as String,
        type: (json['type'] ?? 'String').toString(),
        required: json['required'] == true || json['nullable'] == false,
      );
}

class EntityDefinition {
  const EntityDefinition({required this.name, required this.attributes});

  final String name;
  final List<AttributeDefinition> attributes;

  factory EntityDefinition.fromJson(Map<String, dynamic> json) =>
      EntityDefinition(
        name: (json['name'] ?? json['type'] ?? '').toString(),
        attributes: ((json['attributes'] as List?) ?? const [])
            .map(
              (value) => AttributeDefinition.fromJson(
                Map<String, dynamic>.from(value as Map),
              ),
            )
            .toList(),
      );

  AttributeDefinition? attribute(String candidate) {
    final normalized = candidate.toLowerCase();
    for (final value in attributes) {
      if (value.name.toLowerCase() == normalized) return value;
    }
    return null;
  }
}

class AppDefinition {
  const AppDefinition({
    required this.name,
    required this.entities,
    this.relationships = const [],
  });

  final String name;
  final List<EntityDefinition> entities;
  final List<Map<String, dynamic>> relationships;

  factory AppDefinition.fromJson(Map<String, dynamic> json) => AppDefinition(
    name: (json['name'] ?? json['applicationName'] ?? 'Aplicación').toString(),
    entities: ((json['entities'] as List?) ?? const [])
        .map(
          (value) => EntityDefinition.fromJson(
            Map<String, dynamic>.from(value as Map),
          ),
        )
        .where((entity) => entity.name.isNotEmpty)
        .toList(),
    relationships: ((json['relationships'] as List?) ?? const [])
        .map((value) => Map<String, dynamic>.from(value as Map))
        .toList(),
  );

  EntityDefinition? entity(String candidate) {
    final normalized = candidate.toLowerCase();
    for (final value in entities) {
      if (value.name.toLowerCase() == normalized) return value;
    }
    return null;
  }
}
