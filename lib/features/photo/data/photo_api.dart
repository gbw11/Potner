import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/core/api/media_url.dart';
import 'package:potner_app/features/photo/domain/photo_models.dart';

class PhotoApi {
  PhotoApi(this._dio);

  final Dio _dio;

  Future<void> uploadRepresentativePhoto({
    required String plantId,
    required String filePath,
    required String fileName,
  }) async {
    try {
      await _dio.post<Object?>(
        'plants/$plantId/representative-photo',
        data: FormData.fromMap({
          'file': await MultipartFile.fromFile(filePath, filename: fileName),
        }),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<List<PlantPhoto>> getPhotos({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) async {
    try {
      final response = await _dio.get<Object?>(
        'plants/$plantId/photos',
        queryParameters: {
          'from': _dateParameter(from),
          'to': _dateParameter(to),
        },
      );
      final data = _asMap(response.data);
      return _asList(data['photos'])
          .map((item) {
            final photo = _asMap(item);
            return PlantPhoto(
              photoId: _requiredString(photo['photoId']),
              photoDate: _requiredDate(photo['photoDate']),
              thumbnailUrl: requireMediaUrl(
                _requiredString(photo['thumbnailUrl']),
              ),
              originalUrl: requireMediaUrl(
                _requiredString(photo['originalUrl']),
              ),
              playbackUrl: resolveMediaUrl(
                _optionalString(photo['playbackUrl']),
              ),
              capturedAt: _optionalDateTime(photo['capturedAt']),
            );
          })
          .toList(growable: false);
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<void> selectRepresentativePhoto({
    required String plantId,
    required String photoId,
  }) async {
    try {
      await _dio.patch<Object?>(
        'plants/$plantId/representative-photo',
        data: {'photoId': photoId},
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  /// 대표 사진을 내린다. 사용자가 올린 사진이었으면 서버가 파일까지 지우고,
  /// 장치가 찍은 사진이었으면 대표에서만 내려와 포토 로그에는 그대로 남는다.
  Future<void> clearRepresentativePhoto({required String plantId}) async {
    try {
      await _dio.delete<Object?>('plants/$plantId/representative-photo');
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    }
  }

  Future<void> deletePhoto({
    required String plantId,
    required String photoId,
  }) async {
    try {
      await _dio.delete<Object?>('plants/$plantId/photos/$photoId');
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

  String _dateParameter(DateTime value) {
    final month = value.month.toString().padLeft(2, '0');
    final day = value.day.toString().padLeft(2, '0');
    return '${value.year}-$month-$day';
  }
}
