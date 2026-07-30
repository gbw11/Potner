import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
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
              thumbnailUrl: _optionalString(diary['thumbnailUrl']),
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
        photoUrl:
            _optionalString(photo?['originalUrl']) ??
            _optionalString(photo?['thumbnailUrl']),
      );
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
