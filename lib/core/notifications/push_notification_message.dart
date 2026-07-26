class PushNotificationMessage {
  const PushNotificationMessage({
    required this.data,
    this.messageId,
    this.title,
    this.body,
  });

  final String? messageId;
  final String? title;
  final String? body;
  final Map<String, String> data;
}
