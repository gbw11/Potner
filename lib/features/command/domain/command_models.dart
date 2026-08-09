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

  /// 팬으로 주변 공기를 순환시키는 송풍.
  ///
  /// 습해졌을 때 말리는 대응이 아니라 **낮 동안 일정한 간격으로 도는 환기**다. 공기 순환의
  /// 이득 절반은 습도와 무관하다 — 바람에 흔들린 줄기는 굵고 짧아지고, 잎 표면의 정체된
  /// 공기층이 걷혀야 증산이 이어진다.
  ///
  /// 가동 시간과 풍량은 앱도 서버도 정하지 않는다. 라즈베리가 자기 설정으로 정하고 회신으로
  /// 실제 값을 알려 주므로, 이력의 [DeviceCommandRecord.runSeconds] 는 **실제로 돈 시간**이다.
  fan,

  /// 지도 좌표로 이동. **목적지의 좌표가 입력되어 있어야 발행된다.**
  navigate,

  /// 지도 제작(SLAM) 시작. 로봇이 자율주행을 내리고 지도 그리기로 들어간다.
  mappingStart,

  /// 그린 지도를 저장하고 제작을 끝낸다. 성공하면 그 지도로 자율주행이 다시 뜬다.
  mappingSave,

  /// 저장하지 않고 제작을 끝낸다. 지도가 겹쳐 그려졌을 때 버리고 다시 시작하는 통로다.
  mappingCancel;

  String get wireName => switch (this) {
    DeviceCommandType.water => 'WATER',
    DeviceCommandType.capture => 'CAPTURE',
    DeviceCommandType.fan => 'FAN',
    DeviceCommandType.navigate => 'NAVIGATE',
    DeviceCommandType.mappingStart => 'MAPPING_START',
    DeviceCommandType.mappingSave => 'MAPPING_SAVE',
    DeviceCommandType.mappingCancel => 'MAPPING_CANCEL',
  };

  String get label => switch (this) {
    DeviceCommandType.water => '물 주기',
    DeviceCommandType.capture => '사진 찍기',
    DeviceCommandType.fan => '송풍',
    DeviceCommandType.navigate => '이동',
    DeviceCommandType.mappingStart => '지도 그리기 시작',
    DeviceCommandType.mappingSave => '지도 저장',
    DeviceCommandType.mappingCancel => '지도 그리기 취소',
  };

  /// 지도 제작 세 종류인지. 명령 이력에서 매핑 진행 여부를 가릴 때 쓴다.
  bool get isMapping =>
      this == DeviceCommandType.mappingStart ||
      this == DeviceCommandType.mappingSave ||
      this == DeviceCommandType.mappingCancel;
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

  /// 장치가 자기 판단으로 수행하지 않았다. 급수만 온다.
  ///
  /// 라즈베리의 과급수 가드다 — 1회 상한·급수 간격·24시간 예산 중 하나에 걸리면 펌프를 돌리지
  /// 않는다. **실패가 아니다.** 장치는 멀쩡하고 물을 주지 않기로 판단했을 뿐이라 회차는 송풍·
  /// 복귀까지 그대로 이어진다.
  skipped,

  /// 제한 시간 안에 회신이 없어 서버가 끊었다.
  timedOut,

  /// 서버가 뒤에 추가한 상태다. 종결로 보지 않아 폴링이 멈추지 않게 한다.
  unknown;

  bool get isTerminal => this != DeviceCommandStatus.issued && this != unknown;

  /// 회차의 다음 단계를 기다려도 되는 상태인지. 서버의 `continuesChain()` 과 같은 규칙이다.
  ///
  /// 둘의 공통점은 로봇이 어디 있는지 안다는 것이다 — 시킨 자리에서 일을 마쳤거나, 하지
  /// 않기로 했을 뿐이다. 나머지는 회차가 거기서 끝난 것으로 본다.
  bool get continuesChain =>
      this == DeviceCommandStatus.ok || this == DeviceCommandStatus.skipped;
}

