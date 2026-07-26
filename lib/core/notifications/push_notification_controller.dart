import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/notifications/firebase_push_notification_service.dart';
import 'package:potner_app/core/notifications/push_notification_message.dart';
import 'package:potner_app/core/notifications/push_notification_service.dart';
import 'package:potner_app/core/notifications/push_notification_state.dart';
import 'package:potner_app/core/notifications/push_token_repository.dart';

final pushNotificationControllerProvider =
    NotifierProvider<PushNotificationController, PushNotificationState>(
      PushNotificationController.new,
    );

class PushNotificationController extends Notifier<PushNotificationState> {
  late final PushNotificationService _service;
  Future<void>? _activationFuture;
  Future<void> _synchronizationQueue = Future.value();
  bool _active = false;
  String? _registeredInstallationId;
  final List<StreamSubscription<Object?>> _subscriptions = [];

  @override
  PushNotificationState build() {
    _service = ref.read(pushNotificationServiceProvider);
    ref.onDispose(() {
      for (final subscription in _subscriptions) {
        unawaited(subscription.cancel());
      }
    });
    return const PushNotificationState();
  }

  Future<void> activate() {
    final pending = _activationFuture;
    if (pending != null) {
      return pending;
    }
    if (_active) {
      return _enqueueSynchronization();
    }

    late final Future<void> activation;
    activation = _activate().whenComplete(() {
      if (identical(_activationFuture, activation)) {
        _activationFuture = null;
      }
    });
    _activationFuture = activation;
    return activation;
  }

  Future<void> _activate() async {
    state = state.copyWith(status: PushNotificationStatus.requestingPermission);

    try {
      await _service.prepare();
      final permission = await _service.requestPermission();
      if (permission == PushPermissionStatus.unavailable) {
        state = state.copyWith(status: PushNotificationStatus.unavailable);
        return;
      }
      if (permission == PushPermissionStatus.denied) {
        state = state.copyWith(status: PushNotificationStatus.denied);
        return;
      }

      _active = true;
      _listenForFirebaseEvents();
      await _enqueueSynchronization();

      final initialMessage = await _service.getInitialMessage();
      if (initialMessage != null) {
        _recordOpenedMessage(initialMessage);
      }
    } on Object {
      state = state.copyWith(status: PushNotificationStatus.failure);
    }
  }

  void _listenForFirebaseEvents() {
    if (_subscriptions.isNotEmpty) {
      return;
    }
    _subscriptions
      ..add(
        _service.onTokenRefresh.listen(
          (token) => unawaited(_enqueueSynchronization(token: token)),
        ),
      )
      ..add(
        _service.onInstallationIdChange.listen(
          (_) => unawaited(_enqueueSynchronization()),
        ),
      )
      ..add(_service.onForegroundMessage.listen(_recordForegroundMessage))
      ..add(_service.onMessageOpenedApp.listen(_recordOpenedMessage));
  }

  Future<void> _enqueueSynchronization({String? token}) {
    final next = _synchronizationQueue
        .catchError((Object _) {})
        .then((_) => _synchronizeRegistration(token: token));
    _synchronizationQueue = next;
    return next;
  }

  Future<void> _synchronizeRegistration({String? token}) async {
    if (!_active) {
      return;
    }
    state = state.copyWith(status: PushNotificationStatus.synchronizing);

    try {
      final registration = await _service.getRegistration(token: token);
      if (registration == null) {
        state = state.copyWith(status: PushNotificationStatus.failure);
        return;
      }

      final repository = ref.read(pushTokenRepositoryProvider);
      await repository.register(registration);

      final previousInstallationId = _registeredInstallationId;
      _registeredInstallationId = registration.installationId;
      if (previousInstallationId != null &&
          previousInstallationId != registration.installationId) {
        await repository.unregister(previousInstallationId);
      }
      state = state.copyWith(status: PushNotificationStatus.registered);
    } on Object {
      state = state.copyWith(status: PushNotificationStatus.failure);
    }
  }

  Future<void> deactivate({required bool unregister}) async {
    _active = false;
    final subscriptions = List.of(_subscriptions);
    _subscriptions.clear();
    for (final subscription in subscriptions) {
      await subscription.cancel();
    }

    await _synchronizationQueue.catchError((Object _) {});

    if (unregister) {
      try {
        final installationId =
            _registeredInstallationId ??
            (await _service.getRegistration())?.installationId;
        if (installationId != null) {
          await ref
              .read(pushTokenRepositoryProvider)
              .unregister(installationId);
        }
      } on Object {
        // An explicit logout must still clear the local authenticated session.
      }
    }

    _registeredInstallationId = null;
    state = const PushNotificationState();
  }

  void _recordForegroundMessage(PushNotificationMessage message) {
    state = state.copyWith(
      lastForegroundMessage: message,
      eventSequence: state.eventSequence + 1,
    );
  }

  void _recordOpenedMessage(PushNotificationMessage message) {
    state = state.copyWith(
      lastOpenedMessage: message,
      eventSequence: state.eventSequence + 1,
    );
  }
}
