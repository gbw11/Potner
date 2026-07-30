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

  Future<List<ManagedRobot>> getRobots() async {
    try {
      final response = await _dio.get<Object?>('robots');
      final data = _asMap(response.data);
      return _asList(data['robots'])
          .map((item) {
            final robot = _asMap(item);
            return ManagedRobot(
              robotId: _requiredString(robot['robotId']),
              deviceUid: _requiredString(robot['deviceUid']),
              name: _requiredString(robot['name']),
              connectionStatus: _connectionStatus(robot['connectionStatus']),
              lastSeenAt: _optionalDateTime(robot['lastSeenAt']),
              batteryPercent: _optionalInt(robot['batteryPercent']),
              firmwareVersion: _optionalString(robot['firmwareVersion']),
              assignedPlantId: _optionalString(robot['assignedPlantId']),
              assignedPlantName: _optionalString(robot['assignedPlantName']),
              devices: _asList(
                robot['devices'],
              ).map(_parseIotDevice).toList(growable: false),
            );
          })
          .toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<RobotLiveStatus?> getPlantRobotStatus(String plantId) async {
    try {
      final response = await _dio.get<Object?>('plants/$plantId/devices');
      final data = _asMap(response.data);
      final robot = data['robot'];
      if (robot == null) {
        return null;
      }
      final summary = _asMap(robot);
      return RobotLiveStatus(
        currentState: _activityState(summary['currentState']),
        stateChangedAt: _optionalDateTime(summary['stateChangedAt']),
        batteryPercent: _optionalInt(summary['batteryPercent']),
        batteryMeasuredAt: _optionalDateTime(summary['batteryMeasuredAt']),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<String> reissueUploadToken(String robotId) async {
    try {
      final response = await _dio.post<Object?>(
        'robots/$robotId/upload-token',
      );
      final data = _asMap(response.data);
      return _requiredString(data['uploadToken']);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<void> unassignPlant(String plantId) async {
    try {
      await _dio.delete<Object?>('plants/$plantId/assignment');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  ManagedIotDevice _parseIotDevice(Object? value) {
    final device = _asMap(value);
    return ManagedIotDevice(
      deviceUid: _requiredString(device['deviceUid']),
      deviceType: _requiredString(device['deviceType']),
      connectionStatus: _connectionStatus(device['connectionStatus']),
      lastSeenAt: _optionalDateTime(device['lastSeenAt']),
    );
  }

  DeviceConnectionStatus _connectionStatus(Object? value) {
    return switch (value) {
      'ONLINE' => DeviceConnectionStatus.online,
      'OFFLINE' => DeviceConnectionStatus.offline,
      'ERROR' => DeviceConnectionStatus.error,
      _ => DeviceConnectionStatus.unknown,
    };
  }

  RobotActivityState _activityState(Object? value) {
    return switch (value) {
      'IDLE' => RobotActivityState.idle,
      'NAVIGATING' => RobotActivityState.navigating,
      'DOCKING' => RobotActivityState.docking,
      'SERVICING' => RobotActivityState.servicing,
      'GREETING' => RobotActivityState.greeting,
      _ => RobotActivityState.unknown,
    };
  }

  List<Object?> _asList(Object? value) {
    if (value is List) {
      return List<Object?>.from(value);
    }
    throw const FormatException('Expected a JSON list.');
  }

  int? _optionalInt(Object? value) {
    if (value == null) {
      return null;
    }
    if (value is int) {
      return value;
    }
    if (value is num) {
      return value.round();
    }
    final parsed = int.tryParse(value.toString());
    if (parsed == null) {
      throw const FormatException('Expected an integer.');
    }
    return parsed;
  }

  DateTime? _optionalDateTime(Object? value) {
    final text = _optionalString(value);
    if (text == null) {
      return null;
    }
    final parsed = DateTime.tryParse(text);
    if (parsed == null) {
      throw const FormatException('Expected an ISO date time.');
    }
    return parsed;
  }

  String? _optionalString(Object? value) {
    return value is String && value.trim().isNotEmpty ? value : null;
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
