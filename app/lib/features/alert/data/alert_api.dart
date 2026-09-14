import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/alert/domain/alert_models.dart';

class AlertApi {
  AlertApi(this._dio);

  final Dio _dio;

  Future<List<PlantAlert>> getAlerts() async {
    try {
      final response = await _dio.get<Object?>(
        'alerts',
        queryParameters: {'page': 0, 'size': 100},
      );
      final data = _asMap(response.data);
      return _asList(data['alerts']).map(_parseAlert).toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<void> markRead(String alertId) async {
    try {
      await _dio.patch<Object?>('alerts/$alertId/read');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  /// 목록에서 치운다. 서버는 행을 지우지 않고 목록 조회에서만 뺀다.
  ///
  /// 읽음 처리를 따로 부를 필요가 없다 — 서버가 함께 남긴다.
  Future<void> dismiss(String alertId) async {
    try {
      await _dio.patch<Object?>('alerts/$alertId/dismiss');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  /// 치운 것을 되돌린다. 읽음은 되돌아오지 않는다.
  Future<void> restore(String alertId) async {
    try {
      await _dio.delete<Object?>('alerts/$alertId/dismiss');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  PlantAlert _parseAlert(Object? value) {
    final alert = _asMap(value);
    return PlantAlert(
      alertId: _requiredString(alert['alertId']),
      plantId: _requiredString(alert['plantId']),
      plantName: _requiredString(alert['plantName']),
      metric: _metric(alert['metricType']),
      deviation: alert['deviation'] == 'HIGH'
          ? AlertDeviation.high
          : AlertDeviation.low,
      measuredValue: _optionalDouble(alert['measuredValue']),
      occurredAt: _requiredDateTime(alert['occurredAt']),
      resolvedAt: _optionalDateTime(alert['resolvedAt']),
      active: alert['active'] == true,
      read: alert['read'] == true,
    );
  }

  AlertMetric _metric(Object? value) {
    return switch (value) {
      'TEMPERATURE' => AlertMetric.temperature,
      'HUMIDITY' => AlertMetric.humidity,
      'SOIL_MOISTURE' => AlertMetric.soilMoisture,
      'DAILY_LIGHT' => AlertMetric.dailyLight,
      'PHOTOPERIOD' => AlertMetric.photoperiod,
      'STATION_WATER_LOW' => AlertMetric.stationWaterLow,
      'DRAINAGE_TRAY' => AlertMetric.drainageTray,
      _ => AlertMetric.unknown,
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
    final parsed = double.tryParse(value.toString());
    if (parsed == null) {
      throw const FormatException('Expected a number.');
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
