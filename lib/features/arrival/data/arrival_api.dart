import 'package:dio/dio.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/arrival/domain/arrival_models.dart';

class ArrivalApi {
  ArrivalApi(this._dio);

  final Dio _dio;

  Future<ArrivalEventReceipt> sendEvent(ArrivalEventCall event) async {
    try {
      final response = await _dio.post<Object?>(
        'arrival/events',
        data: {
          'eventId': event.eventId,
          'visitId': event.visitId,
          'eventType': event.eventType.wireName,
          'source': event.source.wireName,
          'geofenceId': event.geofenceId,
          'occurredAt': event.occurredAt.toUtc().toIso8601String(),
        },
      );
      final data = _asMap(response.data);
      return ArrivalEventReceipt(
        eventId: _requiredString(data['eventId']),
        status: _requiredString(data['status']),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  Future<ArrivalEventStatus> getEventStatus(String eventId) async {
    try {
      final response = await _dio.get<Object?>('arrival/events/$eventId');
      final data = _asMap(response.data);
      return ArrivalEventStatus(
        eventId: _requiredString(data['eventId']),
        visitId: _requiredString(data['visitId']),
        eventType: _eventType(data['eventType']),
        status: _processingStatus(data['status']),
        errorMessage: _optionalString(data['errorMessage']),
        reportedAt: _optionalDateTime(data['reportedAt']),
      );
    } on DioException catch (exception) {
      throw ApiException.fromDio(exception);
    } on FormatException catch (exception) {
      throw ApiException.invalidResponse(exception);
    }
  }

  ArrivalEventType _eventType(Object? value) {
    return switch (value) {
      'APPROACH' => ArrivalEventType.approach,
      'CANCEL' => ArrivalEventType.cancel,
      _ => throw FormatException('Unknown arrival event type: $value'),
    };
  }

  ArrivalProcessingStatus _processingStatus(Object? value) {
    return switch (value) {
      'COMMAND_PUBLISHED' => ArrivalProcessingStatus.commandPublished,
      'OK' => ArrivalProcessingStatus.ok,
      'ERROR' => ArrivalProcessingStatus.error,
      'BUSY' => ArrivalProcessingStatus.busy,
      'TIMED_OUT' => ArrivalProcessingStatus.timedOut,
      _ => ArrivalProcessingStatus.unknown,
    };
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
