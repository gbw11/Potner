/// 로봇 하위 IoT 장치 종류다. 서버 enum 과 같은 이름을 쓴다.
enum IotDeviceType { raspberryPi, jetsonOrin }

extension IotDeviceTypeWire on IotDeviceType {
  String get wireName => switch (this) {
    IotDeviceType.raspberryPi => 'RASPBERRY_PI',
    IotDeviceType.jetsonOrin => 'JETSON_ORIN',
  };
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
