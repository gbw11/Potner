import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/device/domain/device_models.dart';

class DeviceApi {
  DeviceApi(this._dio);

  final Dio _dio;

  Future<RobotRegistration> registerRobot({
    required String deviceUid,
    required String name,
  }) async {
    try {
      final response = await _dio.post<Object?>(
        'robots',
        data: {'deviceUid': deviceUid, 'name': name},
      );
      final data = _asMap(response.data);
      final robot = _asMap(data['robot']);
      return RobotRegistration(
        robotId: _requiredString(robot['robotId']),
        deviceUid: _requiredString(robot['deviceUid']),
        name: _requiredString(robot['name']),
        uploadToken: _requiredString(data['uploadToken']),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<void> registerIotDevice({
    required String robotId,
    required String deviceUid,
    required IotDeviceType deviceType,
  }) async {
    try {
      await _dio.post<Object?>(
        'robots/$robotId/devices',
        data: {'deviceUid': deviceUid, 'deviceType': deviceType.wireName},
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<void> assignRobotToPlant({
    required String plantId,
    required String robotId,
  }) async {
    try {
      await _dio.post<Object?>(
        'plants/$plantId/assignment',
        data: {'robotId': robotId},
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Map<Object?, Object?> _asMap(Object? value) {
    if (value is Map<Object?, Object?>) {
      return value;
    }
    if (value is Map) {
      return Map<Object?, Object?>.from(value);
    }
    throw const FormatException('Expected a JSON object.');
  }

  String _requiredString(Object? value) {
    if (value is String && value.trim().isNotEmpty) {
      return value;
    }
    throw const FormatException('Expected a non-empty string.');
  }
}
