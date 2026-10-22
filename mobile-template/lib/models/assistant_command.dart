enum AssistantAction { list, get, create, update, delete }

class AssistantCommand {
  const AssistantCommand({
    required this.action,
    required this.entityType,
    this.selector = const {},
    this.syncId,
    this.data = const {},
  });

  final AssistantAction action;
  final String entityType;
  final Map<String, dynamic> selector;
  final String? syncId;
  final Map<String, dynamic> data;
}
