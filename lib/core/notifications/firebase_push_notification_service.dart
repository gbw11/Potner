import 'package:firebase_app_installations/firebase_app_installations.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/notifications/push_notification_message.dart';
import 'package:potner_app/core/notifications/push_notification_service.dart';

final pushNotificationServiceProvider = Provider<PushNotificationService>((
  ref,
) {
  if (Firebase.apps.isEmpty) {
    return const UnavailablePushNotificationService();
  }
  return FirebasePushNotificationService(
    FirebaseMessaging.instance,
    FirebaseInstallations.instance,
  );
});

class FirebasePushNotificationService implements PushNotificationService {
  FirebasePushNotificationService(this._messaging, this._installations);

  final FirebaseMessaging _messaging;
  final FirebaseInstallations _installations;

  @override
  Future<void> prepare() {
    return _messaging.setForegroundNotificationPresentationOptions(
      alert: true,
      badge: true,
      sound: true,
    );
  }

  @override
  Future<PushPermissionStatus> requestPermission() async {
    final settings = await _messaging.requestPermission(
      alert: true,
      announcement: false,
      badge: true,
      carPlay: false,
      criticalAlert: false,
      provisional: false,
      sound: true,
    );
    return switch (settings.authorizationStatus) {
      AuthorizationStatus.authorized => PushPermissionStatus.authorized,
      AuthorizationStatus.provisional => PushPermissionStatus.provisional,
      AuthorizationStatus.denied => PushPermissionStatus.denied,
      AuthorizationStatus.notDetermined => PushPermissionStatus.denied,
    };
  }

  @override
  Future<PushDeviceRegistration?> getRegistration({String? token}) async {
    final resolvedToken = token ?? await _messaging.getToken();
    if (resolvedToken == null || resolvedToken.isEmpty) {
      return null;
    }

    final platform = switch (defaultTargetPlatform) {
      TargetPlatform.android => PushPlatform.android,
      TargetPlatform.iOS => PushPlatform.ios,
      _ => PushPlatform.unsupported,
    };
    if (platform == PushPlatform.unsupported) {
      return null;
    }

    return PushDeviceRegistration(
      installationId: await _installations.getId(),
      token: resolvedToken,
      platform: platform,
    );
  }

  @override
  Stream<String> get onInstallationIdChange => _installations.onIdChange;

  @override
  Stream<PushNotificationMessage> get onForegroundMessage =>
      FirebaseMessaging.onMessage.map(_toMessage);

  @override
  Stream<PushNotificationMessage> get onMessageOpenedApp =>
      FirebaseMessaging.onMessageOpenedApp.map(_toMessage);

  @override
  Stream<String> get onTokenRefresh => _messaging.onTokenRefresh;

  @override
  Future<PushNotificationMessage?> getInitialMessage() async {
    final message = await _messaging.getInitialMessage();
    return message == null ? null : _toMessage(message);
  }

  PushNotificationMessage _toMessage(RemoteMessage message) {
    return PushNotificationMessage(
      messageId: message.messageId,
      title: message.notification?.title,
      body: message.notification?.body,
      data: message.data.map((key, value) => MapEntry(key, value.toString())),
    );
  }
}

class UnavailablePushNotificationService implements PushNotificationService {
  const UnavailablePushNotificationService();

  @override
  Future<PushDeviceRegistration?> getRegistration({String? token}) async =>
      null;

  @override
  Future<PushNotificationMessage?> getInitialMessage() async => null;

  @override
  Stream<String> get onInstallationIdChange => const Stream.empty();

  @override
  Stream<PushNotificationMessage> get onForegroundMessage =>
      const Stream.empty();

  @override
  Stream<PushNotificationMessage> get onMessageOpenedApp =>
      const Stream.empty();

  @override
  Stream<String> get onTokenRefresh => const Stream.empty();

  @override
  Future<void> prepare() async {}

  @override
  Future<PushPermissionStatus> requestPermission() async =>
      PushPermissionStatus.unavailable;
}
