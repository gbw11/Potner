/// 알림을 발생시킨 판정 지표다. 서버가 모르는 값이 와도 죽지 않도록 unknown 을 둔다.
enum AlertMetric {
  temperature,
  humidity,
  soilMoisture,
  dailyLight,
  photoperiod,
  unknown,
}

/// 적용 기준 범위에서 벗어난 방향이다.
enum AlertDeviation { low, high }

/// 센서 이상 알림 한 건이다.
///
/// 사용자에게 보여줄 문구는 서버가 만들지 않는다. 문구 조립은 앱 책임이며
/// [PlantAlert.message] 가 그 규칙을 담는다.
class PlantAlert {
  const PlantAlert({
    required this.alertId,
    required this.plantId,
    required this.plantName,
    required this.metric,
    required this.deviation,
    required this.occurredAt,
    required this.active,
    required this.read,
    this.measuredValue,
    this.resolvedAt,
  });

  final String alertId;
  final String plantId;
  final String plantName;
  final AlertMetric metric;
  final AlertDeviation deviation;
  final double? measuredValue;
  final DateTime occurredAt;
  final DateTime? resolvedAt;

  /// 아직 해소되지 않은 이상이면 true 다.
  final bool active;
  final bool read;

  String get message {
    final direction = deviation == AlertDeviation.high ? '높습니다' : '낮습니다';
    return switch (metric) {
      AlertMetric.temperature =>
        '$plantName의 온도${_value('℃')}가 너무 $direction.',
      AlertMetric.humidity => '$plantName의 습도${_value('%')}가 너무 $direction.',
      AlertMetric.soilMoisture =>
        '$plantName의 토양 수분${_value('%')}이 너무 $direction.',
      AlertMetric.dailyLight =>
        deviation == AlertDeviation.high
            ? '$plantName의 하루 광량이 목표보다 많았어요.'
            : '$plantName의 하루 광량이 부족했어요.',
      AlertMetric.photoperiod =>
        deviation == AlertDeviation.high
            ? '$plantName의 일조 시간이 목표보다 길었어요.'
            : '$plantName의 일조 시간이 부족했어요.',
      AlertMetric.unknown => '$plantName의 환경을 확인해 주세요.',
    };
  }

  String _value(String unit) {
    final value = measuredValue;
    if (value == null) {
      return '';
    }
    final text = value == value.roundToDouble()
        ? value.round().toString()
        : value.toStringAsFixed(1);
    return '($text$unit)';
  }
}
