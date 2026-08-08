import 'package:potner_app/features/sensor/domain/sensor_models.dart';
import 'package:potner_app/features/sensor/domain/sensor_repository.dart';

class FakeSensorRepository implements SensorRepository {
  final List<({SensorKind kind, SensorHistoryInterval interval})>
  historyCalls = [];

  /// 지정하면 기본 목록 대신 이 값을 돌려준다. 값이 끊긴 화면을 만들 때 쓴다.
  List<CurrentSensor>? currentSensorsOverride;

  @override
  Future<List<CurrentSensor>> getCurrentSensors(String plantId) async {
    final override = currentSensorsOverride;
    if (override != null) {
      return override;
    }
    // 측정 시각은 호출 시점 기준 상대값이다. 고정 시각을 쓰면 '몇 시간 전' 문구가
    // 테스트를 돌리는 날짜에 따라 달라진다.
    final now = DateTime.now().toUtc();
    return [
      CurrentSensor(
        kind: SensorKind.soilMoisture,
        level: SensorLevel.normal,
        value: 56,
        measuredAt: now.subtract(const Duration(minutes: 4)),
      ),
      const CurrentSensor(
        kind: SensorKind.illuminance,
        level: SensorLevel.notApplicable,
        value: 1250,
      ),
      CurrentSensor(
        kind: SensorKind.temperature,
        level: SensorLevel.high,
        value: 31.2,
        measuredAt: now.subtract(const Duration(minutes: 4)),
      ),
      const CurrentSensor(kind: SensorKind.humidity, level: SensorLevel.noData),
    ];
  }

  /// 값이 끊긴 상태다. '오래됨' 대신 얼마나 지났는지를 보여주는지 확인하는 데 쓴다.
  static List<CurrentSensor> staleSensors() {
    final now = DateTime.now().toUtc();
    return [
      CurrentSensor(
        kind: SensorKind.soilMoisture,
        level: SensorLevel.stale,
        value: 41,
        measuredAt: now.subtract(const Duration(hours: 3)),
      ),
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
      today: const DailyLightToday(
        progressPct: 64,
        lightHours: 4.5,
        accumulatedLuxHour: 96000,
        targetLuxHour: 150000,
        coveragePct: 86,
        sampleCount: 1240,
      ),
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
