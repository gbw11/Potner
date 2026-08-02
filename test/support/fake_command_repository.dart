import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/command/domain/command_repository.dart';
import 'package:potner_app/features/device/domain/device_models.dart';

class FakeDeviceCommandRepository implements DeviceCommandRepository {
  final List<
    ({
      String plantId,
      DeviceCommandType type,
      RobotLocationType? destination,
      int? seconds,
    })
  >
  issueCalls = [];

  /// 폴링이 찾을 이력이다. 테스트가 원하는 종결 상태를 미리 심어 둔다.
  final List<DeviceCommandRecord> commands = [];

  Object? issueError;

  /// 발행 응답에 쓸 requestId 다. 폴링 대상을 테스트가 지목할 수 있어야 한다.
  String nextRequestId = 'request-1';

  @override
  Future<DeviceCommandRecord> issue({
    required String plantId,
    required DeviceCommandType type,
    RobotLocationType? destination,
    int? seconds,
  }) async {
    final error = issueError;
    if (error != null) {
      throw error;
    }
    issueCalls.add((
      plantId: plantId,
      type: type,
      destination: destination,
      seconds: seconds,
    ));
    return DeviceCommandRecord(
      requestId: nextRequestId,
      type: type,
      status: DeviceCommandStatus.issued,
      initiator: CommandInitiator.user,
      destination: destination,
    );
  }

  @override
  Future<List<DeviceCommandRecord>> getCommands({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) async {
    return List.of(commands);
  }
}
