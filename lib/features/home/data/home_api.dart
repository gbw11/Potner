import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/core/api/media_url.dart';
import 'package:potner_app/features/home/domain/home_dashboard.dart';

class HomeApi {
  HomeApi(this._dio);

  final Dio _dio;

  Future<List<HomePlant>> getPlants() async {
    try {
      final response = await _dio.get<Object?>('plants');
      final data = _asMap(response.data);
      final plants = _asList(data['plants']);
      return plants
          .map((item) {
            final plant = _asMap(item);
            final photo = _nullableMap(plant['representativePhoto']);
            return HomePlant(
              id: _requiredString(plant['plantId']),
              name: _requiredString(plant['name']),
              createdAt: _requiredDate(plant['createdAt']),
              adoptedDate: _optionalDate(plant['adoptedDate']),
              thumbnailUrl: resolveMediaUrl(
                _optionalString(photo?['thumbnailUrl']),
              ),
              imageUrl: resolveMediaUrl(
                _optionalString(photo?['originalUrl']) ??
                    _optionalString(photo?['thumbnailUrl']),
              ),
            );
          })
          .toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<HomeMood> getMood(String plantId) async {
    try {
      final response = await _dio.get<Object?>('plants/$plantId/happiness');
      final data = _asMap(response.data);
      return HomeMood(
        grade: _requiredString(data['grade']),
        headline: _requiredString(data['headline']),
        detail: _requiredString(data['detail']),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<List<HomeSensorReading>> getCurrentSensors(String plantId) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/sensors/current',
      );
      final data = _asMap(response.data);
      return _asList(data['sensors'])
          .map((item) {
            final sensor = _asMap(item);
            return HomeSensorReading(
              type: _sensorType(_requiredString(sensor['sensorType'])),
              unit: _requiredString(sensor['unit']),
              value: _optionalNumber(sensor['value']),
              measuredAt: _optionalUtcDateTime(sensor['measuredAt']),
              status: _sensorStatus(_requiredString(sensor['status'])),
            );
          })
          .toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<String?> getLatestPhoto(HomePlant plant) async {
    try {
      final now = DateTime.now();
      final firstPhotoDate = plant.adoptedDate ?? plant.createdAt;
      final response = await _dio.get<Object?>(
        'plants/${plant.id}/photos',
        queryParameters: {
          'from': _dateParameter(firstPhotoDate),
          'to': _dateParameter(now),
        },
      );
      final data = _asMap(response.data);
      final photos = _asList(data['photos']);
      if (photos.isEmpty) {
        return null;
      }
      final latest = _asMap(photos.last);
      return resolveMediaUrl(
        _optionalString(latest['thumbnailUrl']) ??
            _optionalString(latest['originalUrl']),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<int> getUnreadAlertCount() async {
    try {
      final response = await _dio.get<Object?>(
        'alerts',
        queryParameters: {'unreadOnly': true, 'page': 0, 'size': 1},
      );
      final data = _asMap(response.data);
      final value = data['unreadCount'];
      if (value is int) {
        return value;
      }
      final parsed = int.tryParse(value?.toString() ?? '');
      if (parsed == null) {
        throw const FormatException('Expected an unread alert count.');
      }
      return parsed;
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
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

  num? _optionalNumber(Object? value) {
    if (value is num) {
      return value;
    }
    return num.tryParse(value?.toString() ?? '');
  }

  DateTime _requiredDate(Object? value) {
    final parsed = DateTime.tryParse(_requiredString(value));
    if (parsed == null) {
      throw const FormatException('Expected an ISO date.');
    }
    return parsed;
  }

  DateTime? _optionalDate(Object? value) {
    final text = _optionalString(value);
    if (text == null) {
      return null;
    }
    final parsed = DateTime.tryParse(text);
    if (parsed == null) {
      throw const FormatException('Expected an ISO date.');
    }
    return parsed;
  }

  DateTime? _optionalUtcDateTime(Object? value) {
    final text = _optionalString(value);
    if (text == null) {
      return null;
    }
    final parsed = DateTime.tryParse(text);
    if (parsed == null) {
      throw const FormatException('Expected an ISO date time.');
    }
    final hasOffset =
        text.endsWith('Z') || RegExp(r'[+-]\d{2}:\d{2}$').hasMatch(text);
    if (hasOffset) {
      return parsed.toUtc();
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

  String _dateParameter(DateTime value) {
    final month = value.month.toString().padLeft(2, '0');
    final day = value.day.toString().padLeft(2, '0');
    return '${value.year}-$month-$day';
  }

  HomeSensorType _sensorType(String value) {
    return switch (value) {
      'SOIL_MOISTURE' => HomeSensorType.soilMoisture,
      'ILLUMINANCE' => HomeSensorType.illuminance,
      'TEMPERATURE' => HomeSensorType.temperature,
      'HUMIDITY' => HomeSensorType.humidity,
      _ => throw FormatException('Unknown sensor type: $value'),
    };
  }

  HomeSensorStatus _sensorStatus(String value) {
    return switch (value) {
      'LOW' => HomeSensorStatus.low,
      'NORMAL' => HomeSensorStatus.normal,
      'HIGH' => HomeSensorStatus.high,
      'NOT_APPLICABLE' => HomeSensorStatus.notApplicable,
      'NO_DATA' => HomeSensorStatus.noData,
      'STALE' => HomeSensorStatus.stale,
      _ => throw FormatException('Unknown sensor status: $value'),
    };
  }
}
