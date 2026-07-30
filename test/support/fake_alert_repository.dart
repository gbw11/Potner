import 'package:potner_app/features/alert/domain/alert_models.dart';
import 'package:potner_app/features/alert/domain/alert_repository.dart';

class FakeAlertRepository implements AlertRepository {
  FakeAlertRepository({List<PlantAlert>? alerts})
    : alerts = List.of(alerts ?? _defaultAlerts());

  final List<PlantAlert> alerts;
  final List<String> readAlertIds = [];

  static List<PlantAlert> _defaultAlerts() {
    final now = DateTime.now().toUtc();
    return [
      PlantAlert(
        alertId: 'alert-active',
        plantId: 'plant-rose',
        plantName: '로지',
        metric: AlertMetric.humidity,
        deviation: AlertDeviation.high,
        measuredValue: 89,
        occurredAt: now.subtract(const Duration(minutes: 5)),
        active: true,
        read: false,
      ),
      PlantAlert(
        alertId: 'alert-resolved',
        plantId: 'plant-rose',
        plantName: '로지',
        metric: AlertMetric.temperature,
        deviation: AlertDeviation.low,
        measuredValue: 10,
        occurredAt: now.subtract(const Duration(days: 1)),
        resolvedAt: now.subtract(const Duration(hours: 20)),
        active: false,
        read: true,
      ),
    ];
  }

  @override
  Future<List<PlantAlert>> getAlerts() async => List.of(alerts);

  @override
  Future<void> markRead(String alertId) async {
    readAlertIds.add(alertId);
  }
}
