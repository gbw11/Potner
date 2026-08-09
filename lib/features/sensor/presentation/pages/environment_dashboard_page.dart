import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/format/elapsed_label.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/plant/domain/plant_detail.dart';
import 'package:potner_app/features/plant/presentation/pages/care_settings_page.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';
import 'package:potner_app/features/sensor/data/sensor_repository_impl.dart';
import 'package:potner_app/features/sensor/domain/sensor_models.dart';
import 'package:potner_app/features/sensor/domain/sensor_repository.dart';

final currentSensorsProvider = FutureProvider.autoDispose
    .family<List<CurrentSensor>, String>((ref, plantId) {
      return ref.watch(sensorRepositoryProvider).getCurrentSensors(plantId);
    });

/// 이력 조회 범위다. 시간 단위는 서버가 14일까지만 허용해 하루 범위에만 쓴다.
enum HistoryRange { day, week, month }

extension HistoryRangeSpec on HistoryRange {
  String get label => switch (this) {
    HistoryRange.day => '24시간',
    HistoryRange.week => '7일',
    HistoryRange.month => '30일',
  };

  Duration get duration => switch (this) {
    HistoryRange.day => const Duration(hours: 24),
    HistoryRange.week => const Duration(days: 7),
    HistoryRange.month => const Duration(days: 30),
  };

  SensorHistoryInterval get interval => switch (this) {
    HistoryRange.day => SensorHistoryInterval.hour,
    HistoryRange.week || HistoryRange.month => SensorHistoryInterval.day,
  };
}

final sensorHistoryProvider = FutureProvider.autoDispose
    .family<
      SensorHistorySeries,
      ({String plantId, SensorKind kind, HistoryRange range})
    >((ref, arg) {
      final now = DateTime.now().toUtc();
      return ref
          .watch(sensorRepositoryProvider)
          .getHistory(
            plantId: arg.plantId,
            kind: arg.kind,
            from: now.subtract(arg.range.duration),
            to: now,
            interval: arg.range.interval,
          );
    });

final dailyLightProvider = FutureProvider.autoDispose
    .family<DailyLightReport, String>((ref, plantId) {
      return ref.watch(sensorRepositoryProvider).getDailyLight(plantId);
    });

/// 환경 정보 대시보드다. 현재 환경, 센서 이력 그래프, 하루 광량 판정을 한 화면에 모은다.
class EnvironmentDashboardPage extends ConsumerStatefulWidget {
  const EnvironmentDashboardPage({required this.plantId, super.key});

  final String plantId;

  @override
  ConsumerState<EnvironmentDashboardPage> createState() =>
      _EnvironmentDashboardPageState();
}

