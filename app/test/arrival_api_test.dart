import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:potner_app/features/arrival/data/arrival_api.dart';
import 'package:potner_app/features/arrival/domain/arrival_models.dart';

void main() {
  test('ArrivalApi sends debug event and maps Jetson OK status', () async {
    Object? capturedBody;
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1/'));
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          if (options.method == 'POST') {
            capturedBody = options.data;
            handler.resolve(
              Response<Object?>(
                requestOptions: options,
                statusCode: 202,
                data: {'eventId': 'event-01', 'status': 'COMMAND_PUBLISHED'},
              ),
            );
            return;
          }
          handler.resolve(
            Response<Object?>(
              requestOptions: options,
              statusCode: 200,
              data: {
                'eventId': 'event-01',
                'visitId': 'visit-01',
                'eventType': 'APPROACH',
                'status': 'OK',
                'errorMessage': null,
                'reportedAt': '2026-07-31T06:00:00',
              },
            ),
          );
        },
      ),
    );
    final api = ArrivalApi(dio);

    final receipt = await api.sendEvent(
      ArrivalEventCall(
        eventId: 'event-01',
        visitId: 'visit-01',
        eventType: ArrivalEventType.approach,
        source: ArrivalEventSource.debugButton,
        geofenceId: 'DEBUG_BUTTON',
        occurredAt: DateTime.utc(2026, 7, 31, 5),
      ),
    );
    final status = await api.getEventStatus(receipt.eventId);

    expect(receipt.status, 'COMMAND_PUBLISHED');
    expect(status.status, ArrivalProcessingStatus.ok);
    expect(capturedBody, {
      'eventId': 'event-01',
      'visitId': 'visit-01',
      'eventType': 'APPROACH',
      'source': 'DEBUG_BUTTON',
      'geofenceId': 'DEBUG_BUTTON',
      'occurredAt': '2026-07-31T05:00:00.000Z',
    });
  });
}
