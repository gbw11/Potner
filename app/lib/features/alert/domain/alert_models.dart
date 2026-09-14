/// 알림을 발생시킨 판정 지표다. 서버가 모르는 값이 와도 죽지 않도록 unknown 을 둔다.
///
/// 서버 `AlertMetricType` 과 짝을 맞춘다. 여기 없는 지표는 unknown 으로 떨어져 "환경을
/// 확인해 주세요" 라는 뭉뚱그린 문구가 되므로, 서버가 지표를 늘리면 이 목록도 같이 늘려야
/// 한다 — unknown 은 앱이 죽지 않게 하는 장치일 뿐 표시할 문구로 쓸 것이 아니다.
enum AlertMetric {
  temperature,
  humidity,
  soilMoisture,
  dailyLight,
  photoperiod,
  stationWaterLow,
  drainageTray,
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
      // 아래 둘은 방향이 하나뿐이라 deviation 을 보지 않는다. 물 부족은 부족일 때만,
      // 배수트레이는 찼을 때만 열린다 — 서버가 그 반대로는 알림을 만들지 않는다.
      // 측정값도 없다(불리언 보고라 저장할 수치가 없다). _value 를 쓰지 않는 이유다.
      AlertMetric.stationWaterLow =>
        '스테이션에 물이 부족해요. $plantName의 급수를 위해 물을 보충해 주세요.',
      AlertMetric.drainageTray =>
        '$plantName의 배수트레이에 물이 찼어요. 넘치지 않도록 비워 주세요.',
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
