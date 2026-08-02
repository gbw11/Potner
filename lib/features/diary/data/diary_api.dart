import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/core/api/media_url.dart';
import 'package:potner_app/features/diary/domain/diary_models.dart';

class DiaryApi {
  DiaryApi(this._dio);

  final Dio _dio;

  Future<List<DiarySummary>> getDiaries({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/diaries',
        queryParameters: {
          'from': _dateParameter(from),
          'to': _dateParameter(to),
        },
      );
      final data = _asMap(response.data);
      return _asList(data['diaries'])
          .map((item) {
            final diary = _asMap(item);
            return DiarySummary(
              diaryId: _requiredString(diary['diaryId']),
              diaryDate: _requiredDate(diary['diaryDate']),
              title: _requiredString(diary['title']),
              thumbnailUrl: resolveMediaUrl(
                _optionalString(diary['thumbnailUrl']),
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

  Future<DiaryDetail> getDiary({
    required String plantId,
    required String diaryId,
  }) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/diaries/$diaryId',
      );
      final data = _asMap(response.data);
      final photo = _nullableMap(data['photo']);
      return DiaryDetail(
        diaryId: _requiredString(data['diaryId']),
        diaryDate: _requiredDate(data['diaryDate']),
        title: _requiredString(data['title']),
        content: _requiredString(data['content']),
        photoUrl: resolveMediaUrl(
          _optionalString(photo?['originalUrl']) ??
              _optionalString(photo?['thumbnailUrl']),
        ),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<DailyStatusReport> getStatusReport({
    required String plantId,
    required DateTime date,
  }) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/status-report',
        queryParameters: {'date': _dateParameter(date)},
      );
      final data = _asMap(response.data);
      return DailyStatusReport(
        date: _requiredDate(data['date']),
        happinessScore: _optionalInt(data['happinessScore']),
        lightHours: _optionalDouble(data['lightHours']),
        wateredMl: _optionalDouble(data['wateredMl']),
        adjustments: _asList(data['adjustments'])
            .map((item) {
              final adjustment = _asMap(item);
              return ScoreAdjustment(
                reason: _requiredString(adjustment['reason']),
                points: _requiredInt(adjustment['points']),
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

  int? _optionalInt(Object? value) {
    if (value == null) {
      return null;
    }
    return _requiredInt(value);
  }

  int _requiredInt(Object? value) {
    if (value is int) {
      return value;
    }
    if (value is num) {
      return value.round();
    }
    final parsed = int.tryParse(value?.toString() ?? '');
    if (parsed == null) {
      throw const FormatException('Expected an integer.');
    }
    return parsed;
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

  DateTime _requiredDate(Object? value) {
    final parsed = DateTime.tryParse(_requiredString(value));
    if (parsed == null) {
      throw const FormatException('Expected an ISO date.');
    }
    return parsed;
  }

  String _dateParameter(DateTime value) {
    final month = value.month.toString().padLeft(2, '0');
    final day = value.day.toString().padLeft(2, '0');
    return '${value.year}-$month-$day';
  }
}
