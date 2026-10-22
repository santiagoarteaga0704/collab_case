class AssistantResult {
  const AssistantResult(this.message, {this.requiresConfirmation = false});

  final String message;
  final bool requiresConfirmation;
}
