import 'package:potner_app/core/notifications/push_notification_message.dart';

enum PushPermissionStatus { authorized, provisional, denied, unavailable }

class PushDeviceRegistration {
  const PushDeviceRegistration({
    required this.installationId,
    required this.token,
    required this.platform,
  });

  final String installationId;
  final String token;
  final PushPlatform platform;
}

enum PushPlatform {
  android('ANDROID'),
  ios('IOS'),
  unsupported('UNSUPPORTED');

  const PushPlatform(this.apiValue);

  final String apiValue;
}

abstract interface class PushNotificationService {
  Future<void> prepare();

  Future<PushPermissionStatus> requestPermission();

  Future<PushDeviceRegistration?> getRegistration({String? token});

  Stream<String> get onTokenRefresh;

  Stream<String> get onInstallationIdChange;

  Stream<PushNotificationMessage> get onForegroundMessage;

  Stream<PushNotificationMessage> get onMessageOpenedApp;

  Future<PushNotificationMessage?> getInitialMessage();
}
