import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/features/device/domain/robot_status.dart';

final robotStatusControllerProvider =
    NotifierProvider<RobotStatusController, RobotStatus>(
      RobotStatusController.new,
    );

class RobotStatusController extends Notifier<RobotStatus> {
  @override
  RobotStatus build() {
    // TODO: 로봇 상태 API 또는 WebSocket 연결 후 수신 상태로 교체합니다.
    return RobotStatus.resting;
  }

  /// 추후 실시간 상태 수신부에서 호출할 상태 갱신 진입점입니다.
  void updateStatus(RobotStatus status) {
    state = status;
  }
}
