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

  Future<void> deleteRobot(String robotId);

  /// 배정된 로봇이 없으면 null 이다.
  Future<RobotLiveStatus?> getPlantRobotStatus(String plantId);

  /// 재발급 즉시 이전 토큰이 무효가 된다. 원문은 이 응답에서만 볼 수 있다.
  Future<String> reissueUploadToken(String robotId);

  Future<void> unassignPlant(String plantId);

  Future<List<RobotLocationInfo>> getRobotLocations(String robotId);

  /// 종류별로 하나만 등록된다. [stationCode] 는 급수 스테이션에만 넣는다.
  Future<RobotLocationInfo> registerRobotLocation({
    required String robotId,
    required RobotLocationType type,
    String? stationCode,
  });

  /// RViz 에서 읽은 map 프레임 좌표(x·y m, yaw rad)를 넣는다. 세 값 모두 필수다.
  Future<RobotLocationInfo> updateLocationPose({
    required String robotId,
    required RobotLocationType type,
    required double x,
    required double y,
    required double yaw,
  });
}
