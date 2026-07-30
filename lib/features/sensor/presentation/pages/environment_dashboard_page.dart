import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';
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

    return Scaffold(
      appBar: AppBar(
        title: const Text('환경 정보'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('environment_back'),
          onPressed: () => context.canPop()
              ? context.pop()
              : context.go('/plants/${widget.plantId}'),
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
                      childAspectRatio: 1.55,
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
                      ),
                    ),
                  ),
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

  (String, Color) get _statusChip => switch (sensor.level) {
    SensorLevel.low => ('낮음', AppColors.error),
    SensorLevel.high => ('높음', AppColors.error),
    SensorLevel.normal => ('정상', const Color(0xFF2E7D32)),
    SensorLevel.stale => ('오래됨', const Color(0xFFB07E09)),
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
          const Spacer(),
          Text(
            value == null ? '—' : _trimNumber(value),
            style: const TextStyle(
              fontSize: 24,
              fontWeight: FontWeight.w800,
              color: AppColors.text,
            ),
          ),
          Text(
            sensor.kind.unitLabel,
            style: const TextStyle(fontSize: 12, color: AppColors.textMuted),
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

class _HistoryChart extends StatelessWidget {
  const _HistoryChart({required this.series, required this.range, super.key});

  final SensorHistorySeries series;
  final HistoryRange range;

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
          child: CustomPaint(painter: _LineChartPainter(points: points)),
        ),
        const SizedBox(height: 6),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              _timeLabel(points.first.bucketAt),
              style: const TextStyle(fontSize: 11, color: AppColors.textMuted),
            ),
            Text(
              _timeLabel(points.last.bucketAt),
              style: const TextStyle(fontSize: 11, color: AppColors.textMuted),
            ),
          ],
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

/// 평균값 꺾은선과 최소·최대 밴드를 그린다. 외부 차트 패키지 없이 충분한 수준만 담는다.
class _LineChartPainter extends CustomPainter {
  _LineChartPainter({required this.points});

  final List<SensorHistoryPoint> points;

  @override
  void paint(Canvas canvas, Size size) {
    final values = <double>[
      for (final point in points) ...[
        point.average!,
        if (point.minimum != null) point.minimum!,
        if (point.maximum != null) point.maximum!,
      ],
    ];
    var minValue = values.reduce((a, b) => a < b ? a : b);
    var maxValue = values.reduce((a, b) => a > b ? a : b);
    if (maxValue - minValue < 1e-6) {
      // 값이 전부 같으면 위아래 여유를 줘서 가운데 선으로 그린다.
      minValue -= 1;
      maxValue += 1;
    }

    double x(int index) => size.width * index / (points.length - 1);
    double y(double value) =>
        size.height * (1 - (value - minValue) / (maxValue - minValue));

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
      canvas.drawPath(
        bandPath,
        Paint()..color = AppColors.primarySoft.withValues(alpha: 0.35),
      );
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

    // 축 라벨(최소·최대)
    _paintLabel(canvas, _trimNumber(maxValue), Offset(0, 0));
    _paintLabel(
      canvas,
      _trimNumber(minValue),
      Offset(0, size.height - 12),
    );
  }

  void _paintLabel(Canvas canvas, String text, Offset offset) {
    final painter = TextPainter(
      text: TextSpan(
        text: text,
        style: const TextStyle(fontSize: 10, color: AppColors.textMuted),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(canvas, offset);
  }

  @override
  bool shouldRepaint(covariant _LineChartPainter oldDelegate) {
    return oldDelegate.points != points;
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
              if (today?.lightHours != null) ...[
                const SizedBox(height: 8),
                Text(
                  '오늘 일조 시간 ${_trimNumber(today!.lightHours!)}시간',
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
                            style: const TextStyle(
                              fontWeight: FontWeight.w700,
                            ),
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