class _EnvironmentDashboardPageState
    extends ConsumerState<EnvironmentDashboardPage> {
  SensorKind _kind = SensorKind.soilMoisture;
  HistoryRange _range = HistoryRange.day;

  @override
  Widget build(BuildContext context) {
    final current = ref.watch(currentSensorsProvider(widget.plantId));
    final history = ref.watch(
      sensorHistoryProvider((
        plantId: widget.plantId,
        kind: _kind,
        range: _range,
      )),
    );
    final dailyLight = ref.watch(dailyLightProvider(widget.plantId));
    // 그래프에 적정 범위를 깔기 위해 케어 설정 값을 함께 읽는다. 같은 provider 를 케어
    // 설정 화면이 쓰고 있어 한쪽에서 저장하면 이 화면의 기준선도 따라 움직인다.
    // 아직 안 왔거나 실패했으면 null 이다. 그래프는 기준선 없이 그린다.
    final targetRange = ref
        .watch(growthProfileProvider(widget.plantId))
        .asData
        ?.value
        .rangeFor(_kind);

    return Scaffold(
      appBar: AppBar(
        title: const Text('환경 정보'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('environment_back'),
          onPressed: () => returnFromSharedPage(
            context,
            fallbackLocation: '/plants/${widget.plantId}',
          ),
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const _SectionTitle(icon: Icons.eco_outlined, title: '현재 환경'),
                  current.when(
                    loading: () => const Padding(
                      padding: EdgeInsets.symmetric(vertical: 30),
                      child: Center(child: CircularProgressIndicator()),
                    ),
                    error: (error, _) => _InlineError(
                      message: plantErrorMessage(error, '현재 환경을 불러오지 못했습니다.'),
                      retryKey: const Key('environment_current_retry'),
                      onRetry: () => ref.invalidate(
                        currentSensorsProvider(widget.plantId),
                      ),
                    ),
                    data: (sensors) => GridView.count(
                      crossAxisCount: 2,
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      mainAxisSpacing: 12,
                      crossAxisSpacing: 12,
                      // 카드 안은 라벨·값·측정 시각 세 줄이다. 높이가 남으면 값 아래가
                      // 허옇게 비어 카드가 반쯤 빈 것처럼 보인다. 세 줄에 맞춰 조인다.
                      //
                      // 더 올리지 않는 이유는 글꼴 배율 때문이다. 배율을 키우면 세 줄이
                      // 그만큼 두꺼워지는데 타일 높이는 배율을 따라가지 않아 넘친다.
                      childAspectRatio: 1.68,
                      children: [
                        for (final sensor in sensors)
                          _CurrentSensorCard(sensor: sensor),
                      ],
                    ),
                  ),
                  const SizedBox(height: 26),
                  const _SectionTitle(
                    icon: Icons.show_chart_rounded,
                    title: '센서 이력',
                  ),
                  Wrap(
                    spacing: 8,
                    children: [
                      for (final kind in SensorKind.values)
                        ChoiceChip(
                          key: Key('history_kind_${kind.name}'),
                          label: Text(kind.label),
                          selected: _kind == kind,
                          onSelected: (_) => setState(() => _kind = kind),
                        ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    children: [
                      for (final range in HistoryRange.values)
                        ChoiceChip(
                          key: Key('history_range_${range.name}'),
                          label: Text(range.label),
                          selected: _range == range,
                          onSelected: (_) => setState(() => _range = range),
                        ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  Container(
                    height: 220,
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: AppColors.surface,
                      borderRadius: BorderRadius.circular(22),
                      boxShadow: [
                        BoxShadow(
                          color: AppColors.primary.withValues(alpha: 0.07),
                          blurRadius: 14,
                          offset: const Offset(0, 4),
                        ),
                      ],
                    ),
                    child: history.when(
                      loading: () =>
                          const Center(child: CircularProgressIndicator()),
                      error: (error, _) => _InlineError(
                        message: plantErrorMessage(error, '이력을 불러오지 못했습니다.'),
                        retryKey: const Key('environment_history_retry'),
                        onRetry: () => ref.invalidate(
                          sensorHistoryProvider((
                            plantId: widget.plantId,
                            kind: _kind,
                            range: _range,
                          )),
                        ),
                      ),
                      data: (series) => _HistoryChart(
                        key: const Key('sensor_history_chart'),
                        series: series,
                        range: _range,
                        // 케어 설정에 저장된 이 식물의 적정 범위다. 못 불러와도 그래프는
                        // 그린다. 기준선이 없으면 값만 보일 뿐 화면이 비지는 않는다.
                        target: targetRange,
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  _ChartLegend(hasTarget: targetRange != null),
                  const SizedBox(height: 26),
                  const _SectionTitle(
                    icon: Icons.wb_sunny_outlined,
                    title: '하루 광량',
                  ),
                  dailyLight.when(
                    loading: () => const Padding(
                      padding: EdgeInsets.symmetric(vertical: 30),
                      child: Center(child: CircularProgressIndicator()),
                    ),
                    error: (error, _) => _InlineError(
                      message: plantErrorMessage(error, '광량 정보를 불러오지 못했습니다.'),
                      retryKey: const Key('environment_light_retry'),
                      onRetry: () =>
                          ref.invalidate(dailyLightProvider(widget.plantId)),
                    ),
                    data: (report) => _DailyLightSection(report: report),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.icon, required this.title});

  final IconData icon;
  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          Icon(icon, size: 18, color: AppColors.primary),
          const SizedBox(width: 6),
          Text(
            title,
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w800,
              color: AppColors.text,
            ),
          ),
        ],
      ),
    );
  }
}

class _CurrentSensorCard extends StatelessWidget {
  const _CurrentSensorCard({required this.sensor});

  final CurrentSensor sensor;

  /// '오래됨' 대신 얼마나 지났는지를 쓴다.
  ///
  /// '오래됨' 은 얼마나 오래됐는지를 말해주지 않아 사용자가 판단할 수 없다. 5분 전이라
  /// 기다리면 되는 상황인지, 이틀째 끊긴 상황인지가 갈리는데 문구가 같았다. 홈이 이미
  /// 경과 시간으로 쓰고 있어 같은 함수를 쓴다.
  (String, Color) get _statusChip => switch (sensor.level) {
    SensorLevel.low => ('낮음', AppColors.error),
    SensorLevel.high => ('높음', AppColors.error),
    SensorLevel.normal => ('정상', const Color(0xFF2E7D32)),
    SensorLevel.stale => (
      elapsedChipLabel(sensor.measuredAt),
      const Color(0xFFB07E09),
    ),
    SensorLevel.noData => ('측정 전', AppColors.textMuted),
    SensorLevel.notApplicable ||
    SensorLevel.unknown => ('—', AppColors.textMuted),
  };

  @override
  Widget build(BuildContext context) {
    final (statusLabel, statusColor) = _statusChip;
    final value = sensor.value;
    return Container(
      key: Key('current_${sensor.kind.name}'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: AppColors.primary.withValues(alpha: 0.07),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  sensor.kind.label,
                  style: const TextStyle(
                    fontSize: 13,
                    color: AppColors.textMuted,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              Text(
                statusLabel,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w800,
                  color: statusColor,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          // 단위를 숫자 옆에 붙인다. 아래에 따로 두면 '55' 와 '%' 가 두 줄로 갈려
          // 한 값이 아니라 두 정보처럼 읽힌다.
          //
          // Row 로 두 Text 를 나란히 두고 베이스라인을 맞춘다. 크기가 달라도 글자 밑선이
          // 같아 '55%' 한 덩어리로 보인다.
          Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(
                value == null ? '—' : _trimNumber(value),
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  color: AppColors.text,
                ),
              ),
              // 값이 없으면 단위도 뜻이 없다. '— %' 는 측정값처럼 보인다.
              if (value != null) ...[
                const SizedBox(width: 2),
                Text(
                  sensor.kind.unitLabel,
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: AppColors.textMuted,
                  ),
                ),
              ],
            ],
          ),
          // 언제 잰 값인지 밝힌다. 상태 칩만으로는 '지금 정상' 인지 '세 시간 전에
          // 정상이었는지' 를 구분할 수 없다.
          //
          // 값이 끊긴 상태(stale)면 칩이 이미 같은 문구를 쓰고 있어 여기서는 뺀다.
          // 넣으면 한 카드에 '1일 전' 이 두 번 나온다.
          if (sensor.measuredAt != null && sensor.level != SensorLevel.stale)
            Text(
              elapsedChipLabel(sensor.measuredAt),
              style: const TextStyle(fontSize: 11, color: AppColors.textMuted),
            ),
        ],
      ),
    );
  }
}

String _trimNumber(double value) {
  return value == value.roundToDouble()
      ? value.round().toString()
      : value.toStringAsFixed(1);
}

/// 천 단위로 끊는다. lux·h 는 자릿수가 커서 끊지 않으면 읽기 어렵다.
String _thousands(double value) {
  final digits = value.round().toString();
  final buffer = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) {
      buffer.write(',');
    }
    buffer.write(digits[i]);
  }
  return buffer.toString();
}

