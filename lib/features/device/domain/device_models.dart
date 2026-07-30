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
