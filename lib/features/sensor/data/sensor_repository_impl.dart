import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/sensor/data/sensor_api.dart';
import 'package:potner_app/features/sensor/domain/sensor_models.dart';
import 'package:potner_app/features/sensor/domain/sensor_repository.dart';

final sensorRepositoryProvider = Provider<SensorRepository>((ref) {
  return SensorRepositoryImpl(SensorApi(ref.watch(apiClientProvider).dio));
});

class SensorRepositoryImpl implements SensorRepository {
  SensorRepositoryImpl(this._api);

  final SensorApi _api;

  @override
  Future<List<CurrentSensor>> getCurrentSensors(String plantId) {
    return _api.getCurrentSensors(plantId);
  }

  @override
  Future<SensorHistorySeries> getHistory({
    required String plantId,
    required SensorKind kind,
    required DateTime from,
    required DateTime to,
    required SensorHistoryInterval interval,
  }) {
    return _api.getHistory(
      plantId: plantId,
      kind: kind,
      from: from,
      to: to,
      interval: interval,
    );
  }

  @override
  Future<DailyLightReport> getDailyLight(String plantId, {int days = 7}) {
    return _api.getDailyLight(plantId, days: days);
  }
}
