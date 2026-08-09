import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/device/domain/device_models.dart';

class DeviceCommandApi {
  DeviceCommandApi(this._dio);

  final Dio _dio;

  Future<DeviceCommandRecord> issue({
    required String plantId,
    required DeviceCommandType type,
    RobotLocationType? destination,
    int? seconds,
  }) async {
    try {
      final response = await _dio.post<Object?>(
        'plants/$plantId/device-commands',
        // 종류에 맞지 않는 필드를 보내면 서버가 400 이다. 비어 있으면 키를 빼야 한다.
        data: {
          'type': type.wireName,
          'destination': ?destination?.wireName,
          'seconds': ?seconds,
        },
      );
      return _parseCommand(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<DeviceCommandRecord> startCareRun({
    required String plantId,
    required CareRunPurpose purpose,
  }) async {
    try {
      // 명령 종류를 보내지 않는다. purpose 만 정하면 첫 단계와 이후 순서는 서버가 정한다.
      final response = await _dio.post<Object?>(
        'plants/$plantId/care-runs',
        data: {'purpose': purpose.wireName},
      );
      return _parseCommand(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<List<DeviceCommandRecord>> getCommands({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/device-commands',
        queryParameters: {
          'from': _dateParameter(from),
          'to': _dateParameter(to),
        },
      );
      final data = _asMap(response.data);
      return _asList(data['commands'])
          .map(_parseCommand)
          .toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<void> publishExpression({
    required String plantId,
    required PlantExpression expression,
  }) async {
    try {
      await _dio.post<Object?>(
        'plants/$plantId/expression',
        data: {'expression': expression.wireName},
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<void> drive({
    required String plantId,
    required DriveDirection direction,
  }) async {
    try {
      // 속도와 이동 시간은 보내지 않는다. 서버 설정(potner.drive.*)이 유일한 출처다.
      await _dio.post<Object?>(
        'plants/$plantId/drive',
        data: {'direction': direction.wireName},
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  DeviceCommandRecord _parseCommand(Object? value) {
    final command = _asMap(value);
    return DeviceCommandRecord(
      requestId: _requiredString(command['requestId']),
      type: _commandType(command['type']),
      status: _commandStatus(command['status']),
      initiator: _initiator(command['initiator']),
      destination: _locationType(command['destination']),
      requestedMl: _optionalDouble(command['requestedMl']),
      dispensedMl: _optionalDouble(command['dispensedMl']),
      runSeconds: _optionalInt(command['runSeconds']),
      errorMessage: _optionalString(command['errorMessage']),
      issuedAt: _optionalDateTime(command['issuedAt']),
      reportedAt: _optionalDateTime(command['reportedAt']),
    );
  }

  DeviceCommandType? _commandType(Object? value) {
    return switch (value) {
      'WATER' => DeviceCommandType.water,
      'CAPTURE' => DeviceCommandType.capture,
      'FAN' => DeviceCommandType.fan,
      'NAVIGATE' => DeviceCommandType.navigate,
      'MAPPING_START' => DeviceCommandType.mappingStart,
      'MAPPING_SAVE' => DeviceCommandType.mappingSave,
      'MAPPING_CANCEL' => DeviceCommandType.mappingCancel,
      _ => null,
    };
  }

  /// 모르는 값을 `unknown` 으로 떨어뜨린다. 서버가 상태를 추가해도 앱이 죽지 않아야 하고,
  /// `unknown` 은 종결이 아니라서 폴링이 조용히 성공으로 끝나지도 않는다.
  DeviceCommandStatus _commandStatus(Object? value) {
    return switch (value) {
      'ISSUED' => DeviceCommandStatus.issued,
      'OK' => DeviceCommandStatus.ok,
      'ERROR' => DeviceCommandStatus.error,
      'BUSY' => DeviceCommandStatus.busy,
      'SKIPPED' => DeviceCommandStatus.skipped,
      'TIMED_OUT' => DeviceCommandStatus.timedOut,
      _ => DeviceCommandStatus.unknown,
    };
  }

  CommandInitiator _initiator(Object? value) {
    return switch (value) {
      'USER' => CommandInitiator.user,
      'AUTO' => CommandInitiator.auto,
      _ => CommandInitiator.unknown,
    };
  }

  RobotLocationType? _locationType(Object? value) {
    return switch (value) {
      'WATER_STATION' => RobotLocationType.waterStation,
      'HOME' => RobotLocationType.home,
      'SUNLIGHT' => RobotLocationType.sunlight,
      'GREETING' => RobotLocationType.greeting,
      _ => null,
    };
  }

  String _dateParameter(DateTime value) {
    final month = value.month.toString().padLeft(2, '0');
    final day = value.day.toString().padLeft(2, '0');
    return '${value.year}-$month-$day';
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

  List<Object?> _asList(Object? value) {
    if (value is List) {
      return List<Object?>.from(value);
    }
    throw const FormatException('Expected a JSON list.');
  }

  String _requiredString(Object? value) {
    final parsed = _optionalString(value);
    if (parsed == null) {
      throw const FormatException('Expected a non-empty string.');
    }
    return parsed;
  }

  String? _optionalString(Object? value) {
    return value is String && value.trim().isNotEmpty ? value : null;
  }

  double? _optionalDouble(Object? value) {
    if (value == null) {
      return null;
    }
    if (value is num) {
      return value.toDouble();
    }
    return double.tryParse(value.toString());
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
    return int.tryParse(value.toString());
  }

  DateTime? _optionalDateTime(Object? value) {
    final text = _optionalString(value);
    if (text == null) {
      return null;
    }
    return DateTime.tryParse(text);
  }
}
