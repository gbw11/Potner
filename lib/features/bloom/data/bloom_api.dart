import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/bloom/domain/bloom_models.dart';

class BloomApi {
  BloomApi(this._dio);

  final Dio _dio;

  Future<List<BloomRecord>> getBlooms() async {
    try {
      // size 상한(100)까지 한 번에 받는다. 개화는 드문 이벤트라 한 페이지로 충분하다.
      final response = await _dio.get<Object?>(
        'blooms',
        queryParameters: {'page': 0, 'size': 100},
      );
      final data = _asMap(response.data);
      return _asList(data['blooms']).map(_parseBloom).toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<BloomRecord> recordBloom({
    required String plantId,
    DateTime? bloomDate,
    String? note,
  }) async {
    try {
      final response = await _dio.post<Object?>(
        'plants/$plantId/blooms',
        data: {
          if (bloomDate != null) 'bloomDate': _dateParameter(bloomDate),
          'note': ?note,
        },
      );
      return _parseBloom(response.data);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<void> deleteBloom({
    required String plantId,
    required String bloomId,
  }) async {
    try {
      await _dio.delete<Object?>('plants/$plantId/blooms/$bloomId');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<void> markRead(String bloomId) async {
    try {
      await _dio.patch<Object?>('blooms/$bloomId/read');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  BloomRecord _parseBloom(Object? value) {
    final bloom = _asMap(value);
    return BloomRecord(
      bloomId: _requiredString(bloom['bloomId']),
      plantId: _requiredString(bloom['plantId']),
      plantName: _requiredString(bloom['plantName']),
      bloomDate: _requiredDate(bloom['bloomDate']),
      isUserRecorded: bloom['source'] == 'USER',
      read: bloom['read'] == true,
      note: _optionalString(bloom['note']),
    );
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