/// 적정 범위다. 케어 설정에 저장된 이 식물의 기준을 그래프에 깔기 위한 것이다.
class TargetRange {
  const TargetRange({required this.min, required this.max});

  final double min;
  final double max;
}

/// 센서 종류별 적정 범위를 꺼낸다. 한쪽만 있으면 밴드를 그릴 수 없어 null 이다.
extension CareTargetRange on GrowthProfile {
  TargetRange? rangeFor(SensorKind kind) {
    final (min, max) = switch (kind) {
      SensorKind.soilMoisture => (soilMoistureMinPct, soilMoistureMaxPct),
      SensorKind.temperature => (temperatureMinC, temperatureMaxC),
      SensorKind.humidity => (humidityMinPct, humidityMaxPct),
      SensorKind.illuminance => (illuminanceMinLux, illuminanceMaxLux),
    };
    if (min == null || max == null || max <= min) {
      return null;
    }
    return TargetRange(min: min, max: max);
  }
}

class _HistoryChart extends StatelessWidget {
  const _HistoryChart({
    required this.series,
    required this.range,
    this.target,
    super.key,
  });

  final SensorHistorySeries series;
  final HistoryRange range;
  final TargetRange? target;

  @override
  Widget build(BuildContext context) {
    final points = series.points
        .where((point) => point.average != null)
        .toList(growable: false);
    if (points.length < 2) {
      return const Center(
        child: Text(
          '아직 그래프를 그릴 데이터가 부족해요.',
          style: TextStyle(color: AppColors.textMuted),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(
          child: CustomPaint(
            painter: _LineChartPainter(
              points: points,
              unit: series.kind.unitLabel,
              target: target,
            ),
          ),
        ),
        const SizedBox(height: 6),
        // 가운데 시각을 하나 더 찍는다. 양 끝만 있으면 그래프의 어느 지점이 언제인지
        // 눈으로 짚을 수가 없다.
        Padding(
          padding: const EdgeInsets.only(left: 42),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              _AxisLabel(text: _timeLabel(points.first.bucketAt)),
              _AxisLabel(
                text: _timeLabel(points[points.length ~/ 2].bucketAt),
              ),
              _AxisLabel(text: _timeLabel(points.last.bucketAt)),
            ],
          ),
        ),
      ],
    );
  }

  String _timeLabel(DateTime utc) {
    final local = utc.toLocal();
    if (range == HistoryRange.day) {
      return '${local.hour.toString().padLeft(2, '0')}:00';
    }
    return '${local.month}/${local.day}';
  }
}

