import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/core/api/auth_interceptor.dart';
import 'package:potner_app/core/api/session_invalidation_notifier.dart';
import 'package:potner_app/core/storage/token_storage.dart';

void main() {
  test(
    'concurrent expired requests share one refresh and retry once',
    () async {
      final storage = _MemoryTokenStorage(
        const StoredTokens(accessToken: 'expired', refreshToken: 'refresh-old'),
      );
      final notifier = SessionInvalidationNotifier();
      addTearDown(notifier.dispose);
      var protectedCalls = 0;
      var refreshCalls = 0;

      final refreshClient =
          Dio(BaseOptions(baseUrl: 'https://potner.test/api/v1/'))
            ..httpClientAdapter = _FakeAdapter((options) async {
              refreshCalls++;
              expect(options.path, 'auth/reissue');
              expect(options.headers['Authorization'], isNull);
              await Future<void>.delayed(const Duration(milliseconds: 20));
              return _jsonResponse(
                200,
                '{"accessToken":"access-new","refreshToken":"refresh-new"}',
              );
            });
      final client = Dio(BaseOptions(baseUrl: 'https://potner.test/api/v1/'))
        ..httpClientAdapter = _FakeAdapter((options) async {
          protectedCalls++;
          final authorization = options.headers['Authorization'];
          if (authorization == 'Bearer expired') {
            return _jsonResponse(
              401,
              '{"status":401,"code":"EXPIRED_ACCESS_TOKEN"}',
            );
          }
          expect(authorization, 'Bearer access-new');
          return _jsonResponse(200, '{"ok":true}');
        });
      final interceptor = AuthInterceptor(storage, refreshClient, notifier);
      interceptor.attach(client);
      client.interceptors.add(interceptor);

      final responses = await Future.wait([
        client.get<Object?>('plants/one'),
        client.get<Object?>('plants/two'),
      ]);

      expect(
        responses.map((response) => response.statusCode),
        everyElement(200),
      );
      expect(refreshCalls, 1);
      expect(protectedCalls, 4);
      expect((await storage.readTokens())?.accessToken, 'access-new');
      expect((await storage.readTokens())?.refreshToken, 'refresh-new');

      client.close(force: true);
      refreshClient.close(force: true);
    },
  );

  test('refresh failure clears the session and emits invalidation', () async {
    final storage = _MemoryTokenStorage(
      const StoredTokens(accessToken: 'expired', refreshToken: 'refresh-old'),
    );
    final notifier = SessionInvalidationNotifier();
    addTearDown(notifier.dispose);
    final invalidated = notifier.events.first;

    final refreshClient =
        Dio(BaseOptions(baseUrl: 'https://potner.test/api/v1/'))
          ..httpClientAdapter = _FakeAdapter(
            (_) async => _jsonResponse(
              401,
              '{"status":401,"code":"EXPIRED_REFRESH_TOKEN"}',
            ),
          );
    final client = Dio(BaseOptions(baseUrl: 'https://potner.test/api/v1/'))
      ..httpClientAdapter = _FakeAdapter(
        (_) async =>
            _jsonResponse(401, '{"status":401,"code":"EXPIRED_ACCESS_TOKEN"}'),
      );
    final interceptor = AuthInterceptor(storage, refreshClient, notifier);
    interceptor.attach(client);
    client.interceptors.add(interceptor);

    await expectLater(
      client.get<Object?>('users/me'),
      throwsA(isA<DioException>()),
    );
    await invalidated;

    expect(await storage.readTokens(), isNull);
    expect(storage.clearCount, 1);

    client.close(force: true);
    refreshClient.close(force: true);
  });
}

ResponseBody _jsonResponse(int statusCode, String body) {
  return ResponseBody.fromString(
    body,
    statusCode,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );
}

class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.handler);

  final Future<ResponseBody> Function(RequestOptions options) handler;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    return handler(options);
  }

  @override
  void close({bool force = false}) {}
}

class _MemoryTokenStorage implements TokenStorage {
  _MemoryTokenStorage(this.tokens);

  StoredTokens? tokens;
  int clearCount = 0;

  @override
  Future<void> clearTokens() async {
    clearCount++;
    tokens = null;
  }

  @override
  Future<StoredTokens?> readTokens() async => tokens;

  @override
  Future<void> writeTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    tokens = StoredTokens(accessToken: accessToken, refreshToken: refreshToken);
  }
}
