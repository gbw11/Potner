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

  Future<void> deleteRobot(String robotId) async {
    try {
      await _dio.delete<Object?>('robots/$robotId');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
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
      final response = await _dio.post<Object?>('robots/$robotId/upload-token');
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

  Future<List<RobotLocationInfo>> getRobotLocations(String robotId) async {
    try {
      final response = await _dio.get<Object?>('robots/$robotId/locations');
      final data = _asMap(response.data);
      return _asList(
        data['locations'],
      ).map(_parseLocation).toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<RobotLocationInfo> registerRobotLocation({
    required String robotId,
    required RobotLocationType type,
    String? stationCode,
  }) async {
    try {
      final response = await _dio.post<Object?>(
        'robots/$robotId/locations',
        data: {'type': type.wireName, 'stationCode': ?stationCode},
      );
      return _parseLocation(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<RobotLocationInfo> updateLocationPose({
    required String robotId,
    required RobotLocationType type,
    required double x,
    required double y,
    required double yaw,
  }) async {
    try {
      final response = await _dio.put<Object?>(
        'robots/$robotId/locations/${type.wireName}/pose',
        data: {'x': x, 'y': y, 'yaw': yaw},
      );
      return _parseLocation(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  RobotLocationInfo _parseLocation(Object? value) {
    final location = _asMap(value);
    return RobotLocationInfo(
      locationId: _requiredString(location['locationId']),
      type: _locationType(location['type']),
      stationCode: _optionalString(location['stationCode']),
      poseX: _optionalDouble(location['poseX']),
      poseY: _optionalDouble(location['poseY']),
      poseYaw: _optionalDouble(location['poseYaw']),
      poseConfigured: location['poseConfigured'] == true,
      waterLow: location['waterLow'] == true,
      waterLowAt: _optionalDateTime(location['waterLowAt']),
    );
  }

  RobotLocationType _locationType(Object? value) {
    return switch (value) {
      'WATER_STATION' => RobotLocationType.waterStation,
      'HOME' => RobotLocationType.home,
      'SUNLIGHT' => RobotLocationType.sunlight,
      'GREETING' => RobotLocationType.greeting,
      _ => throw FormatException('Unknown location type: $value'),
    };
  }

  double? _optionalDouble(Object? value) {
    if (value == null) {
      return null;
    }
    if (value is num) {
      return value.toDouble();
    }
    final parsed = double.tryParse(value.toString());
    if (parsed == null) {
      throw const FormatException('Expected a number.');
    }
    return parsed;
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