class _AxisLabel extends StatelessWidget {
  const _AxisLabel({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: const TextStyle(fontSize: 11, color: AppColors.textMuted),
    );
  }
}

/// 그래프의 선과 띠가 각각 무엇인지 적는다.
///
/// 색만 다른 두 개의 띠를 설명 없이 두면 무엇이 평균이고 무엇이 기준인지 알 수 없다.
///
/// 색은 [_LineChartPainter] 의 상수를 그대로 쓴다. 범례와 그래프가 다른 색이면 범례가
/// 오히려 방해가 된다.
class _ChartLegend extends StatelessWidget {
  const _ChartLegend({required this.hasTarget});

  final bool hasTarget;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 14,
      runSpacing: 6,
      children: [
        const _LegendItem(color: AppColors.primary, label: '평균', isLine: true),
        const _LegendItem(
          color: _LineChartPainter._spreadFill,
          label: '그 시간의 최저~최고',
        ),
        if (hasTarget)
          const _LegendItem(
            color: _LineChartPainter._targetFill,
            borderColor: _LineChartPainter._targetEdge,
            label: '적정 범위',
          )
        else
          const _LegendItem(
            color: Colors.transparent,
            label: '적정 범위는 케어 설정에서 정해져요',
          ),
      ],
    );
  }
}

