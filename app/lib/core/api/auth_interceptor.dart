import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_problem.dart';
import 'package:potner_app/core/api/session_invalidation_notifier.dart';
import 'package:potner_app/core/storage/token_storage.dart';

class AuthInterceptor extends Interceptor {
  AuthInterceptor(
    this._tokenStorage,
    this._refreshClient,
    this._sessionInvalidationNotifier,
  );

  static const _authorization = 'Authorization';
  static const _retriedKey = 'potner.auth.retried';
  static const _publicPaths = <String>{
    'auth/signup',
    'auth/login',
    'auth/reissue',
  };
  static const _terminalAuthCodes = <String>{
    'INVALID_ACCESS_TOKEN',
    'INVALID_REFRESH_TOKEN',
    'REFRESH_TOKEN_NOT_FOUND',
    'REFRESH_TOKEN_MISMATCH',
    'REVOKED_REFRESH_TOKEN',
    'EXPIRED_REFRESH_TOKEN',
  };

  final TokenStorage _tokenStorage;
  final Dio _refreshClient;
  final SessionInvalidationNotifier _sessionInvalidationNotifier;

  late Dio _client;
  Future<void>? _refreshFuture;
  Future<void>? _invalidationFuture;

  void attach(Dio client) {
    _client = client;
  }

  @override
  void onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    if (!_isPublic(options.path)) {
      final tokens = await _tokenStorage.readTokens();
      if (tokens != null) {
        options.headers[_authorization] = 'Bearer ${tokens.accessToken}';
      }
    }
    handler.next(options);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    final request = err.requestOptions;
    final problem = ApiProblem.tryParse(err.response?.data);
    final isUnauthorized = err.response?.statusCode == 401;

    if (!isUnauthorized || _isPublic(request.path)) {
      handler.next(err);
      return;
    }

    final wasRetried = request.extra[_retriedKey] == true;
    if (wasRetried) {
      await _invalidateSession();
      handler.next(err);
      return;
    }

    if (problem?.code == 'EXPIRED_ACCESS_TOKEN') {
      try {
        await _refreshAccessToken();
        final tokens = await _tokenStorage.readTokens();
        if (tokens == null) {
          throw StateError('Tokens were not stored after reissue.');
        }
        request.extra[_retriedKey] = true;
        request.headers[_authorization] = 'Bearer ${tokens.accessToken}';
        final response = await _client.fetch<Object?>(request);
        handler.resolve(response);
      } catch (exception) {
        await _invalidateSession();
        handler.reject(
          DioException(
            requestOptions: request,
            response: err.response,
            type: err.type,
            error: exception,
          ),
        );
      }
      return;
    }

    if (_terminalAuthCodes.contains(problem?.code)) {
      await _invalidateSession();
    }
    handler.next(err);
  }

  Future<void> _refreshAccessToken() {
    final pending = _refreshFuture;
    if (pending != null) {
      return pending;
    }

    late final Future<void> refresh;
    refresh = _performRefresh().whenComplete(() {
      if (identical(_refreshFuture, refresh)) {
        _refreshFuture = null;
      }
    });
    _refreshFuture = refresh;
    return refresh;
  }

  Future<void> _performRefresh() async {
    final tokens = await _tokenStorage.readTokens();
    if (tokens == null) {
      throw StateError('Refresh token is missing.');
    }

    final response = await _refreshClient.post<Object?>(
      'auth/reissue',
      data: {'refreshToken': tokens.refreshToken},
    );
    final data = response.data;
    if (data is! Map) {
      throw const FormatException('Invalid token response.');
    }

    final accessToken = data['accessToken'];
    final refreshToken = data['refreshToken'];
    if (accessToken is! String ||
        accessToken.isEmpty ||
        refreshToken is! String ||
        refreshToken.isEmpty) {
      throw const FormatException('Token response is missing values.');
    }

    await _tokenStorage.writeTokens(
      accessToken: accessToken,
      refreshToken: refreshToken,
    );
  }

  Future<void> _invalidateSession() {
    final pending = _invalidationFuture;
    if (pending != null) {
      return pending;
    }

    late final Future<void> invalidation;
    invalidation =
        () async {
          try {
            await _tokenStorage.clearTokens();
          } finally {
            _sessionInvalidationNotifier.notify();
          }
        }().whenComplete(() {
          if (identical(_invalidationFuture, invalidation)) {
            _invalidationFuture = null;
          }
        });
    _invalidationFuture = invalidation;
    return invalidation;
  }

  bool _isPublic(String path) {
    final normalized = path.startsWith('/') ? path.substring(1) : path;
    return _publicPaths.contains(normalized);
  }
}
