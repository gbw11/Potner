enum ArrivalEventType { approach, cancel }

enum ArrivalEventSource { debugButton, androidGeofence }

extension ArrivalEventSourceWire on ArrivalEventSource {
  String get wireName => switch (this) {
    ArrivalEventSource.debugButton => 'DEBUG_BUTTON',
    ArrivalEventSource.androidGeofence => 'ANDROID_GEOFENCE',
  };
}

extension ArrivalEventTypeWire on ArrivalEventType {
  String get wireName => switch (this) {
    ArrivalEventType.approach => 'APPROACH',
    ArrivalEventType.cancel => 'CANCEL',
  };
}

enum ArrivalProcessingStatus {
  commandPublished,
  ok,
  error,
  busy,
  timedOut,
  unknown,
}

extension ArrivalProcessingStatusSpec on ArrivalProcessingStatus {
  bool get isTerminal => switch (this) {
    ArrivalProcessingStatus.ok ||
    ArrivalProcessingStatus.error ||
    ArrivalProcessingStatus.busy ||
    ArrivalProcessingStatus.timedOut => true,
    _ => false,
  };
}

class ArrivalEventReceipt {
  const ArrivalEventReceipt({required this.eventId, required this.status});

  final String eventId;
  final String status;
}

class ArrivalEventStatus {
  const ArrivalEventStatus({
    required this.eventId,
    required this.visitId,
    required this.eventType,
    required this.status,
    this.errorMessage,
    this.reportedAt,
  });

  final String eventId;
  final String visitId;
  final ArrivalEventType eventType;
  final ArrivalProcessingStatus status;
  final String? errorMessage;
  final DateTime? reportedAt;
}

class ArrivalEventCall {
  const ArrivalEventCall({
    required this.eventId,
    required this.visitId,
    required this.eventType,
    required this.source,
    required this.geofenceId,
    required this.occurredAt,
  });

  final String eventId;
  final String visitId;
  final ArrivalEventType eventType;
  final ArrivalEventSource source;
  final String geofenceId;
  final DateTime occurredAt;
}