class _LegendItem extends StatelessWidget {
  const _LegendItem({
    required this.color,
    required this.label,
    this.borderColor,
    this.isLine = false,
  });

  final Color color;
  final String label;

  /// 그래프에서 테두리를 두른 띠는 범례에도 두른다. 적정 범위가 그렇다.
  final Color? borderColor;
  final bool isLine;

  @override
  Widget build(BuildContext context) {
    final borderColor = this.borderColor;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 14,
          height: isLine ? 3 : 10,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(isLine ? 2 : 3),
            border: borderColor == null
                ? null
                : Border.all(color: borderColor, width: 1),
          ),
        ),
        const SizedBox(width: 5),
        Text(
          label,
          style: const TextStyle(fontSize: 11, color: AppColors.textMuted),
        ),
      ],
    );
  }
}

/// 평균값 꺾은선과 최소·최대 밴드를 그린다. 외부 차트 패키지 없이 충분한 수준만 담는다.
///
/// <p>왼쪽에 눈금을 세 개 찍고 단위를 붙인다. 숫자만 두 개 있던 예전 화면은 그 값이 무엇의
/// 몇인지 알 수 없었다. 적정 범위를 초록 띠로 깔아 지금 값이 기준 안인지 밖인지를 선의
/// 위치만으로 읽게 한다. 사용자가 그래프를 보고 판단할 수 있으려면 비교 대상이 필요하다.
class _LineChartPainter extends CustomPainter {
  _LineChartPainter({
    required this.points,
    required this.unit,
    this.target,
  });

  final List<SensorHistoryPoint> points;
  final String unit;
  final TargetRange? target;

  /// 왼쪽 눈금 라벨이 차지하는 폭이다. 이만큼 비우고 그려야 선과 글자가 겹치지 않는다.
  static const double _axisWidth = 42;

  /// 적정 범위 띠의 색이다. 케어 설정이 정한 **기준**을 뜻한다.
  ///
  /// 측정값(회색 계열)과 색 계열을 나눈다. 예전에는 밴드도 초록이라 어느 쪽이 기준이고
  /// 어느 쪽이 실제 값인지 색만 보고는 갈리지 않았다.
  static const Color _targetFill = Color(0x2E4CAF50);
  static const Color _targetEdge = Color(0x8C2E7D32);

  /// 그 시간의 최저~최고 밴드다. 판정이 아니라 **측정값의 폭**이라 중립색을 쓴다.
  static const Color _spreadFill = Color(0x385F675C);

