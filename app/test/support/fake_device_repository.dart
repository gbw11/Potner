import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/device/domain/device_repository.dart';

class FakeDeviceRepository implements DeviceRepository {
  FakeDeviceRepository({List<ManagedRobot>? robots})
    : robots = List.of(robots ?? sampleManagedRobots);

  final List<ManagedRobot> robots;
  final Map<String, RobotLiveStatus> liveStatuses = {
    'plant-rose': const RobotLiveStatus(
      currentState: RobotActivityState.servicing,
      batteryPercent: 85,
    ),
  };
  final List<RegisterRobotCall> registerRobotCalls = [];
  final List<RegisterIotDeviceCall> registerIotDeviceCalls = [];
  final List<AssignCall> assignCalls = [];
  final List<String> reissuedRobotIds = [];
  final List<String> unassignedPlantIds = [];
  final List<String> deletedRobotIds = [];
  Object? registerIotDeviceError;
  Object? assignError;

  @override
  Future<RobotRegistration> registerRobot({
    required String deviceUid,
    required String name,
  }) async {
    registerRobotCalls.add(RegisterRobotCall(deviceUid: deviceUid, name: name));
    robots.add(
      ManagedRobot(
        robotId: 'robot-new',
        deviceUid: deviceUid,
        name: name,
        connectionStatus: DeviceConnectionStatus.online,
        devices: const [],
      ),
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

  @override
  Future<List<ManagedRobot>> getRobots() async => List.of(robots);

  @override
  Future<void> deleteRobot(String robotId) async {
    deletedRobotIds.add(robotId);
    robots.removeWhere((robot) => robot.robotId == robotId);
  }

  @override
  Future<RobotLiveStatus?> getPlantRobotStatus(String plantId) async {
    return liveStatuses[plantId];
  }

  @override
  Future<String> reissueUploadToken(String robotId) async {
    reissuedRobotIds.add(robotId);
    return 'reissued-token-5678';
  }

  final Map<String, List<RobotLocationInfo>> locations = {};
  final List<({String robotId, RobotLocationType type, String? stationCode})>
  registerLocationCalls = [];
  final List<({RobotLocationType type, double x, double y, double yaw})>
  poseUpdates = [];

  @override
  Future<List<RobotLocationInfo>> getRobotLocations(String robotId) async {
    return List.of(locations[robotId] ?? const []);
  }

  @override
  Future<RobotLocationInfo> registerRobotLocation({
    required String robotId,
    required RobotLocationType type,
    String? stationCode,
  }) async {
    registerLocationCalls.add((
      robotId: robotId,
      type: type,
      stationCode: stationCode,
    ));
    final info = RobotLocationInfo(
      locationId: 'loc-${type.name}',
      type: type,
      stationCode: stationCode,
      poseConfigured: false,
      waterLow: false,
    );
    locations.putIfAbsent(robotId, () => []).add(info);
    return info;
  }

  @override
  Future<RobotLocationInfo> updateLocationPose({
    required String robotId,
    required RobotLocationType type,
    required double x,
    required double y,
    required double yaw,
  }) async {
    poseUpdates.add((type: type, x: x, y: y, yaw: yaw));
    final list = locations.putIfAbsent(robotId, () => []);
    final index = list.indexWhere((location) => location.type == type);
    final updated = RobotLocationInfo(
      locationId: 'loc-${type.name}',
      type: type,
      stationCode: index >= 0 ? list[index].stationCode : null,
      poseX: x,
      poseY: y,
      poseYaw: yaw,
      poseConfigured: true,
      waterLow: index >= 0 && list[index].waterLow,
    );
    if (index >= 0) {
      list[index] = updated;
    } else {
      list.add(updated);
    }
    return updated;
  }

  @override
  Future<void> unassignPlant(String plantId) async {
    unassignedPlantIds.add(plantId);
    for (var i = 0; i < robots.length; i++) {
      if (robots[i].assignedPlantId == plantId) {
        robots[i] = ManagedRobot(
          robotId: robots[i].robotId,
          deviceUid: robots[i].deviceUid,
          name: robots[i].name,
          connectionStatus: robots[i].connectionStatus,
          devices: robots[i].devices,
          lastSeenAt: robots[i].lastSeenAt,
          batteryPercent: robots[i].batteryPercent,
          firmwareVersion: robots[i].firmwareVersion,
        );
      }
    }
  }
}

final sampleManagedRobots = [
  ManagedRobot(
    robotId: 'robot-1',
    deviceUid: 'potner-robot-01',
    name: '포트니',
    connectionStatus: DeviceConnectionStatus.online,
    batteryPercent: 85,
    firmwareVersion: '1.2.0',
    assignedPlantId: 'plant-rose',
    assignedPlantName: '로지',
    lastSeenAt: DateTime.utc(2026, 7, 30, 8),
    devices: const [
      ManagedIotDevice(
        deviceUid: 'station-01',
        deviceType: 'RASPBERRY_PI',
        connectionStatus: DeviceConnectionStatus.online,
      ),
    ],
  ),
  const ManagedRobot(
    robotId: 'robot-2',
    deviceUid: 'potner-robot-02',
    name: '새싹이',
    connectionStatus: DeviceConnectionStatus.offline,
    devices: [],
  ),
];

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
