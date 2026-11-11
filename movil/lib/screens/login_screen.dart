import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../main.dart';
import '../theme.dart';
import '../widgets/marca.dart';

/// Ingreso y registro (mismas reglas que `Login.jsx` y `Register.jsx`).
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _confirmation = TextEditingController();
  bool _registering = false;
  bool _busy = false;
  bool _hidden = true;
  String _error = '';

  @override
  void dispose() {
    _name.dispose();
    _email.dispose();
    _password.dispose();
    _confirmation.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = '';
    });
    final session = SessionScope.of(context);
    try {
      if (_registering) {
        await session.register(_name.text, _email.text, _password.text);
      } else {
        await session.login(_email.text, _password.text);
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() => _error = switch ((_registering, e.status)) {
              (false, 401) => 'Correo o contraseña incorrectos.',
              (true, 409) => 'Ese email ya tiene una cuenta. Inicia sesión con él.',
              _ => e.message,
            });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Form(
                key: _form,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Row(children: [Marca(), SizedBox(width: 10), Text('Collab CASE', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700))]),
                    const SizedBox(height: 28),
                    Text(_registering ? 'Crear cuenta' : 'Iniciar sesión',
                        style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700)),
                    const SizedBox(height: 6),
                    Text(
                      _registering
                          ? 'Regístrate para modelar con tu equipo y la IA.'
                          : 'Modela diagramas UML en equipo, con la IA como un integrante más.',
                      style: const TextStyle(color: Colores.muted),
                    ),
                    const SizedBox(height: 24),
                    if (_registering) ...[
                      TextFormField(
                        key: const ValueKey('name'),
                      controller: _name,
                        decoration: const InputDecoration(labelText: 'Nombre'),
                        textCapitalization: TextCapitalization.words,
                        validator: (v) => (v ?? '').trim().isEmpty ? 'Escribe tu nombre.' : null,
                      ),
                      const SizedBox(height: 14),
                    ],
                    TextFormField(
                      key: const ValueKey('email'),
                      controller: _email,
                      decoration: const InputDecoration(labelText: 'Correo'),
                      keyboardType: TextInputType.emailAddress,
                      autocorrect: false,
                      validator: (v) => RegExp(r'^\S+@\S+\.\S+$').hasMatch((v ?? '').trim()) ? null : 'Escribe un correo válido.',
                    ),
                    const SizedBox(height: 14),
                    TextFormField(
                      key: const ValueKey('password'),
                      controller: _password,
                      obscureText: _hidden,
                      decoration: InputDecoration(
                        labelText: 'Contraseña',
                        suffixIcon: IconButton(
                          tooltip: _hidden ? 'Mostrar contraseña' : 'Ocultar contraseña',
                          icon: Icon(_hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                          onPressed: () => setState(() => _hidden = !_hidden),
                        ),
                      ),
                      validator: (v) {
                        if ((v ?? '').isEmpty) return 'Escribe tu contraseña.';
                        if (_registering && v!.length < 8) return 'Usa al menos 8 caracteres.';
                        return null;
                      },
                    ),
                    if (_registering) ...[
                      const SizedBox(height: 14),
                      TextFormField(
                        key: const ValueKey('confirmation'),
                        controller: _confirmation,
                        obscureText: _hidden,
                        decoration: const InputDecoration(labelText: 'Repite la contraseña'),
                        validator: (v) => v == _password.text ? null : 'Las contraseñas no coinciden.',
                      ),
                    ],
                    if (_error.isNotEmpty) ...[
                      const SizedBox(height: 14),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(color: Colores.dangerTint, borderRadius: BorderRadius.circular(8)),
                        child: Text(_error, style: const TextStyle(color: Colores.danger)),
                      ),
                    ],
                    const SizedBox(height: 22),
                    FilledButton(
                      onPressed: _busy ? null : _submit,
                      child: _busy
                          ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                          : Text(_registering ? 'Crear cuenta' : 'Ingresar'),
                    ),
                    const SizedBox(height: 12),
                    TextButton(
                      onPressed: _busy
                          ? null
                          : () {
                              _form.currentState?.reset();
                              setState(() {
                                _registering = !_registering;
                                _error = '';
                              });
                            },
                      child: Text(_registering ? '¿Ya tienes cuenta? Inicia sesión' : '¿No tienes cuenta? Regístrate'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
