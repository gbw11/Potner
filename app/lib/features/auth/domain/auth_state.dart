import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/auth/domain/user.dart';

enum AuthStatus {
  initializing,
  unauthenticated,
  authenticating,
  authenticated,
  failure,
}

class AuthState {
  const AuthState({
    required this.status,
    this.user,
    this.failure,
    this.isRestoring = false,
  });

  const AuthState.initializing()
    : this(status: AuthStatus.initializing, isRestoring: true);

  const AuthState.unauthenticated() : this(status: AuthStatus.unauthenticated);

  final AuthStatus status;
  final User? user;
  final AuthFailure? failure;
  final bool isRestoring;

  bool get isBusy =>
      status == AuthStatus.initializing || status == AuthStatus.authenticating;

  bool get isAuthenticated =>
      status == AuthStatus.authenticated && user != null;
}

class AuthFailure {
  const AuthFailure({
    required this.message,
    this.code,
    this.fieldErrors = const {},
    this.isConnectionFailure = false,
  });

  final String message;
  final String? code;
  final Map<String, String> fieldErrors;
  final bool isConnectionFailure;

  static AuthFailure from(Object error) {
    if (error is! ApiException) {
      return const AuthFailure(message: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    }

    if (error.kind == ApiExceptionKind.connection ||
        error.kind == ApiExceptionKind.timeout) {
      return const AuthFailure(
        message: '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
        isConnectionFailure: true,
      );
    }

    if (error.kind == ApiExceptionKind.invalidResponse) {
      return const AuthFailure(message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.');
    }

    final problem = error.problem;
    final code = problem?.code;
    final fieldErrors = Map<String, String>.from(problem?.errors ?? const {});
    if (code == 'EMAIL_ALREADY_EXISTS') {
      fieldErrors.putIfAbsent('email', () => '이미 사용 중인 이메일입니다.');
    }

    final fallback = switch (code) {
      'LOGIN_FAILED' => '이메일 또는 비밀번호를 확인해 주세요.',
      'ACCOUNT_INACTIVE' => '현재 사용할 수 없는 계정입니다.',
      'INVALID_REQUEST' => '입력값을 확인해 주세요.',
      'INVALID_ACCESS_TOKEN' ||
      'INVALID_REFRESH_TOKEN' ||
      'REFRESH_TOKEN_NOT_FOUND' ||
      'REFRESH_TOKEN_MISMATCH' ||
      'REVOKED_REFRESH_TOKEN' ||
      'EXPIRED_REFRESH_TOKEN' => '로그인이 만료되었습니다. 다시 로그인해 주세요.',
      'INTERNAL_SERVER_ERROR' => '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
      _ => '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    };

    return AuthFailure(
      message: problem?.detail ?? fallback,
      code: code,
      fieldErrors: Map.unmodifiable(fieldErrors),
    );
  }
}

class AuthCommandResult<T> {
  const AuthCommandResult._({this.data, this.failure});

  factory AuthCommandResult.success(T data) {
    return AuthCommandResult._(data: data);
  }

  factory AuthCommandResult.failure(AuthFailure failure) {
    return AuthCommandResult._(failure: failure);
  }

  final T? data;
  final AuthFailure? failure;

  bool get isSuccess => failure == null;
}
