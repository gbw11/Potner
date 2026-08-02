import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/device/data/device_api.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/device/domain/device_repository.dart';

final deviceRepositoryProvider = Provider<DeviceRepository>((ref) {
  return DeviceRepositoryImpl(DeviceApi(ref.watch(apiClientProvider).dio));
});

class DeviceRepositoryImpl implements DeviceRepository {
  DeviceRepositoryImpl(this._api);

  final DeviceApi _api;

  @override
  Future<RobotRegistration> registerRobot({
    required String deviceUid,
    required String name,
  }) {
    return _api.registerRobot(deviceUid: deviceUid, name: name);
  }

  @override
  Future<void> registerIotDevice({
    required String robotId,
    required String deviceUid,
    required IotDeviceType deviceType,
  }) {
    return _api.registerIotDevice(
      robotId: robotId,
      deviceUid: deviceUid,
      deviceType: deviceType,
    );
  }

  @override
  Future<void> assignRobotToPlant({
    required String plantId,
    required String robotId,
  }) {
    return _api.assignRobotToPlant(plantId: plantId, robotId: robotId);
  }

  @override
  Future<List<ManagedRobot>> getRobots() {
    return _api.getRobots();
  }

  @override
  Future<void> deleteRobot(String robotId) {
    return _api.deleteRobot(robotId);
  }

  @override
  Future<RobotLiveStatus?> getPlantRobotStatus(String plantId) {
    return _api.getPlantRobotStatus(plantId);
  }

  @override
  Future<String> reissueUploadToken(String robotId) {
    return _api.reissueUploadToken(robotId);
  }

  @override
  Future<void> unassignPlant(String plantId) {
    return _api.unassignPlant(plantId);
  }

  @override
  Future<List<RobotLocationInfo>> getRobotLocations(String robotId) {
    return _api.getRobotLocations(robotId);
  }

  @override
  Future<RobotLocationInfo> registerRobotLocation({
    required String robotId,
    required RobotLocationType type,
    String? stationCode,
  }) {
    return _api.registerRobotLocation(
      robotId: robotId,
      type: type,
      stationCode: stationCode,
    );
  }

  @override
  Future<RobotLocationInfo> updateLocationPose({
    required String robotId,
    required RobotLocationType type,
    required double x,
    required double y,
    required double yaw,
  }) {
    return _api.updateLocationPose(
      robotId: robotId,
      type: type,
      x: x,
      y: y,
      yaw: yaw,
    );
  }
}