/// 누가 발행했는지다. 이력에서 "내가 안 시킨 급수"를 구분하는 근거다.
///
/// 앱이 시작한 자동 케어 회차([CareRunPurpose])도 `auto` 다. 체인을 잇는 서버 리스너들이
/// 사용자 명령의 회신에는 후속을 붙이지 않기 때문이고, 시작 신호만 사람이 줬을 뿐 이후 판단은
/// 실제로 서버가 한다. 그래서 이력에서는 스케줄러가 스스로 시작한 회차와 구분되지 않는다.
enum CommandInitiator { user, auto, unknown }

/// 자동 케어 한 회차의 종류다.
///
/// 명령([DeviceCommandType])과 다른 층이다. 명령은 장치 하나가 받는 단일 동작이고, 이쪽은
/// **이동 → 작업 → 복귀로 이어지는 회차 전체**다. 그래서 앱은 종류만 고르고 어떤 명령을 어떤
/// 순서로 보낼지는 서버가 정한다 — 그 판단을 앱이 대신하기 시작하면 자동 케어와 시연 경로가
/// 두 벌로 갈린다.
///
/// 수동 명령으로는 대신할 수 없다. 그쪽은 `initiator` 가 USER 라 체인이 이어지지 않아서
/// 송풍 버튼을 눌러도 스테이션에서 팬만 돌 뿐 로봇은 움직이지 않고, 이동은 사용자 배치로
/// 기록되어 자동 재배치가 한동안 비켜서기까지 한다.
enum CareRunPurpose {
  /// 이동(스테이션) → 급수 → 송풍 → 복귀.
  watering,

  /// 이동(스테이션) → 송풍 → 복귀.
  drying,

  /// 이동(스테이션) → 촬영 → 복귀.
  capture,

  /// 햇빛 자리로 이동.
  ///
  /// 이어지는 단계가 없는 단발 이동이다. 햇빛 자리는 가서 일하고 오는 곳이 아니라 머무는
  /// 곳이고, 복귀는 목표 광량을 채우거나 해가 진 뒤에 서버가 판단한다.
  ///
  /// **같은 요청이 되돌리기도 한다.** 그 판단을 기다릴 수 없을 때를 위해, 로봇이 이미 햇빛
  /// 자리에 있으면 서버가 목적지를 대기 장소로 뒤집는다.
  relocation;

  String get wireName => switch (this) {
    CareRunPurpose.watering => 'WATERING',
    CareRunPurpose.drying => 'DRYING',
    CareRunPurpose.capture => 'CAPTURE',
    CareRunPurpose.relocation => 'RELOCATION',
  };

  String get label => switch (this) {
    CareRunPurpose.watering => '급수',
    CareRunPurpose.drying => '환기',
    CareRunPurpose.capture => '촬영',
    CareRunPurpose.relocation => '햇빛 자리',
  };

  /// 버튼 아래에 붙여 무엇이 일어나는지 미리 보여 준다.
  String get steps => switch (this) {
    CareRunPurpose.watering => '이동 → 급수 → 송풍 → 복귀',
    CareRunPurpose.drying => '이동 → 송풍 → 복귀',
    CareRunPurpose.capture => '이동 → 촬영 → 복귀',
    CareRunPurpose.relocation => '햇빛 자리로 이동 (이미 있으면 대기 장소로 복귀)',
  };

  /// 대기 장소 복귀로 끝나는 회차인지.
  ///
  /// 회차가 끝났는지 판단하는 근거다. 단계 수를 세지 않는 이유는 순서를 정하는 것이 서버이기
  /// 때문이다 — 앱이 단계 목록을 복사해 두면 서버가 체인을 고칠 때마다 앱이 어긋난다.
  bool get returnsHome => this != CareRunPurpose.relocation;
}

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

  /// 자동 케어 회차 안에서 이 단계를 부르는 이름이다.
  ///
  /// 이동은 목적지가 붙어야 "어디로" 가 보인다. 대기 장소로 가는 이동만 '복귀' 라고 부른다 —
  /// 회차의 마지막 단계라서 사용자가 기다림의 끝으로 읽어야 한다.
  String get stepLabel {
    final type = this.type;
    if (type == null) {
      return '알 수 없는 단계';
    }
    if (type != DeviceCommandType.navigate) {
      return type.label;
    }
    return switch (destination) {
      null => '이동',
      RobotLocationType.home => '대기 장소로 복귀',
      final destination => '${destination.label}(으)로 이동',
    };
  }
}
