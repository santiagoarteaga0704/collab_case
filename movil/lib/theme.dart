import 'package:flutter/material.dart';

/// Los mismos colores que la web (`frontend/src/index.css`).
abstract final class Colores {
  static const paper = Color(0xFFF2F3F0);
  static const sheet = Color(0xFFFCFCFB);
  static const ink = Color(0xFF1D232A);
  static const muted = Color(0xFF5C6570);
  static const faint = Color(0xFF8A929B);
  static const rule = Color(0xFFD6D9D3);
  static const accent = Color(0xFF13615A);
  static const accentTint = Color(0xFFE2EEEB);
  static const collab = Color(0xFFD9962B);
  static const collabTint = Color(0xFFFBF0DC);
  static const collabInk = Color(0xFF85560A);
  static const danger = Color(0xFFB3362F);
  static const dangerTint = Color(0xFFF8E6E4);
  static const success = Color(0xFF2C7A4B);
  static const successTint = Color(0xFFE3F1E8);
}

ThemeData temaCollabCase() {
  final scheme = ColorScheme.fromSeed(
    seedColor: Colores.accent,
    primary: Colores.accent,
    surface: Colores.sheet,
    error: Colores.danger,
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: Colores.paper,
    appBarTheme: const AppBarTheme(
      backgroundColor: Colores.sheet,
      foregroundColor: Colores.ink,
      elevation: 0,
      scrolledUnderElevation: 1,
      titleTextStyle: TextStyle(color: Colores.ink, fontSize: 18, fontWeight: FontWeight.w600),
    ),
    cardTheme: CardThemeData(
      color: Colores.sheet,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(10),
        side: const BorderSide(color: Colores.rule),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: Colores.accent,
        minimumSize: const Size(48, 46),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(48, 46),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    ),
  );
}
