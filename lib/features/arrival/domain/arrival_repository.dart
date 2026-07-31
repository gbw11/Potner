import 'package:potner_app/features/arrival/domain/arrival_models.dart';

abstract class ArrivalRepository {
  Future<ArrivalEventReceipt> sendDebugEvent(ArrivalEventCall event);

  Future<ArrivalEventStatus> getEventStatus(String eventId);
}
