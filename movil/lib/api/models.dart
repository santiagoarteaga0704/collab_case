class Usuario {
  Usuario({required this.id, required this.name, required this.email});

  factory Usuario.fromJson(Map<String, dynamic> json) =>
      Usuario(id: json['id'] as String, name: json['name'] as String? ?? '', email: json['email'] as String? ?? '');

  final String id;
  final String name;
  final String email;
}

class Proyecto {
  Proyecto({required this.id, required this.name, this.description, required this.role, this.updatedAt});

  factory Proyecto.fromJson(Map<String, dynamic> json) => Proyecto(
        id: json['id'] as String,
        name: json['name'] as String? ?? '',
        description: json['description'] as String?,
        role: json['role'] as String? ?? 'VIEWER',
        updatedAt: DateTime.tryParse(json['updatedAt'] as String? ?? ''),
      );

  final String id;
  final String name;
  final String? description;
  final String role;
  final DateTime? updatedAt;

  bool get puedeEditar => role == 'OWNER' || role == 'EDITOR';
}

class Version {
  Version({required this.id, required this.name, this.description, required this.main});

  factory Version.fromJson(Map<String, dynamic> json) => Version(
        id: json['id'] as String,
        name: json['name'] as String? ?? '',
        description: json['description'] as String?,
        main: json['mainVersion'] as bool? ?? false,
      );

  final String id;
  final String name;
  final String? description;
  final bool main;
}

class DiagramaResumen {
  DiagramaResumen({required this.id, required this.name, this.updatedAt});

  factory DiagramaResumen.fromJson(Map<String, dynamic> json) => DiagramaResumen(
        id: json['id'] as String,
        name: json['name'] as String? ?? '',
        updatedAt: DateTime.tryParse(json['updatedAt'] as String? ?? ''),
      );

  final String id;
  final String name;
  final DateTime? updatedAt;
}

class Integrante {
  Integrante({required this.userId, required this.name, required this.email, required this.role});

  factory Integrante.fromJson(Map<String, dynamic> json) => Integrante(
        userId: json['userId'] as String,
        name: json['name'] as String? ?? '',
        email: json['email'] as String? ?? '',
        role: json['role'] as String? ?? 'VIEWER',
      );

  final String userId;
  final String name;
  final String email;
  final String role;
}

const Map<String, String> nombresRol = {
  'OWNER': 'Propietario',
  'EDITOR': 'Editor',
  'VIEWER': 'Lector',
};
