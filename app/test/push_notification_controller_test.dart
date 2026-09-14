import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/core/notifications/firebase_push_notification_service.dart';
import 'package:potner_app/core/notifications/push_notification_controller.dart';
import 'package:potner_app/core/notifications/push_notification_message.dart';
import 'package:potner_app/core/notifications/push_notification_service.dart';
import 'package:potner_app/core/notifications/push_notification_state.dart';
import 'package:potner_app/core/notifications/push_token_repository.dart';

void main() {
  test('activate registers the current device and token', () async {
    final service = _FakePushNotificationService();
    final repository = _FakePushTokenRepository();
    final container = ProviderContainer(
      overrides: [
        pushNotificationServiceProvider.overrideWithValue(service),
        pushTokenRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(container.dispose);
    addTearDown(service.dispose);

    await container
        .read(pushNotificationControllerProvider.notifier)
        .activate();

    expect(repository.registered, hasLength(1));
    expect(repository.registered.single.installationId, 'installation-1');
    expect(repository.registered.single.token, 'token-1');
    expect(
      container.read(pushNotificationControllerProvider).status,
      PushNotificationStatus.registered,
    );
  });

  test(
    'token refresh replaces the registration for the installation',
    () async {
      final service = _FakePushNotificationService();
      final repository = _FakePushTokenRepository();
      final container = ProviderContainer(
        overrides: [
          pushNotificationServiceProvider.overrideWithValue(service),
          pushTokenRepositoryProvider.overrideWithValue(repository),
        ],
      );
      addTearDown(container.dispose);
      addTearDown(service.dispose);

      await container
          .read(pushNotificationControllerProvider.notifier)
          .activate();
      service.emitToken('token-2');
      await Future<void>.delayed(Duration.zero);
      await Future<void>.delayed(Duration.zero);

      expect(repository.registered, hasLength(2));
      expect(repository.registered.last.token, 'token-2');
      expect(repository.unregistered, isEmpty);
    },
  );

  test('logout unregisters the installation', () async {
    final service = _FakePushNotificationService();
    final repository = _FakePushTokenRepository();
    final container = ProviderContainer(
      overrides: [
        pushNotificationServiceProvider.overrideWithValue(service),
        pushTokenRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(container.dispose);
    addTearDown(service.dispose);

    final controller = container.read(
      pushNotificationControllerProvider.notifier,
    );
    await controller.activate();
    await controller.deactivate(unregister: true);

    expect(repository.unregistered, ['installation-1']);
    expect(
      container.read(pushNotificationControllerProvider).status,
      PushNotificationStatus.idle,
    );
  });
}

class _FakePushNotificationService implements PushNotificationService {
  final _tokenController = StreamController<String>.broadcast();

  void emitToken(String token) => _tokenController.add(token);

  Future<void> dispose() => _tokenController.close();

  @override
  Future<PushDeviceRegistration?> getRegistration({String? token}) async {
    return PushDeviceRegistration(
      installationId: 'installation-1',
      token: token ?? 'token-1',
      platform: PushPlatform.android,
    );
  }

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
  Stream<String> get onTokenRefresh => _tokenController.stream;

  @override
  Future<void> prepare() async {}

  @override
  Future<PushPermissionStatus> requestPermission() async =>
      PushPermissionStatus.authorized;
}

class _FakePushTokenRepository implements PushTokenRepository {
  final List<PushDeviceRegistration> registered = [];
  final List<String> unregistered = [];

  @override
  Future<void> register(PushDeviceRegistration registration) async {
    registered.add(registration);
  }

  @override
  Future<void> unregister(String installationId) async {
    unregistered.add(installationId);
  }
}
