import 'dart:convert';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:share_plus/share_plus.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

import '../config.dart';
import '../main.dart';
import '../theme.dart';
import '../widgets/comunes.dart';

/// Editor UML colaborativo. Apollon (el editor) y Yjs solo existen para la web, así que
/// el teléfono abre el mismo editor de la app desplegada, con la sesión del teléfono. Desde
/// aquí funcionan igual que en la web: la edición en vivo, el chat con la IA que dibuja,
/// el asistente de modelado, la voz, la foto de pizarra, exportar y generar código.
class EditorScreen extends StatefulWidget {
  const EditorScreen({super.key, required this.projectId, required this.versionId, this.diagramId});

  final String projectId;
  final String versionId;
  final String? diagramId;

  @override
  State<EditorScreen> createState() => _EditorScreenState();
}

class _EditorScreenState extends State<EditorScreen> {
  late final WebViewController _controller;
  bool _loading = true;
  bool _tokenSet = false;
  String? _error;
  IOSink? _download;
  File? _downloadFile;

  String get _editorUrl =>
      '$servidor/projects/${widget.projectId}/versions/${widget.versionId}/diagrams/${widget.diagramId ?? 'new'}';

  // Ajustes de pantalla de celular y puente de descargas (assets/editor_movil.js).
  static Future<String>? _ajustes;


  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colores.paper)
      ..addJavaScriptChannel('CollabDescarga', onMessageReceived: (m) => _onDownloadMessage(m.message))
      ..setNavigationDelegate(NavigationDelegate(
        onPageFinished: _onPageFinished,
        onUrlChange: (change) => _onUrlChange(change.url),
        onWebResourceError: (error) {
          if (error.isForMainFrame ?? true) {
            setState(() {
              _loading = false;
              _error = 'No se pudo abrir el editor. Revisa tu conexión.';
            });
          }
        },
      ));
    final platform = _controller.platform;
    if (platform is AndroidWebViewController) {
      platform.setMediaPlaybackRequiresUserGesture(false);
      platform.setOnShowFileSelector(_chooseFiles);
      platform.setOnPlatformPermissionRequest(_onPermissionRequest);
    }
    _start();
  }

  @override
  void dispose() {
    _download?.close();
    super.dispose();
  }

  // Primero se abre un archivo liviano del mismo servidor para dejar el token de la sesión del
  // teléfono en localStorage, que es donde lo busca la web; después se abre el editor.
  Future<void> _start() async {
    setState(() {
      _loading = true;
      _error = null;
      _tokenSet = false;
    });
    await _controller.loadRequest(Uri.parse('$servidor/favicon.svg'));
  }

  Future<void> _onPageFinished(String url) async {
    if (!_tokenSet) {
      _tokenSet = true;
      final token = jsonEncode(SessionScope.of(context).token ?? '');
      await _controller.runJavaScript("localStorage.setItem('$claveTokenWeb', $token)");
      await _controller.loadRequest(Uri.parse(_editorUrl));
      return;
    }
    _ajustes ??= rootBundle.loadString('assets/editor_movil.js');
    await _controller.runJavaScript(await _ajustes!);
    if (mounted) setState(() => _loading = false);
  }

  void _onUrlChange(String? url) {
    if (url == null || !_tokenSet || !mounted) return;
    final path = Uri.parse(url).path;
    if (path.startsWith('/login')) {
      // la web no aceptó el token: la sesión venció
      SessionScope.of(context).logout();
      Navigator.of(context).popUntil((r) => r.isFirst);
    } else if (!path.contains('/diagrams/') && path != '/favicon.svg') {
      // el botón «Volver al proyecto» de la web vuelve a la pantalla nativa
      Navigator.of(context).maybePop();
    }
  }

  Future<List<String>> _chooseFiles(FileSelectorParams params) async {
    final accepts = params.acceptTypes.join(',');
    final images = accepts.contains('image');
    String? path;
    if (images) {
      final source = await showModalBottomSheet<ImageSource>(
        context: context,
        builder: (context) => SafeArea(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const ListTile(title: Text('Foto del diagrama', style: TextStyle(fontWeight: FontWeight.w700))),
            ListTile(
              leading: const Icon(Icons.photo_camera_outlined),
              title: const Text('Tomar foto (pizarra o papel)'),
              onTap: () => Navigator.pop(context, ImageSource.camera),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('Elegir de la galería'),
              onTap: () => Navigator.pop(context, ImageSource.gallery),
            ),
          ]),
        ),
      );
      if (source == null) return [];
      final picked = await ImagePicker().pickImage(source: source, maxWidth: 2400, imageQuality: 85);
      path = picked?.path;
    } else {
      final files = await FilePicker.pickFiles();
      path = files.isEmpty ? null : files.first.path;
    }
    return path == null ? [] : [Uri.file(path).toString()];
  }

  Future<void> _onPermissionRequest(PlatformWebViewPermissionRequest request) async {
    final wantsMic = request.types.contains(WebViewPermissionResourceType.microphone);
    if (wantsMic && !(await Permission.microphone.request()).isGranted) {
      await request.deny();
      if (mounted) avisar(context, 'Permite el micrófono para dictar por voz.');
      return;
    }
    await request.grant();
  }

  Future<void> _onDownloadMessage(String raw) async {
    final message = jsonDecode(raw) as Map<String, dynamic>;
    switch (message['type']) {
      case 'start':
        final dir = await getTemporaryDirectory();
        final name = '${message['name']}'.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_');
        _downloadFile = File('${dir.path}/$name');
        _download = _downloadFile!.openWrite();
        if (mounted) avisar(context, 'Descargando $name…');
      case 'chunk':
        _download?.add(base64Decode(message['data'] as String));
      case 'end':
        await _download?.close();
        _download = null;
        final file = _downloadFile;
        if (file != null && mounted) {
          await SharePlus.instance.share(ShareParams(files: [XFile(file.path)], text: file.uri.pathSegments.last));
        }
      default:
        await _download?.close();
        _download = null;
        if (mounted) avisar(context, 'No se pudo descargar el archivo.');
    }
  }

  // Atrás del teléfono: cierra el asistente, un diálogo o el chat si están abiertos.
  static const _cerrarAbierto = r'''(function () {
  if (window.__collabMovilCerrarAsistente && window.__collabMovilCerrarAsistente()) return 'si';
  var b = document.querySelector('.export-dialog [aria-label="Cerrar"], .export-dialog .icon-button, .team-chat-drawer.is-open .team-chat-header .icon-button, .agent-drawer [aria-label="Cerrar agente"]');
  if (b) { b.click(); return 'si'; }
  return 'no';
})()''';

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        // atrás del teléfono: primero cierra lo que la web tenga abierto, después sale
        final navigator = Navigator.of(context);
        final closed = await _controller.runJavaScriptReturningResult(_cerrarAbierto);
        if ('$closed'.contains('si')) return;
        navigator.pop();
      },
      child: Scaffold(
        body: SafeArea(
          child: Stack(children: [
            if (_error == null) WebViewWidget(controller: _controller),
            if (_error != null)
              ErrorConReintento(mensaje: _error!, onRetry: _start),
            if (_loading && _error == null)
              Container(
                color: Colores.paper,
                child: const Center(
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                    CircularProgressIndicator(),
                    SizedBox(height: 14),
                    Text('Abriendo el editor…', style: TextStyle(color: Colores.muted)),
                  ]),
                ),
              ),
          ]),
        ),
      ),
    );
  }
}
