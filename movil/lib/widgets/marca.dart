import 'package:flutter/material.dart';

import '../theme.dart';

/// El logo de la web (`BrandMark.jsx`): una clase UML con sus tres compartimentos.
class Marca extends StatelessWidget {
  const Marca({super.key, this.size = 26});

  final double size;

  @override
  Widget build(BuildContext context) => CustomPaint(size: Size.square(size), painter: _MarcaPainter());
}

class _MarcaPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final s = size.width / 24;
    final line = Paint()
      ..color = Colores.ink
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.8 * s;
    canvas.drawRRect(RRect.fromRectAndRadius(Rect.fromLTWH(2.5 * s, 2.5 * s, 19 * s, 19 * s), Radius.circular(2.5 * s)), line);
    canvas.drawLine(Offset(2.5 * s, 9 * s), Offset(21.5 * s, 9 * s), line);
    canvas.drawLine(Offset(2.5 * s, 15 * s), Offset(21.5 * s, 15 * s), line);
    canvas.drawCircle(Offset(18 * s, 6 * s), 1.6 * s, Paint()..color = Colores.collab);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
