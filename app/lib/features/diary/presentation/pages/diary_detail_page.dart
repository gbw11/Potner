import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/diary/data/diary_repository_impl.dart';
import 'package:potner_app/features/diary/domain/diary_models.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

final diaryDetailProvider = FutureProvider.autoDispose
    .family<DiaryDetail, ({String plantId, String diaryId})>((ref, arg) {
      return ref
          .read(diaryRepositoryProvider)
          .getDiary(plantId: arg.plantId, diaryId: arg.diaryId);
    });

final statusReportProvider = FutureProvider.autoDispose
    .family<DailyStatusReport, ({String plantId, String dateIso})>((ref, arg) {
      return ref
          .read(diaryRepositoryProvider)
          .getStatusReport(
            plantId: arg.plantId,
            date: DateTime.parse(arg.dateIso),
          );
    });

/// 일기 상세다. 본문과 그날 장치 사진을 함께 보여 준다.
class DiaryDetailPage extends ConsumerWidget {
  const DiaryDetailPage({
    required this.plantId,
    required this.diaryId,
    super.key,
  });

  final String plantId;
  final String diaryId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(
      diaryDetailProvider((plantId: plantId, diaryId: diaryId)),
    );

    return Scaffold(
      appBar: AppBar(
        title: const Text('일기 상세'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('diary_detail_back'),
          onPressed: () =>
              context.go(withCurrentNavigationOrigin(context, '/growth/diary')),
        ),
      ),
      body: SafeArea(
        child: detail.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    plantErrorMessage(error, '일기를 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('diary_detail_retry'),
                    onPressed: () => ref.invalidate(
                      diaryDetailProvider((plantId: plantId, diaryId: diaryId)),
                    ),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (diary) => SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${diary.diaryDate.year}년 ${diary.diaryDate.month}월 '
                      '${diary.diaryDate.day}일',
                      style: const TextStyle(
                        color: AppColors.primaryContainer,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      diary.title,
                      style: Theme.of(context).textTheme.headlineMedium
                          ?.copyWith(color: AppColors.text),
                    ),
                    const SizedBox(height: 18),
                    if (diary.photoUrl != null)
                      ClipRRect(
                        borderRadius: BorderRadius.circular(24),
                        child: Stack(
                          children: [
                            AspectRatio(
                              aspectRatio: 1,
                              child: Image.network(
                                diary.photoUrl!,
                                key: const Key('diary_photo'),
                                fit: BoxFit.cover,
                                errorBuilder: (_, _, _) => const ColoredBox(
                                  color: AppColors.surfaceLow,
                                  child: Center(
                                    child: Icon(
                                      Icons.image_not_supported_outlined,
                                      color: AppColors.textMuted,
                                    ),
                                  ),
                                ),
                              ),
                            ),
                            Positioned(
                              right: 12,
                              bottom: 12,
                              child: Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 6,
                                ),
                                decoration: BoxDecoration(
                                  color: Colors.white.withValues(alpha: 0.9),
                                  borderRadius: BorderRadius.circular(999),
                                ),
                                child: const Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(
                                      Icons.photo_camera_outlined,
                                      size: 16,
                                      color: AppColors.primary,
                                    ),
                                    SizedBox(width: 6),
                                    Text(
                                      "Today's Shot",
                                      style: TextStyle(
                                        fontSize: 12,
                                        fontWeight: FontWeight.w700,
                                        color: AppColors.primary,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ],
                        ),
                      )
                    else
                      Container(
                        padding: const EdgeInsets.all(20),
                        decoration: BoxDecoration(
                          color: AppColors.surfaceLow,
                          borderRadius: BorderRadius.circular(20),
                        ),
                        child: const Row(
                          children: [
                            Icon(
                              Icons.no_photography_outlined,
                              color: AppColors.textMuted,
                            ),
                            SizedBox(width: 10),
                            Text(
                              '이 날은 촬영된 사진이 없어요.',
                              style: TextStyle(color: AppColors.textMuted),
                            ),
                          ],
                        ),
                      ),
                    const SizedBox(height: 22),
                    const Text(
                      '❝',
                      style: TextStyle(
                        fontSize: 30,
                        color: AppColors.primarySoft,
                      ),
                    ),
                    Text(
                      diary.content,
                      style: const TextStyle(fontSize: 16, height: 1.7),
                    ),
                    const SizedBox(height: 26),
                    _StatusReportSection(
                      plantId: plantId,
                      date: diary.diaryDate,
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusReportSection extends ConsumerWidget {
  const _StatusReportSection({required this.plantId, required this.date});

  final String plantId;
  final DateTime date;

  String get _dateIso {
    final month = date.month.toString().padLeft(2, '0');
    final day = date.day.toString().padLeft(2, '0');
    return '${date.year}-$month-$day';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final report = ref.watch(
      statusReportProvider((plantId: plantId, dateIso: _dateIso)),
    );

    return Container(
      key: const Key('diary_status_report'),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.surfaceLow,
        borderRadius: BorderRadius.circular(24),
      ),
      child: report.when(
        loading: () => const Center(
          child: Padding(
            padding: EdgeInsets.all(12),
            child: CircularProgressIndicator(),
          ),
        ),
        // 리포트가 없어도 일기 본문은 읽을 수 있어야 하므로 조용히 안내만 한다.
        error: (_, _) => const Text(
          '상태 리포트를 불러오지 못했어요.',
          style: TextStyle(color: AppColors.textMuted),
        ),
        data: (data) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(
                  Icons.healing_outlined,
                  size: 18,
                  color: AppColors.primary,
                ),
                SizedBox(width: 6),
                Text(
                  '상태 리포트',
                  style: TextStyle(
                    fontWeight: FontWeight.w800,
                    color: AppColors.primary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                _HappinessDonut(score: data.happinessScore),
                const SizedBox(width: 18),
                Expanded(
                  child: Column(
                    children: [
                      _ReportRow(
                        icon: Icons.wb_sunny_outlined,
                        label: '일조량',
                        value: data.lightHours == null
                            ? '집계 전'
                            : '${_trim(data.lightHours!)} hours',
                      ),
                      const SizedBox(height: 10),
                      _ReportRow(
                        icon: Icons.water_drop_outlined,
                        label: '급수량',
                        value: data.wateredMl == null
                            ? '기록 준비 중'
                            : '${_trim(data.wateredMl!)} ml',
                      ),
                    ],
                  ),
                ),
              ],
            ),
            if (data.adjustments.isNotEmpty) ...[
              const SizedBox(height: 14),
              for (final adjustment in data.adjustments)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Row(
                    children: [
                      Icon(
                        adjustment.points >= 0
                            ? Icons.add_circle_outline_rounded
                            : Icons.remove_circle_outline_rounded,
                        size: 16,
                        color: adjustment.points >= 0
                            ? AppColors.primary
                            : AppColors.error,
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          _reasonLabel(adjustment.reason),
                          style: const TextStyle(fontSize: 13),
                        ),
                      ),
                      Text(
                        '${adjustment.points > 0 ? '+' : ''}${adjustment.points}점',
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          color: adjustment.points >= 0
                              ? AppColors.primary
                              : AppColors.error,
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }

  static String _trim(double value) {
    return value == value.roundToDouble()
        ? value.round().toString()
        : value.toStringAsFixed(1);
  }

  static String _reasonLabel(String reason) {
    return switch (reason) {
      'TEMPERATURE_ALERT' => '온도가 알맞지 않았어요',
      'HUMIDITY_ALERT' => '습도가 알맞지 않았어요',
      'SOIL_MOISTURE_ALERT' => '토양 수분이 알맞지 않았어요',
      'DAILY_LIGHT_ALERT' => '하루 광량이 부족하거나 넘쳤어요',
      'PHOTOPERIOD_ALERT' => '일조 시간이 알맞지 않았어요',
      'BLOOMED' => '꽃이 피었어요!',
      _ => reason,
    };
  }
}

class _HappinessDonut extends StatelessWidget {
  const _HappinessDonut({required this.score});

  final int? score;

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: 84,
      child: Stack(
        fit: StackFit.expand,
        children: [
          CircularProgressIndicator(
            value: score == null ? 0 : score!.clamp(0, 100) / 100,
            strokeWidth: 7,
            backgroundColor: AppColors.surface,
            color: AppColors.primary,
          ),
          Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  score == null ? '—' : '$score',
                  style: const TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w800,
                    color: AppColors.primary,
                  ),
                ),
                Text(
                  score == null ? '기록 없음' : '행복 점수',
                  style: const TextStyle(
                    fontSize: 10,
                    color: AppColors.textMuted,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ReportRow extends StatelessWidget {
  const _ReportRow({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          Icon(icon, size: 18, color: AppColors.primary),
          const SizedBox(width: 10),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: const TextStyle(
                  fontSize: 11,
                  color: AppColors.textMuted,
                ),
              ),
              Text(value, style: const TextStyle(fontWeight: FontWeight.w800)),
            ],
          ),
        ],
      ),
    );
  }
}
