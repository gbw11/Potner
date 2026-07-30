import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/device/domain/device_repository.dart';

class FakeDeviceRepository implements DeviceRepository {
  final List<RegisterRobotCall> registerRobotCalls = [];
  final List<RegisterIotDeviceCall> registerIotDeviceCalls = [];
  final List<AssignCall> assignCalls = [];
  Object? registerIotDeviceError;
  Object? assignError;

  @override
  Future<RobotRegistration> registerRobot({
    required String deviceUid,
    required String name,
  }) async {
    registerRobotCalls.add(
      RegisterRobotCall(deviceUid: deviceUid, name: name),
    );
    return RobotRegistration(
      robotId: 'robot-1',
      deviceUid: deviceUid,
      name: name,
      uploadToken: 'token-plain-text-1234',
    );
  }

  @override
  Future<void> registerIotDevice({
    required String robotId,
    required String deviceUid,
    required IotDeviceType deviceType,
  }) async {
    final error = registerIotDeviceError;
    if (error != null) {
      throw error;
    }
    registerIotDeviceCalls.add(
      RegisterIotDeviceCall(
        robotId: robotId,
        deviceUid: deviceUid,
        deviceType: deviceType,
      ),
    );
  }

  @override
  Future<void> assignRobotToPlant({
    required String plantId,
    required String robotId,
  }) async {
    final error = assignError;
    if (error != null) {
      throw error;
    }
    assignCalls.add(AssignCall(plantId: plantId, robotId: robotId));
  }
}

class RegisterRobotCall {
  const RegisterRobotCall({required this.deviceUid, required this.name});

  final String deviceUid;
  final String name;
}

class RegisterIotDeviceCall {
  const RegisterIotDeviceCall({
    required this.robotId,
    required this.deviceUid,
    required this.deviceType,
  });

  final String robotId;
  final String deviceUid;
  final IotDeviceType deviceType;
}

class AssignCall {
  const AssignCall({required this.plantId, required this.robotId});

  final String plantId;
  final String robotId;
}
