import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/alert/data/alert_api.dart';
import 'package:potner_app/features/alert/domain/alert_models.dart';
import 'package:potner_app/features/alert/domain/alert_repository.dart';

final alertRepositoryProvider = Provider<AlertRepository>((ref) {
  return AlertRepositoryImpl(AlertApi(ref.watch(apiClientProvider).dio));
});

class AlertRepositoryImpl implements AlertRepository {
  AlertRepositoryImpl(this._api);

  final AlertApi _api;

  @override
  Future<List<PlantAlert>> getAlerts() {
    return _api.getAlerts();
  }

  @override
  Future<void> markRead(String alertId) {
    return _api.markRead(alertId);
  }

  @override
  Future<void> dismiss(String alertId) {
    return _api.dismiss(alertId);
  }

  @override
  Future<void> restore(String alertId) {
    return _api.restore(alertId);
  }
}
