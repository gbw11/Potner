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
}
