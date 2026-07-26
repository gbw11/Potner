import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/core/notifications/push_notification_service.dart';

final pushTokenRepositoryProvider = Provider<PushTokenRepository>((ref) {
  return ApiPushTokenRepository(ref.watch(apiClientProvider).dio);
});

abstract interface class PushTokenRepository {
  Future<void> register(PushDeviceRegistration registration);

  Future<void> unregister(String installationId);
}

class ApiPushTokenRepository implements PushTokenRepository {
  ApiPushTokenRepository(this._dio);

  static const _path = 'users/me/fcm-tokens';

  final Dio _dio;

  @override
  Future<void> register(PushDeviceRegistration registration) async {
    final installationId = Uri.encodeComponent(registration.installationId);
    await _dio.put<void>(
      '$_path/$installationId',
      data: {
        'token': registration.token,
        'platform': registration.platform.apiValue,
      },
    );
  }

  @override
  Future<void> unregister(String installationId) async {
    await _dio.delete<void>('$_path/${Uri.encodeComponent(installationId)}');
  }
}
