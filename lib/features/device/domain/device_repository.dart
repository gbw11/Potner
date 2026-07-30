import 'package:potner_app/features/device/domain/device_models.dart';

abstract class DeviceRepository {
  Future<RobotRegistration> registerRobot({
    required String deviceUid,
    required String name,
  });

  Future<void> registerIotDevice({
    required String robotId,
    required String deviceUid,
    required IotDeviceType deviceType,
  });

  Future<void> assignRobotToPlant({
    required String plantId,
    required String robotId,
  });

  Future<List<ManagedRobot>> getRobots();

  /// 배정된 로봇이 없으면 null 이다.
  Future<RobotLiveStatus?> getPlantRobotStatus(String plantId);

  /// 재발급 즉시 이전 토큰이 무효가 된다. 원문은 이 응답에서만 볼 수 있다.
  Future<String> reissueUploadToken(String robotId);

  Future<void> unassignPlant(String plantId);
}
