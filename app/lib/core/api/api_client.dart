import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/auth_interceptor.dart';
import 'package:potner_app/core/api/session_invalidation_notifier.dart';
import 'package:potner_app/core/config/app_config.dart';
import 'package:potner_app/core/storage/token_storage.dart';

final apiClientProvider = Provider<ApiClient>((ref) {
  final client = ApiClient(
    baseUrl: normalizedApiBaseUrl,
    tokenStorage: ref.watch(tokenStorageProvider),
    sessionInvalidationNotifier: ref.watch(sessionInvalidationNotifierProvider),
  );
  ref.onDispose(client.dispose);
  return client;
});

class ApiClient {
  ApiClient({
    required String baseUrl,
    required TokenStorage tokenStorage,
    required SessionInvalidationNotifier sessionInvalidationNotifier,
  }) {
    final options = BaseOptions(
      baseUrl: baseUrl,
      connectTimeout: const Duration(seconds: 10),
      sendTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 15),
      contentType: Headers.jsonContentType,
      responseType: ResponseType.json,
      headers: const {'Accept': Headers.jsonContentType},
    );

    final refreshClient = Dio(options);
    dio = Dio(options);
    final interceptor = AuthInterceptor(
      tokenStorage,
      refreshClient,
      sessionInvalidationNotifier,
    );
    interceptor.attach(dio);
    dio.interceptors.add(interceptor);
    _refreshClient = refreshClient;
  }

  late final Dio dio;
  late final Dio _refreshClient;

  void dispose() {
    dio.close(force: true);
    _refreshClient.close(force: true);
  }
}
