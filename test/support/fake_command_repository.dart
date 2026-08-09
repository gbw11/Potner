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

  final List<({String plantId, CareRunPurpose purpose})> careRunCalls = [];

  Object? careRunError;

  /// 회차의 첫 명령이 갖는 requestId 다. 이력에 심어 둘 단계와 맞춰야 폴링이 회차를 집는다.
  String nextCareRunRequestId = 'care-run-1';

  /// 첫 명령의 발행 시각이다. 뒤따르는 자동 명령을 같은 회차로 묶는 기준이라 비울 수 없다.
  DateTime careRunIssuedAt = DateTime(2026, 8, 9, 10);

  @override
  Future<DeviceCommandRecord> startCareRun({
    required String plantId,
    required CareRunPurpose purpose,
  }) async {
    final error = careRunError;
    if (error != null) {
      throw error;
    }
    careRunCalls.add((plantId: plantId, purpose: purpose));
    // 넷 다 이동으로 시작한다. 재배치만 햇빛 자리로, 나머지는 스테이션으로 간다.
    return DeviceCommandRecord(
      requestId: nextCareRunRequestId,
      type: DeviceCommandType.navigate,
      status: DeviceCommandStatus.issued,
      initiator: CommandInitiator.auto,
      destination: purpose == CareRunPurpose.relocation
          ? RobotLocationType.sunlight
          : RobotLocationType.waterStation,
      issuedAt: careRunIssuedAt,
    );
  }

  /// 이력을 몇 번 읽었는지. 기다릴 대상이 없을 때 폴링이 돌지 않는 것을 볼 수 있어야 한다.
  int getCommandsCalls = 0;

  @override
  Future<List<DeviceCommandRecord>> getCommands({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) async {
    getCommandsCalls++;
    return List.of(commands);
  }

  final List<({String plantId, DriveDirection direction})> driveCalls = [];

  Object? driveError;

  /// 응답을 늦춘다. 요청이 날아가는 중에도 다음 버튼(특히 정지)이 눌리는지 보려면 겹치는
  /// 순간을 만들 수 있어야 한다.
  Duration? driveDelay;

  @override
  Future<void> drive({
    required String plantId,
    required DriveDirection direction,
  }) async {
    driveCalls.add((plantId: plantId, direction: direction));
    final delay = driveDelay;
    if (delay != null) {
      await Future<void>.delayed(delay);
    }
    final error = driveError;
    if (error != null) {
      throw error;
    }
  }

  final List<({String plantId, PlantExpression expression})> expressionCalls =
      [];

  Object? expressionError;

  @override
  Future<void> publishExpression({
    required String plantId,
    required PlantExpression expression,
  }) async {
    final error = expressionError;
    if (error != null) {
      throw error;
    }
    expressionCalls.add((plantId: plantId, expression: expression));
  }
}
