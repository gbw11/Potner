/// 로봇 하위 IoT 장치 종류다. 서버 enum 과 같은 이름을 쓴다.
enum IotDeviceType { raspberryPi, jetsonOrin }

extension IotDeviceTypeWire on IotDeviceType {
  String get wireName => switch (this) {
    IotDeviceType.raspberryPi => 'RASPBERRY_PI',
    IotDeviceType.jetsonOrin => 'JETSON_ORIN',
  };
}

/// 장치의 연결 상태다. 서버가 모르는 값이 와도 앱이 죽지 않도록 unknown 을 둔다.
enum DeviceConnectionStatus { online, offline, error, unknown }

/// 로봇의 현재 행동 상태다.
///
/// SERVICING 은 급수와 송풍을 함께 가리킨다. 서버가 둘을 구별할 수 없으므로
/// 앱 문구도 하나로 묶는다.
enum RobotActivityState { idle, navigating, docking, servicing, greeting, unknown }

/// 장치 관리 화면의 로봇 한 대다.
class ManagedRobot {
  const ManagedRobot({
    required this.robotId,
    required this.deviceUid,
    required this.name,
    required this.connectionStatus,
    required this.devices,
    this.lastSeenAt,
    this.batteryPercent,
    this.firmwareVersion,
    this.assignedPlantId,
    this.assignedPlantName,
  });

  final String robotId;
  final String deviceUid;
  final String name;
  final DeviceConnectionStatus connectionStatus;
  final DateTime? lastSeenAt;

  /// 수집 연동 전까지 null 이다.
  final int? batteryPercent;
  final String? firmwareVersion;
  final String? assignedPlantId;
  final String? assignedPlantName;
  final List<ManagedIotDevice> devices;
}

class ManagedIotDevice {
  const ManagedIotDevice({
    required this.deviceUid,
    required this.deviceType,
    required this.connectionStatus,
    this.lastSeenAt,
  });

  final String deviceUid;
  final String deviceType;
  final DeviceConnectionStatus connectionStatus;
  final DateTime? lastSeenAt;
}

/// 배정된 식물 기준의 로봇 실시간 상태다. 행동 상태는 이 조회로만 내려온다.
class RobotLiveStatus {
  const RobotLiveStatus({
    required this.currentState,
    this.stateChangedAt,
    this.batteryPercent,
    this.batteryMeasuredAt,
  });

  final RobotActivityState currentState;
  final DateTime? stateChangedAt;
  final int? batteryPercent;
  final DateTime? batteryMeasuredAt;
}

/// 로봇이 오가는 위치의 종류다. 종류별로 하나만 등록할 수 있다.
enum RobotLocationType { waterStation, home, sunlight, greeting }

extension RobotLocationTypeSpec on RobotLocationType {
  String get wireName => switch (this) {
    RobotLocationType.waterStation => 'WATER_STATION',
    RobotLocationType.home => 'HOME',
    RobotLocationType.sunlight => 'SUNLIGHT',
    RobotLocationType.greeting => 'GREETING',
  };

  // 서버 enum 이 WATER_STATION 인 것은 급수만 있던 시절(V21)에 붙은 이름이다. 그 뒤 송풍(V22)과
  // 촬영이 같은 자리를 쓰게 됐지만 종류를 늘리지 않았다 — 펌프·팬·카메라가 모두 스테이션의
  // 라즈베리에 붙어 있어 물리적으로 한 곳이기 때문이다. 사용자에게는 세 가지를 다 하는 자리로
  // 보여야 하므로 라벨에서 '급수' 를 뺀다.
  String get label => switch (this) {
    RobotLocationType.waterStation => '스테이션',
    RobotLocationType.home => '대기 장소',
    RobotLocationType.sunlight => '햇빛 자리',
    RobotLocationType.greeting => '마중 지점',
  };

  String get description => switch (this) {
    RobotLocationType.waterStation => '급수·촬영·송풍을 하는 자리예요. 스테이션 코드가 필요해요.',
    RobotLocationType.home => '할 일이 없을 때 돌아가 쉬는 곳이에요.',
    RobotLocationType.sunlight => '광량이 모자라면 여기로 데려가요.',
    RobotLocationType.greeting => '사용자를 맞으러 나가는 자리예요.',
  };

  /// 물리 장치가 있는 위치인지. 스테이션 코드 필수 여부가 여기서 갈린다.
  bool get needsStationCode => this == RobotLocationType.waterStation;
}

/// 등록된 위치 한 곳의 현재 상태다.
class RobotLocationInfo {
  const RobotLocationInfo({
    required this.locationId,
    required this.type,
    required this.poseConfigured,
    required this.waterLow,
    this.stationCode,
    this.poseX,
    this.poseY,
    this.poseYaw,
    this.waterLowAt,
  });

  final String locationId;
  final RobotLocationType type;
  final String? stationCode;
  final double? poseX;
  final double? poseY;
  final double? poseYaw;

  /// false 면 좌표 입력 전이라 로봇을 이 위치로 보낼 수 없다.
  final bool poseConfigured;
  final bool waterLow;
  final DateTime? waterLowAt;
}

/// 로봇 등록 결과다.
///
/// [uploadToken] 원문은 등록 응답에서만 볼 수 있다. 서버는 해시만 저장하므로
/// 사용자가 잃어버리면 재발급해야 한다.
class RobotRegistration {
  const RobotRegistration({
    required this.robotId,
    required this.deviceUid,
    required this.name,
    required this.uploadToken,
  });

  final String robotId;
  final String deviceUid;
  final String name;
  final String uploadToken;
}
