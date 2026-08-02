import 'package:potner_app/features/device/domain/device_models.dart';

/// 장치 명령의 종류다. 받는 보드가 종류마다 다르다.
///
/// 급수·촬영·송풍은 라즈베리파이가, 이동은 젯슨이 받는다. 그래서 해당 보드가 등록되어 있지
/// 않으면 그 종류는 발행되지 않는다.
enum DeviceCommandType {
  /// 급수. 양은 요청이 아니라 식물의 적용 생육 기준에서 나온다.
  water,

  /// 성장 사진 촬영.
  capture,

  /// 과습을 말리는 송풍.
  fan,

  /// 지도 좌표로 이동. **목적지의 좌표가 입력되어 있어야 발행된다.**
  navigate;

  String get wireName => switch (this) {
    DeviceCommandType.water => 'WATER',
    DeviceCommandType.capture => 'CAPTURE',
    DeviceCommandType.fan => 'FAN',
    DeviceCommandType.navigate => 'NAVIGATE',
  };

  String get label => switch (this) {
    DeviceCommandType.water => '물 주기',
    DeviceCommandType.capture => '사진 찍기',
    DeviceCommandType.fan => '송풍',
    DeviceCommandType.navigate => '이동',
  };
}

/// 명령의 진행 상태다. `issued` 만 비종결이고 나머지는 장치 회신이나 타임아웃으로 확정된다.
enum DeviceCommandStatus {
  /// 발행했고 장치 회신을 기다린다.
  issued,

  /// 장치가 수행을 완료했다.
  ok,

  /// 장치가 실패를 보고했다.
  error,

  /// 앞선 작업 중이라 장치가 거절했다. 잠시 뒤 다시 보내면 된다.
  busy,

  /// 제한 시간 안에 회신이 없어 서버가 끊었다.
  timedOut,

  /// 서버가 뒤에 추가한 상태다. 종결로 보지 않아 폴링이 멈추지 않게 한다.
  unknown;

  bool get isTerminal => this != DeviceCommandStatus.issued && this != unknown;
}

/// 누가 발행했는지다. 이력에서 "내가 안 시킨 급수"를 구분하는 근거다.
enum CommandInitiator { user, auto, unknown }

class DeviceCommandRecord {
  const DeviceCommandRecord({
    required this.requestId,
    required this.type,
    required this.status,
    required this.initiator,
    this.destination,
    this.requestedMl,
    this.dispensedMl,
    this.runSeconds,
    this.errorMessage,
    this.issuedAt,
    this.reportedAt,
  });

  final String requestId;
  final DeviceCommandType? type;
  final DeviceCommandStatus status;
  final CommandInitiator initiator;
  final RobotLocationType? destination;
  final double? requestedMl;
  final double? dispensedMl;
  final int? runSeconds;
  final String? errorMessage;
  final DateTime? issuedAt;
  final DateTime? reportedAt;
}
