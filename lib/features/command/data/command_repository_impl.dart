import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/command/data/command_api.dart';
import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/command/domain/command_repository.dart';
import 'package:potner_app/features/device/domain/device_models.dart';

final deviceCommandRepositoryProvider = Provider<DeviceCommandRepository>((ref) {
  return DeviceCommandRepositoryImpl(
    DeviceCommandApi(ref.watch(apiClientProvider).dio),
  );
});

class DeviceCommandRepositoryImpl implements DeviceCommandRepository {
  DeviceCommandRepositoryImpl(this._api);

  final DeviceCommandApi _api;

  @override
  Future<DeviceCommandRecord> issue({
    required String plantId,
    required DeviceCommandType type,
    RobotLocationType? destination,
    int? seconds,
  }) {
    return _api.issue(
      plantId: plantId,
      type: type,
      destination: destination,
      seconds: seconds,
    );
  }

  @override
  Future<DeviceCommandRecord> startCareRun({
    required String plantId,
    required CareRunPurpose purpose,
  }) {
    return _api.startCareRun(plantId: plantId, purpose: purpose);
  }

  @override
  Future<List<DeviceCommandRecord>> getCommands({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) {
    return _api.getCommands(plantId: plantId, from: from, to: to);
  }

  @override
  Future<void> drive({
    required String plantId,
    required DriveDirection direction,
  }) {
    return _api.drive(plantId: plantId, direction: direction);
  }

  @override
  Future<void> publishExpression({
    required String plantId,
    required PlantExpression expression,
  }) {
    return _api.publishExpression(plantId: plantId, expression: expression);
  }
}
