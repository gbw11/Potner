import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/auth/domain/user.dart';
import 'package:potner_app/features/user/domain/user_repository.dart';

class UserApi {
  UserApi(this._dio);

  final Dio _dio;

  Future<User> changeNickname(String nickname) async {
    try {
      final response = await _dio.patch<Object?>(
        'users/me',
        data: {'nickname': nickname},
      );
      final data = response.data;
      if (data is! Map) {
        throw const FormatException('Expected a JSON object.');
      }
      return User.fromJson(Map<Object?, Object?>.from(data));
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    try {
      await _dio.patch<Object?>(
        'users/me/password',
        data: {
          'currentPassword': currentPassword,
          'newPassword': newPassword,
        },
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<void> withdraw() async {
    try {
      await _dio.delete<Object?>('users/me');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<NotificationSettings> getNotificationSettings() async {
    try {
      final response = await _dio.get<Object?>(
        'users/me/notification-settings',
      );
      return _parseSettings(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<NotificationSettings> updateNotificationSettings({
    bool? allEnabled,
    bool? pushEnabled,
    bool? plantCareEnabled,
    bool? marketingEnabled,
  }) async {
    try {
      final response = await _dio.patch<Object?>(
        'users/me/notification-settings',
        data: {
          'allEnabled': ?allEnabled,
          'pushEnabled': ?pushEnabled,
          'plantCareEnabled': ?plantCareEnabled,
          'marketingEnabled': ?marketingEnabled,
        },
      );
      return _parseSettings(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  NotificationSettings _parseSettings(Object? value) {
    if (value is! Map) {
      throw const FormatException('Expected a JSON object.');
    }
    final data = Map<Object?, Object?>.from(value);
    return NotificationSettings(
      allEnabled: data['allEnabled'] == true,
      pushEnabled: data['pushEnabled'] == true,
      plantCareEnabled: data['plantCareEnabled'] == true,
      marketingEnabled: data['marketingEnabled'] == true,
    );
  }
}