  @override
  void paint(Canvas canvas, Size size) {
    final values = <double>[
      for (final point in points) ...[
        point.average!,
        if (point.minimum != null) point.minimum!,
        if (point.maximum != null) point.maximum!,
      ],
      // 적정 범위도 눈금 계산에 넣는다. 넣지 않으면 측정값이 기준을 크게 벗어난 날
      // 초록 띠가 화면 밖으로 밀려 '기준이 어디인지' 가 안 보인다.
      if (target != null) ...[target!.min, target!.max],
    ];
    var minValue = values.reduce((a, b) => a < b ? a : b);
    var maxValue = values.reduce((a, b) => a > b ? a : b);
    if (maxValue - minValue < 1e-6) {
      // 값이 전부 같으면 위아래 여유를 줘서 가운데 선으로 그린다.
      minValue -= 1;
      maxValue += 1;
    }

    final chartWidth = size.width - _axisWidth;
    double x(int index) =>
        _axisWidth + chartWidth * index / (points.length - 1);
    double y(double value) =>
        size.height * (1 - (value - minValue) / (maxValue - minValue));

    // 적정 범위 띠. 가장 먼저 그려 다른 선들 아래에 깔린다.
    final targetRange = target;
    if (targetRange != null) {
      final top = y(targetRange.max);
      final bottom = y(targetRange.min);
      canvas.drawRect(
        Rect.fromLTRB(_axisWidth, top, size.width, bottom),
        Paint()..color = _targetFill,
      );
      // 위아래 경계선을 긋는다. 채움만으로는 최저~최고 밴드가 겹쳐 깔렸을 때 기준이
      // 어디서 끝나는지 보이지 않는다.
      final edge = Paint()
        ..color = _targetEdge
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.2;
      canvas.drawLine(Offset(_axisWidth, top), Offset(size.width, top), edge);
      canvas.drawLine(
        Offset(_axisWidth, bottom),
        Offset(size.width, bottom),
        edge,
      );
    }

    // 가로 눈금선 세 개와 왼쪽 값 라벨
    for (var i = 0; i < 3; i++) {
      final value = maxValue - (maxValue - minValue) * i / 2;
      final lineY = y(value);
      canvas.drawLine(
        Offset(_axisWidth, lineY),
        Offset(size.width, lineY),
        Paint()
          ..color = AppColors.textMuted.withValues(alpha: 0.18)
          ..strokeWidth = 1,
      );
      _paintLabel(
        canvas,
        '${_trimNumber(value)}$unit',
        Offset(0, lineY - 6),
        maxWidth: _axisWidth - 6,
      );
    }

    // 최소~최대 밴드
    final bandPath = Path();
    var hasBand = false;
    for (var i = 0; i < points.length; i++) {
      final max = points[i].maximum;
      if (max == null) {
        continue;
      }
      if (!hasBand) {
        bandPath.moveTo(x(i), y(max));
        hasBand = true;
      } else {
        bandPath.lineTo(x(i), y(max));
      }
    }
    for (var i = points.length - 1; i >= 0; i--) {
      final min = points[i].minimum;
      if (min == null) {
        continue;
      }
      bandPath.lineTo(x(i), y(min));
    }
    if (hasBand) {
      bandPath.close();
      canvas.drawPath(bandPath, Paint()..color = _spreadFill);
    }

    // 평균 꺾은선
    final linePath = Path()..moveTo(x(0), y(points.first.average!));
    for (var i = 1; i < points.length; i++) {
      linePath.lineTo(x(i), y(points[i].average!));
    }
    canvas.drawPath(
      linePath,
      Paint()
        ..color = AppColors.primary
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.4
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round,
    );

  }

  /// 눈금 라벨을 그린다. 오른쪽 끝을 축 폭에 맞춰 숫자 자릿수가 달라도 열이 흐트러지지 않는다.
  void _paintLabel(
    Canvas canvas,
    String text,
    Offset offset, {
    required double maxWidth,
  }) {
    final painter = TextPainter(
      text: TextSpan(
        text: text,
        style: const TextStyle(fontSize: 10, color: AppColors.textMuted),
      ),
      textDirection: TextDirection.ltr,
      maxLines: 1,
      ellipsis: '…',
    )..layout(maxWidth: maxWidth);
    painter.paint(canvas, Offset(maxWidth - painter.width, offset.dy));
  }

  @override
  bool shouldRepaint(covariant _LineChartPainter oldDelegate) {
    return oldDelegate.points != points ||
        oldDelegate.unit != unit ||
        oldDelegate.target?.min != target?.min ||
        oldDelegate.target?.max != target?.max;
  }
}

class _DailyLightSection extends StatelessWidget {
  const _DailyLightSection({required this.report});

  final DailyLightReport report;

  static String _statusLabel(DailyLightStatus status) {
    return switch (status) {
      DailyLightStatus.low => '부족',
      DailyLightStatus.normal => '적정',
      DailyLightStatus.high => '과다',
      DailyLightStatus.insufficientData => '표본 부족',
      DailyLightStatus.notApplicable => '기준 없음',
    };
  }

  static Color _statusColor(DailyLightStatus status) {
    return switch (status) {
      DailyLightStatus.low || DailyLightStatus.high => AppColors.error,
      DailyLightStatus.normal => const Color(0xFF2E7D32),
      _ => AppColors.textMuted,
    };
  }

