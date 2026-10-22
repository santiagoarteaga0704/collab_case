import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

class ModelManager {
  static const _preferenceKey = 'assistant_model_path';

  Future<String?> configuredPath() async {
    final preferences = await SharedPreferences.getInstance();
    final value = preferences.getString(_preferenceKey);
    return value != null && await File(value).exists() ? value : null;
  }

  Future<String?> selectAndStore() async {
    final selection = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: const ['gguf'],
    );
    final source = selection?.path;
    if (source == null) return null;
    final root = await getApplicationSupportDirectory();
    final models = Directory(p.join(root.path, 'models'));
    await models.create(recursive: true);
    final destination = p.join(models.path, 'assistant.gguf');
    await File(source).copy(destination);
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_preferenceKey, destination);
    return destination;
  }
}
