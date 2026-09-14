import 'package:potner_app/core/notifications/push_notification_message.dart';

enum PushNotificationStatus {
  idle,
  requestingPermission,
  synchronizing,
  registered,
  denied,
  unavailable,
  failure,
}

class PushNotificationState {
  const PushNotificationState({
    this.status = PushNotificationStatus.idle,
    this.lastForegroundMessage,
    this.lastOpenedMessage,
    this.eventSequence = 0,
  });

  final PushNotificationStatus status;
  final PushNotificationMessage? lastForegroundMessage;
  final PushNotificationMessage? lastOpenedMessage;
  final int eventSequence;

  PushNotificationState copyWith({
    PushNotificationStatus? status,
    PushNotificationMessage? lastForegroundMessage,
    PushNotificationMessage? lastOpenedMessage,
    bool clearMessages = false,
    int? eventSequence,
  }) {
    return PushNotificationState(
      status: status ?? this.status,
      lastForegroundMessage: clearMessages
          ? null
          : lastForegroundMessage ?? this.lastForegroundMessage,
      lastOpenedMessage: clearMessages
          ? null
          : lastOpenedMessage ?? this.lastOpenedMessage,
      eventSequence: eventSequence ?? this.eventSequence,
    );
  }
}
