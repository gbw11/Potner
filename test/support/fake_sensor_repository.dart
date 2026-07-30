import 'package:potner_app/features/sensor/domain/sensor_models.dart';
import 'package:potner_app/features/sensor/domain/sensor_repository.dart';

class FakeSensorRepository implements SensorRepository {
  final List<({SensorKind kind, SensorHistoryInterval interval})>
  historyCalls = [];

  @override
  Future<List<CurrentSensor>> getCurrentSensors(String plantId) async {
    return const [
      CurrentSensor(
        kind: SensorKind.soilMoisture,
        level: SensorLevel.normal,
        value: 56,
      ),
      CurrentSensor(
        kind: SensorKind.illuminance,
        level: SensorLevel.notApplicable,
        value: 1250,
      ),
      CurrentSensor(
        kind: SensorKind.temperature,
        level: SensorLevel.high,
        value: 31.2,
      ),
      CurrentSensor(kind: SensorKind.humidity, level: SensorLevel.noData),
    ];
  }

  @override
  Future<SensorHistorySeries> getHistory({
    required String plantId,
    required SensorKind kind,
    required DateTime from,
    required DateTime to,
    required SensorHistoryInterval interval,
  }) async {
    historyCalls.add((kind: kind, interval: interval));
    final start = DateTime.utc(2026, 7, 29);
    return SensorHistorySeries(
      kind: kind,
      points: [
        for (var i = 0; i < 6; i++)
          SensorHistoryPoint(
            bucketAt: start.add(Duration(hours: i)),
            average: 40 + i * 2,
            minimum: 38 + i * 2,
            maximum: 44 + i * 2,
          ),
      ],
    );
  }

  @override
  Future<DailyLightReport> getDailyLight(String plantId, {int days = 7}) async {
    return DailyLightReport(
      today: const DailyLightToday(progressPct: 64, lightHours: 4.5),
      history: [
        DailyLightDay(
          lightDate: DateTime(2026, 7, 29),
          lightStatus: DailyLightStatus.normal,
          photoperiodStatus: DailyLightStatus.low,
          coveragePct: 98,
          lightHours: 5.5,
        ),
        DailyLightDay(
          lightDate: DateTime(2026, 7, 28),
          lightStatus: DailyLightStatus.insufficientData,
          photoperiodStatus: DailyLightStatus.notApplicable,
        ),
      ],
    );
  }
}
