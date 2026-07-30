import 'package:potner_app/features/alert/domain/alert_models.dart';

abstract class AlertRepository {
  /// 내 알림을 최신순으로 돌려준다.
  Future<List<PlantAlert>> getAlerts();

  Future<void> markRead(String alertId);
}
