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

/// 방향 버튼 하나가 뜻하는 방향이다.
///
/// 목적지 이동([DeviceCommandType.navigate])과 다른 통로다. 그쪽은 목적지의 지도 좌표가 입력되어
/// 있어야 발행되지만, 이쪽은 좌표를 보지 않으므로 **지도를 만들기 전에도 바퀴가 도는지 확인할 수
/// 있다.**
///
/// 속도를 앱이 정하지 않는다. 사람 옆에서 움직이는 장치의 속도는 서버 설정이 유일한 출처다 —
/// 앱에 숫자를 박아 두면 로봇이 바뀔 때 앱을 다시 배포해야 하고, 조작하는 사람이 상한을 올릴 수도
/// 있다. 버튼 한 번에 움직이는 시간도 서버가 정하며, **로봇은 그 시간이 지나면 스스로 멈춘다.**
enum DriveDirection {
  forward,
  backward,

  /// 제자리 좌회전.
  left,

  /// 제자리 우회전.
  right,

  /// 정지. 남은 시간을 기다리지 않고 즉시 세운다.
  stop;

  String get wireName => switch (this) {
    DriveDirection.forward => 'FORWARD',
    DriveDirection.backward => 'BACKWARD',
    DriveDirection.left => 'LEFT',
    DriveDirection.right => 'RIGHT',
    DriveDirection.stop => 'STOP',
  };

  String get label => switch (this) {
    DriveDirection.forward => '전진',
    DriveDirection.backward => '후진',
    DriveDirection.left => '좌회전',
    DriveDirection.right => '우회전',
    DriveDirection.stop => '정지',
  };
}

/// 로봇 디스플레이에 그릴 표정이다.
///
/// 장치 명령([DeviceCommandType])과 다르게 다룬다. 표정은 회신 계약이 없고 이력도 남지 않으며,
/// 서버가 주기적으로 판정해 밀어 주는 값이다. 손으로 보낸 값은 **다음 주기가 덮어쓴다.**
enum PlantExpression {
  /// 급수·송풍 받는 중, 사용자 반기는 중.
  veryHappy,

  /// 오늘 개화, 충분한 광량.
  happy,

  /// 특별한 일 없음.
  neutral,

  /// 온도나 습도가 기준을 벗어남.
  sad;

  String get wireName => switch (this) {
    PlantExpression.veryHappy => 'VERY_HAPPY',
    PlantExpression.happy => 'HAPPY',
    PlantExpression.neutral => 'NEUTRAL',
    PlantExpression.sad => 'SAD',
  };

  String get label => switch (this) {
    PlantExpression.veryHappy => '매우 행복',
    PlantExpression.happy => '행복',
    PlantExpression.neutral => '보통',
    PlantExpression.sad => '우울',
  };
}

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
