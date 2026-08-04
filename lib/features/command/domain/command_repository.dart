import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/device/domain/device_models.dart';

abstract class DeviceCommandRepository {
  /// 명령을 발행한다. 응답의 status 는 항상 `issued` 이며 수행 결과는 이력 조회로 확인한다.
  ///
  /// [destination] 은 NAVIGATE 만, [seconds] 는 FAN 만 쓴다. 다른 종류에 넣으면 서버가 400 이다.
  Future<DeviceCommandRecord> issue({
    required String plantId,
    required DeviceCommandType type,
    RobotLocationType? destination,
    int? seconds,
  });

  /// 기간 안의 명령 이력을 최신순으로 돌려준다. 양쪽 날짜 모두 포함이다.
  Future<List<DeviceCommandRecord>> getCommands({
    required String plantId,
    required DateTime from,
    required DateTime to,
  });

  /// 방향 버튼 한 번을 보낸다. 로봇이 서버가 정한 시간만큼 움직이고 스스로 멈춘다.
  ///
  /// 회신을 기다리지 않는다. 방향 버튼은 연달아 눌리는 것이 정상이라 서버가 회신을 대조해
  /// 확정하는 흐름([issue])에 넣으면 두 번째 누름부터 409 로 막힌다. 그래서 이력도 남지 않으며
  /// 로봇이 실제로 움직였는지는 눈으로 봐야 한다.
  Future<void> drive({
    required String plantId,
    required DriveDirection direction,
  });

  /// 로봇 디스플레이에 표정을 한 번 보낸다.
  ///
  /// 회신을 기다리지 않는다. 표정에는 result 계약이 없어 수행 여부를 서버가 확인할 방법이
  /// 없으므로 로봇 화면을 눈으로 봐야 한다. **다음 주기 발행(기본 30초)이 덮어쓴다.**
  Future<void> publishExpression({
    required String plantId,
    required PlantExpression expression,
  });
}
