import 'package:potner_app/features/alert/domain/alert_models.dart';

abstract class AlertRepository {
  /// 내 알림을 최신순으로 돌려준다.
  Future<List<PlantAlert>> getAlerts();

  Future<void> markRead(String alertId);

  /// 알림을 목록에서 치운다. 서버가 기록하므로 앱을 다시 켜도 돌아오지 않는다.
  ///
  /// 서버는 행을 지우지 않는다 — 행복도 점수는 조회할 때마다 그날 알림을 세고, 자동 급수·말리기는
  /// 해소되지 않은 알림을 보고 트리거되며, 일기도 알림을 근거로 쓴다. 지우면 지난 점수가 바뀌고
  /// 로봇이 물을 또 준다. 읽음 처리는 서버가 함께 한다.
  Future<void> dismiss(String alertId);

  /// 치운 것을 되돌린다. 스와이프는 눌러 확인하는 동작이 없어 오조작이 쉽다.
  Future<void> restore(String alertId);
}
