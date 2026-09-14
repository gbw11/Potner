import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/arrival/data/arrival_api.dart';
import 'package:potner_app/features/arrival/domain/arrival_models.dart';
import 'package:potner_app/features/arrival/domain/arrival_repository.dart';

final arrivalRepositoryProvider = Provider<ArrivalRepository>((ref) {
  return ArrivalRepositoryImpl(ArrivalApi(ref.watch(apiClientProvider).dio));
});

class ArrivalRepositoryImpl implements ArrivalRepository {
  ArrivalRepositoryImpl(this._api);

  final ArrivalApi _api;

  @override
  Future<ArrivalEventReceipt> sendEvent(ArrivalEventCall event) {
    return _api.sendEvent(event);
  }

  @override
  Future<ArrivalEventStatus> getEventStatus(String eventId) {
    return _api.getEventStatus(eventId);
  }
}
