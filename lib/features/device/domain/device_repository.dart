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
}
