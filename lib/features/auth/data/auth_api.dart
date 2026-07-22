import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/auth/data/models/auth_tokens.dart';
import 'package:potner_app/features/auth/domain/user.dart';

final authApiProvider = Provider<AuthApi>((ref) {
  return AuthApi(ref.watch(apiClientProvider).dio);
});

class AuthApi {
  AuthApi(this._dio);

  final Dio _dio;

  Future<User> signup({
    required String email,
    required String password,
    required String nickname,
  }) async {
    try {
      final response = await _dio.post<Object?>(
        'auth/signup',
        data: {'email': email, 'password': password, 'nickname': nickname},
      );
      return User.fromJson(_asMap(response.data));
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<AuthTokens> login({
    required String email,
    required String password,
  }) async {
    try {
      final response = await _dio.post<Object?>(
        'auth/login',
        data: {'email': email, 'password': password},
      );
      return AuthTokens.fromJson(_asMap(response.data));
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<User> getCurrentUser() async {
    try {
      final response = await _dio.get<Object?>('users/me');
      return User.fromJson(_asMap(response.data));
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<AuthTokens> reissue(String refreshToken) async {
    try {
      final response = await _dio.post<Object?>(
        'auth/reissue',
        data: {'refreshToken': refreshToken},
      );
      return AuthTokens.fromJson(_asMap(response.data));
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<void> logout(String refreshToken) async {
    try {
      await _dio.post<void>(
        'auth/logout',
        data: {'refreshToken': refreshToken},
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Map<Object?, Object?> _asMap(Object? data) {
    if (data is Map<Object?, Object?>) {
      return data;
    }
    if (data is Map) {
      return Map<Object?, Object?>.from(data);
    }
    throw const FormatException('Expected a JSON object.');
  }
}