  @override
  Widget build(BuildContext context) {
    final today = report.today;
    final progress = today?.progressPct;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          key: const Key('daily_light_today'),
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(22),
            boxShadow: [
              BoxShadow(
                color: AppColors.primary.withValues(alpha: 0.07),
                blurRadius: 14,
                offset: const Offset(0, 4),
              ),
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      '오늘의 광량 진행률',
                      style: TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ),
                  Text(
                    progress == null ? '집계 전' : '${progress.round()}%',
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      color: AppColors.primary,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              ClipRRect(
                borderRadius: BorderRadius.circular(999),
                child: LinearProgressIndicator(
                  value: progress == null
                      ? null
                      : (progress / 100).clamp(0.0, 1.0),
                  minHeight: 10,
                  backgroundColor: AppColors.surfaceLow,
                  color: AppColors.primary,
                ),
              ),
              // 진행률만으로는 실제 값을 알 수 없다. 목표가 150,000 일 때 '1%' 는
              // 750~2,250 사이 어디든이라, 케어 설정에서 목표를 잡을 근거가 안 된다.
              if (today?.accumulatedLuxHour != null &&
                  today?.targetLuxHour != null) ...[
                const SizedBox(height: 8),
                Text(
                  '${_thousands(today!.accumulatedLuxHour!)}'
                  ' / ${_thousands(today.targetLuxHour!)} lux·h',
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: AppColors.text,
                  ),
                ),
              ],
              if (today?.lightHours != null) ...[
                const SizedBox(height: 6),
                Text(
                  '오늘 일조 시간 ${_trimNumber(today!.lightHours!)}시간',
                  style: const TextStyle(
                    fontSize: 12,
                    color: AppColors.textMuted,
                  ),
                ),
              ],
              // 판정이 '표본 부족' 으로 빠졌을 때 이유를 여기서 읽는다. 측정을 종일
              // 했어도 간격이 벌어지면 커버리지가 낮다 — 값이 없는 것과 다른 상황이다.
              if (today?.coveragePct != null || today?.sampleCount != null) ...[
                const SizedBox(height: 6),
                Text(
                  [
                    if (today?.sampleCount != null) '표본 ${today!.sampleCount}건',
                    if (today?.coveragePct != null)
                      '커버리지 ${today!.coveragePct!.round()}%',
                  ].join(' · '),
                  style: const TextStyle(
                    fontSize: 12,
                    color: AppColors.textMuted,
                  ),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 12),
        if (report.history.isEmpty)
          const Padding(
            padding: EdgeInsets.all(12),
            child: Text(
              '아직 확정된 하루 광량 판정이 없어요.',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.textMuted),
            ),
          )
        else
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(22),
              boxShadow: [
                BoxShadow(
                  color: AppColors.primary.withValues(alpha: 0.07),
                  blurRadius: 14,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Column(
              children: [
                for (final day in report.history)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Row(
                      children: [
                        Expanded(
                          child: Text(
                            '${day.lightDate.month}/${day.lightDate.day}',
                            style: const TextStyle(fontWeight: FontWeight.w700),
                          ),
                        ),
                        _StatusChip(
                          label: '광량 ${_statusLabel(day.lightStatus)}',
                          color: _statusColor(day.lightStatus),
                        ),
                        const SizedBox(width: 8),
                        _StatusChip(
                          label: '일조 ${_statusLabel(day.photoperiodStatus)}',
                          color: _statusColor(day.photoperiodStatus),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w700,
          color: color,
        ),
      ),
    );
  }
}

class _InlineError extends StatelessWidget {
  const _InlineError({
    required this.message,
    required this.retryKey,
    required this.onRetry,
  });

  final String message;
  final Key retryKey;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textMuted),
            ),
            const SizedBox(height: 10),
            FilledButton.tonal(
              key: retryKey,
              style: FilledButton.styleFrom(minimumSize: const Size(120, 40)),
              onPressed: onRetry,
              child: const Text('다시 시도'),
            ),
          ],
        ),
      ),
    );
  }
}
