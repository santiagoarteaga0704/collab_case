import 'package:flutter/material.dart';

import 'api/api_client.dart';
import 'screens/login_screen.dart';
import 'screens/projects_screen.dart';
import 'session.dart';
import 'theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final session = Session(ApiClient())..restore();
  runApp(CollabCaseApp(session: session));
}

/// Da acceso a la sesión desde cualquier pantalla.
class SessionScope extends InheritedNotifier<Session> {
  const SessionScope({super.key, required Session session, required super.child}) : super(notifier: session);

  static Session of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<SessionScope>()!.notifier!;
}

class CollabCaseApp extends StatelessWidget {
  const CollabCaseApp({super.key, required this.session});

  final Session session;

  @override
  Widget build(BuildContext context) {
    return SessionScope(
      session: session,
      child: MaterialApp(
        title: 'Collab CASE',
        debugShowCheckedModeBanner: false,
        theme: temaCollabCase(),
        home: const _Inicio(),
      ),
    );
  }
}

class _Inicio extends StatelessWidget {
  const _Inicio();

  @override
  Widget build(BuildContext context) {
    final session = SessionScope.of(context);
    if (session.loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    // al cerrar sesión (o vencer el token) se vuelve a la pantalla de ingreso
    return session.user == null ? const LoginScreen() : const ProjectsScreen();
  }
}
