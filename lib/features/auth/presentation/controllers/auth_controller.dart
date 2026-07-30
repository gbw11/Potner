import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/session_invalidation_notifier.dart';
import 'package:potner_app/core/notifications/push_notification_controller.dart';
import 'package:potner_app/features/auth/data/auth_repository_impl.dart';
import 'package:potner_app/features/auth/domain/auth_repository.dart';
import 'package:potner_app/features/auth/domain/auth_state.dart';
import 'package:potner_app/features/auth/domain/user.dart';

final authControllerProvider = NotifierProvider<AuthController, AuthState>(
  AuthController.new,
);

class AuthController extends Notifier<AuthState> {
  late final AuthRepository _repository;
  Future<void>? _restorationFuture;

  @override
  AuthState build() {
    _repository = ref.read(authRepositoryProvider);
    final subscription = ref
        .read(sessionInvalidationNotifierProvider)
        .events
        .listen((_) {
          unawaited(
            ref
                .read(pushNotificationControllerProvider.notifier)
                .deactivate(unregister: false),
          );
          state = const AuthState.unauthenticated();
        });
    ref.onDispose(subscription.cancel);
    Future.microtask(restoreSession);
    return const AuthState.initializing();
  }

  Future<void> restoreSession() {
    final pending = _restorationFuture;
    if (pending != null) {
      return pending;
    }

    late final Future<void> restoration;
    restoration = _restoreSession().whenComplete(() {
      if (identical(_restorationFuture, restoration)) {
        _restorationFuture = null;
      }
    });
    _restorationFuture = restoration;
    return restoration;
  }

  Future<void> _restoreSession() async {
    state = const AuthState.initializing();
    try {
      final user = await _repository.restoreSession();
      state = user == null
          ? const AuthState.unauthenticated()
          : AuthState(status: AuthStatus.authenticated, user: user);
      if (user != null) {
        unawaited(
          ref.read(pushNotificationControllerProvider.notifier).activate(),
        );
      }
    } catch (error) {
      final failure = AuthFailure.from(error);
      if (failure.isConnectionFailure) {
        state = AuthState(
          status: AuthStatus.failure,
          failure: failure,
          isRestoring: true,
        );
      } else {
        await _clearSessionBestEffort();
        state = const AuthState.unauthenticated();
      }
    }
  }

  Future<AuthCommandResult<User>> signup({
    required String email,
    required String password,
    required String nickname,
  }) async {
    if (state.status == AuthStatus.authenticating) {
      return AuthCommandResult.failure(
        const AuthFailure(message: '요청을 처리하고 있습니다.'),
      );
    }

    state = const AuthState(status: AuthStatus.authenticating);
    try {
      final user = await _repository.signup(
        email: email.trim().toLowerCase(),
        password: password,
        nickname: nickname.trim(),
      );
      state = const AuthState.unauthenticated();
      return AuthCommandResult.success(user);
    } catch (error) {
      final failure = AuthFailure.from(error);
      state = AuthState(status: AuthStatus.failure, failure: failure);
      return AuthCommandResult.failure(failure);
    }
  }

  Future<AuthCommandResult<User>> login({
    required String email,
    required String password,
  }) async {
    if (state.status == AuthStatus.authenticating) {
      return AuthCommandResult.failure(
        const AuthFailure(message: '요청을 처리하고 있습니다.'),
      );
    }

    state = const AuthState(status: AuthStatus.authenticating);
    try {
      final user = await _repository.login(
        email: email.trim().toLowerCase(),
        password: password,
      );
      state = AuthState(status: AuthStatus.authenticated, user: user);
      unawaited(
        ref.read(pushNotificationControllerProvider.notifier).activate(),
      );
      return AuthCommandResult.success(user);
    } catch (error) {
      final failure = AuthFailure.from(error);
      state = AuthState(status: AuthStatus.failure, failure: failure);
      return AuthCommandResult.failure(failure);
    }
  }

  Future<AuthCommandResult<void>> logout() async {
    if (state.status == AuthStatus.authenticating) {
      return AuthCommandResult.failure(
        const AuthFailure(message: '요청을 처리하고 있습니다.'),
      );
    }

    state = AuthState(status: AuthStatus.authenticating, user: state.user);
    AuthFailure? failure;
    try {
      await ref
          .read(pushNotificationControllerProvider.notifier)
          .deactivate(unregister: true);
      await _repository.logout();
    } catch (error) {
      failure = AuthFailure.from(error);
    } finally {
      state = const AuthState.unauthenticated();
    }

    return failure == null
        ? AuthCommandResult.success(null)
        : AuthCommandResult.failure(failure);
  }

  /// 서버에서 갱신된 사용자 정보를 상태에 반영한다. 닉네임 변경 등에 쓴다.
  void updateUser(User user) {
    if (state.status == AuthStatus.authenticated) {
      state = AuthState(status: AuthStatus.authenticated, user: user);
    }
  }

  Future<void> continueWithoutSession() async {
    await _clearSessionBestEffort();
    state = const AuthState.unauthenticated();
  }

  Future<void> _clearSessionBestEffort() async {
    try {
      await _repository.clearSession();
    } catch (_) {
      // A storage failure must not leave the app stuck on its startup screen.
    }
  }
}
