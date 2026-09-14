import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/sensor/domain/sensor_models.dart';
import 'package:potner_app/features/sensor/domain/sensor_repository.dart';

class SensorApi {
  SensorApi(this._dio);

  final Dio _dio;

  Future<List<CurrentSensor>> getCurrentSensors(String plantId) async {
    try {
      final response = await _dio.get<Object?>('plants/$plantId/sensors/current');
      final data = _asMap(response.data);
      return _asList(data['sensors'])
          .map((item) {
            final sensor = _asMap(item);
            return CurrentSensor(
              kind: _kind(sensor['sensorType']),
              level: _level(sensor['status']),
              value: _optionalDouble(sensor['value']),
              measuredAt: _optionalDateTime(sensor['measuredAt']),
            );
          })
          .toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<SensorHistorySeries> getHistory({
    required String plantId,
    required SensorKind kind,
    required DateTime from,
    required DateTime to,
    required SensorHistoryInterval interval,
  }) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/sensors/history',
        queryParameters: {
          'sensorType': kind.wireName,
          // 서버는 오프셋 없는 시각을 400 으로 거부한다. UTC(Z) 형식으로 보낸다.
          'from': from.toUtc().toIso8601String(),
          'to': to.toUtc().toIso8601String(),
          'interval': interval == SensorHistoryInterval.hour ? 'HOUR' : 'DAY',
        },
      );
      final data = _asMap(response.data);
      return SensorHistorySeries(
        kind: kind,
        points: _asList(data['points'])
            .map((item) {
              final point = _asMap(item);
              return SensorHistoryPoint(
                bucketAt: _requiredDateTime(point['bucketAt']),
                average: _optionalDouble(point['averageValue']),
                minimum: _optionalDouble(point['minimumValue']),
                maximum: _optionalDouble(point['maximumValue']),
              );
            })
            .toList(growable: false),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<DailyLightReport> getDailyLight(String plantId, {int days = 7}) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/daily-light',
        queryParameters: {'days': days},
      );
      final data = _asMap(response.data);
      final today = _nullableMap(data['today']);
      return DailyLightReport(
        today: today == null
            ? null
            : DailyLightToday(
                progressPct: _optionalDouble(today['progressPct']),
                lightHours: _optionalDouble(today['lightHours']),
                accumulatedLuxHour: _optionalDouble(
                  today['accumulatedLuxHour'],
                ),
                targetLuxHour: _optionalDouble(today['targetLuxHour']),
                coveragePct: _optionalDouble(today['coveragePct']),
                sampleCount: _optionalDouble(today['sampleCount'])?.round(),
              ),
        history: _asList(data['history'])
            .map((item) {
              final day = _asMap(item);
              return DailyLightDay(
                lightDate: _requiredDate(day['lightDate']),
                lightStatus: _lightStatus(day['lightStatus']),
                photoperiodStatus: _lightStatus(day['photoperiodStatus']),
                coveragePct: _optionalDouble(day['coveragePct']),
                lightHours: _optionalDouble(day['lightHours']),
              );
            })
            .toList(growable: false),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  SensorKind _kind(Object? value) {
    return switch (value) {
      'SOIL_MOISTURE' => SensorKind.soilMoisture,
      'ILLUMINANCE' => SensorKind.illuminance,
      'TEMPERATURE' => SensorKind.temperature,
      'HUMIDITY' => SensorKind.humidity,
      _ => throw FormatException('Unknown sensor type: $value'),
    };
  }

  SensorLevel _level(Object? value) {
    return switch (value) {
      'LOW' => SensorLevel.low,
      'NORMAL' => SensorLevel.normal,
      'HIGH' => SensorLevel.high,
      'NOT_APPLICABLE' => SensorLevel.notApplicable,
      'NO_DATA' => SensorLevel.noData,
      'STALE' => SensorLevel.stale,
      _ => SensorLevel.unknown,
    };
  }

  DailyLightStatus _lightStatus(Object? value) {
    return switch (value) {
      'LOW' => DailyLightStatus.low,
      'NORMAL' => DailyLightStatus.normal,
      'HIGH' => DailyLightStatus.high,
      'INSUFFICIENT_DATA' => DailyLightStatus.insufficientData,
      _ => DailyLightStatus.notApplicable,
    };
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

  Map<Object?, Object?>? _nullableMap(Object? value) {
    if (value == null) {
      return null;
    }
    return _asMap(value);
  }

  List<Object?> _asList(Object? value) {
    if (value is List) {
      return List<Object?>.from(value);
    }
    throw const FormatException('Expected a JSON list.');
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
    final parsed = double.tryParse(value.toString());
    if (parsed == null) {
      throw const FormatException('Expected a number.');
    }
    return parsed;
  }

  DateTime _requiredDate(Object? value) {
    final text = _optionalString(value);
    final parsed = text == null ? null : DateTime.tryParse(text);
    if (parsed == null) {
      throw const FormatException('Expected an ISO date.');
    }
    return parsed;
  }

  DateTime _requiredDateTime(Object? value) {
    final parsed = _optionalDateTime(value);
    if (parsed == null) {
      throw const FormatException('Expected an ISO date time.');
    }
    return parsed;
  }

  /// 서버 시각은 UTC 라 오프셋이 없어도 UTC 로 해석한다.
  DateTime? _optionalDateTime(Object? value) {
    final text = _optionalString(value);
    if (text == null) {
      return null;
    }
    final parsed = DateTime.tryParse(text);
    if (parsed == null) {
      throw const FormatException('Expected an ISO date time.');
    }
    if (parsed.isUtc) {
      return parsed;
    }
    return DateTime.utc(
      parsed.year,
      parsed.month,
      parsed.day,
      parsed.hour,
      parsed.minute,
      parsed.second,
      parsed.millisecond,
      parsed.microsecond,
    );
  }
}
