import 'package:potner_app/features/arrival/domain/arrival_models.dart';
import 'package:potner_app/features/arrival/domain/arrival_repository.dart';

class FakeArrivalRepository implements ArrivalRepository {
  final List<ArrivalEventCall> calls = [];
  final Map<String, ArrivalEventCall> _events = {};
  ArrivalProcessingStatus resultStatus = ArrivalProcessingStatus.ok;
  String? resultError;

  @override
  Future<ArrivalEventReceipt> sendEvent(ArrivalEventCall event) async {
    calls.add(event);
    _events[event.eventId] = event;
    return ArrivalEventReceipt(
      eventId: event.eventId,
      status: 'COMMAND_PUBLISHED',
    );
  }

  @override
  Future<ArrivalEventStatus> getEventStatus(String eventId) async {
    final event = _events[eventId];
    if (event == null) {
      throw StateError('Unknown event: $eventId');
    }
    return ArrivalEventStatus(
      eventId: event.eventId,
      visitId: event.visitId,
      eventType: event.eventType,
      status: resultStatus,
      errorMessage: resultError,
      reportedAt: DateTime.utc(2026, 7, 31, 6),
    );
  }
}
