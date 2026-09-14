/// 센서 종류다. 서버 SensorType 과 1:1 이다.
enum SensorKind { soilMoisture, illuminance, temperature, humidity }

extension SensorKindWire on SensorKind {
  String get wireName => switch (this) {
    SensorKind.soilMoisture => 'SOIL_MOISTURE',
    SensorKind.illuminance => 'ILLUMINANCE',
    SensorKind.temperature => 'TEMPERATURE',
    SensorKind.humidity => 'HUMIDITY',
  };

  String get label => switch (this) {
    SensorKind.soilMoisture => '토양 수분',
    SensorKind.illuminance => '조도',
    SensorKind.temperature => '온도',
    SensorKind.humidity => '습도',
  };

  String get unitLabel => switch (this) {
    SensorKind.soilMoisture || SensorKind.humidity => '%',
    SensorKind.illuminance => 'lux',
    SensorKind.temperature => '℃',
  };
}

/// 최신값의 상태 판정이다. 조도는 순간값으로 판정하지 않아 NOT_APPLICABLE 이 온다.
enum SensorLevel { low, normal, high, notApplicable, noData, stale, unknown }

class CurrentSensor {
  const CurrentSensor({
    required this.kind,
    required this.level,
    this.value,
    this.measuredAt,
  });

  final SensorKind kind;
  final SensorLevel level;
  final double? value;
  final DateTime? measuredAt;
}

/// 집계 구간 하나다. [bucketAt] 은 구간 시작 시각(UTC)이다.
class SensorHistoryPoint {
  const SensorHistoryPoint({
    required this.bucketAt,
    this.average,
    this.minimum,
    this.maximum,
  });

  final DateTime bucketAt;
  final double? average;
  final double? minimum;
  final double? maximum;
}

class SensorHistorySeries {
  const SensorHistorySeries({required this.kind, required this.points});

  final SensorKind kind;
  final List<SensorHistoryPoint> points;
}

enum DailyLightStatus { low, normal, high, insufficientData, notApplicable }

/// 진행 중인 오늘의 광량 누적값이다. 판정은 마감 후에 나온다.
class DailyLightToday {
  const DailyLightToday({
    this.progressPct,
    this.lightHours,
    this.accumulatedLuxHour,
    this.targetLuxHour,
    this.coveragePct,
    this.sampleCount,
  });

  final double? progressPct;
  final double? lightHours;

  /// 오늘 지금까지 쌓인 빛의 양과 목표다. 진행률만으로는 실제 값을 알 수 없다 —
  /// 목표가 150,000 일 때 '1%' 는 750~2,250 사이 어디든이다.
  final double? accumulatedLuxHour;
  final double? targetLuxHour;

  /// 하루 중 표본이 실제로 덮은 시간의 비율이다. 80% 미만이면 판정을 포기한다.
  ///
  /// 측정을 종일 했어도 간격이 벌어지면 이 값이 낮다. 표본 하나가 대표할 수 있는
  /// 시간에 상한이 있어서, 1시간마다 보내면 그중 10분치만 인정된다.
  final double? coveragePct;

  /// 오늘 저장된 조도 측정값 개수다. 커버리지와 함께 봐야 '값이 없는 것'과
  /// '간격이 벌어진 것'을 구분할 수 있다.
  final int? sampleCount;
}

/// 확정된 하루의 광량 판정이다.
class DailyLightDay {
  const DailyLightDay({
    required this.lightDate,
    required this.lightStatus,
    required this.photoperiodStatus,
    this.coveragePct,
    this.lightHours,
  });

  final DateTime lightDate;
  final DailyLightStatus lightStatus;
  final DailyLightStatus photoperiodStatus;
  final double? coveragePct;
  final double? lightHours;
}

class DailyLightReport {
  const DailyLightReport({required this.history, this.today});

  final DailyLightToday? today;
  final List<DailyLightDay> history;
}
