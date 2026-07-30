import 'package:potner_app/features/sensor/domain/sensor_models.dart';

/// 센서 이력의 집계 단위다. HOUR 는 최대 14일, DAY 는 최대 365일까지 조회할 수 있다.
enum SensorHistoryInterval { hour, day }

abstract class SensorRepository {
  Future<List<CurrentSensor>> getCurrentSensors(String plantId);

  Future<SensorHistorySeries> getHistory({
    required String plantId,
    required SensorKind kind,
    required DateTime from,
    required DateTime to,
    required SensorHistoryInterval interval,
  });

  Future<DailyLightReport> getDailyLight(String plantId, {int days = 7});
}
