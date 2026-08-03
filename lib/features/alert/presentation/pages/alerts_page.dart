import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/navigation/app_menu_button.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/alert/data/alert_repository_impl.dart';
import 'package:potner_app/features/alert/domain/alert_models.dart';
import 'package:potner_app/features/bloom/data/bloom_repository_impl.dart';
import 'package:potner_app/features/bloom/domain/bloom_models.dart';
import 'package:potner_app/features/bloom/presentation/pages/bloom_list_page.dart';
import 'package:potner_app/features/home/presentation/controllers/home_controller.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

final alertsProvider = FutureProvider.autoDispose<List<PlantAlert>>((ref) {
  return ref.watch(alertRepositoryProvider).getAlerts();
});

/// 알림 화면이다. 진행 중인 이상, 개화 소식, 지나간 알림 이력을 한눈에 보여 준다.
class AlertsPage extends ConsumerWidget {
  const AlertsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final alerts = ref.watch(alertsProvider);
    final blooms = ref.watch(bloomsProvider);
    final origin = AppNavigationOrigin.fromContext(context);

    return Scaffold(
      key: const Key('alerts_page'),
      appBar: AppBar(
        title: const Text('알림'),
        centerTitle: true,
        automaticallyImplyLeading: false,
        leading: origin != null
            ? BackButton(
                key: const Key('alerts_back'),
                onPressed: () =>
                    returnFromSharedPage(context, fallbackLocation: '/'),
              )
            : null,
        actions: const [AppMenuButton(), SizedBox(width: 8)],
      ),
      body: SafeArea(
        child: alerts.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    plantErrorMessage(error, '알림을 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('alerts_retry'),
                    onPressed: () => ref.invalidate(alertsProvider),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (items) {
            final active = items
                .where((alert) => alert.active)
                .toList(growable: false);
            final history = items
                .where((alert) => !alert.active)
                .toList(growable: false);
            final bloomItems = blooms.whenOrNull(data: (data) => data) ?? [];

            if (active.isEmpty && history.isEmpty && bloomItems.isEmpty) {
              return const Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.notifications_none_rounded,
                      size: 52,
                      color: AppColors.primarySoft,
                    ),
                    SizedBox(height: 14),
                    Text(
                      '아직 도착한 알림이 없어요.',
                      style: TextStyle(color: AppColors.textMuted),
                    ),
                  ],
                ),
              );
            }

            return RefreshIndicator(
              onRefresh: () {
                ref.invalidate(bloomsProvider);
                return ref.refresh(alertsProvider.future);
              },
              child: ListView(
                key: const Key('alerts_list'),
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
                children: [
                  if (active.isNotEmpty) ...[
                    const _SectionHeader(
                      icon: Icons.warning_amber_rounded,
                      iconColor: AppColors.error,
                      title: '이상 알림',
                    ),
                    for (final alert in active) ...[
                      _ActiveAlertCard(alert: alert),
                      const SizedBox(height: 12),
                    ],
                    const SizedBox(height: 10),
                  ],
                  if (bloomItems.isNotEmpty) ...[
                    const _SectionHeader(
                      icon: Icons.filter_vintage_outlined,
                      iconColor: Color(0xFFB07E09),
                      title: '개화 알림',
                    ),
                    for (final bloom in bloomItems.take(5)) ...[
                      _BloomAlertCard(bloom: bloom),
                      const SizedBox(height: 12),
                    ],
                    const SizedBox(height: 10),
                  ],
                  if (history.isNotEmpty) ...[
                    const _SectionHeader(
                      icon: Icons.history_rounded,
                      iconColor: AppColors.textMuted,
                      title: '알림 이력',
                    ),
                    for (final alert in history) ...[
                      _HistoryAlertCard(alert: alert),
                      const SizedBox(height: 12),
                    ],
                  ],
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}

/// UTC 시각을 '방금 / N분 전 / N시간 전 / N일 전' 으로 줄인다.
String relativeTimeLabel(DateTime utcTime) {
  final elapsed = DateTime.now().toUtc().difference(utcTime);
  if (elapsed.inMinutes < 1) {
    return '방금';
  }
  if (elapsed.inHours < 1) {
    return '${elapsed.inMinutes}분 전';
  }
  if (elapsed.inDays < 1) {
    return '${elapsed.inHours}시간 전';
  }
  return '${elapsed.inDays}일 전';
}

Future<void> _markAlertRead(WidgetRef ref, PlantAlert alert) async {
  if (alert.read) {
    return;
  }
  try {
    await ref.read(alertRepositoryProvider).markRead(alert.alertId);
  } catch (_) {
    // 읽음 처리는 부가 동작이라 실패해도 화면을 막지 않는다.
  }
  ref.invalidate(alertsProvider);
  ref.invalidate(homeControllerProvider);
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({
    required this.icon,
    required this.iconColor,
    required this.title,
  });

  final IconData icon;
  final Color iconColor;
  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        children: [
          Icon(icon, size: 18, color: iconColor),
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

class _ActiveAlertCard extends ConsumerWidget {
  const _ActiveAlertCard({required this.alert});

  final PlantAlert alert;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(20),
      clipBehavior: Clip.antiAlias,
      elevation: 1,
      shadowColor: AppColors.primary.withValues(alpha: 0.1),
      child: InkWell(
        key: Key('alert_card_${alert.alertId}'),
        onTap: () => _markAlertRead(ref, alert),
        child: Container(
          decoration: const BoxDecoration(
            border: Border(left: BorderSide(color: AppColors.error, width: 4)),
          ),
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                alert.message,
                style: TextStyle(
                  fontSize: 15,
                  height: 1.5,
                  fontWeight: alert.read ? FontWeight.w500 : FontWeight.w800,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                relativeTimeLabel(alert.occurredAt),
                style: const TextStyle(
                  fontSize: 12,
                  color: AppColors.textMuted,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _BloomAlertCard extends ConsumerWidget {
  const _BloomAlertCard({required this.bloom});

  final BloomRecord bloom;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(20),
      clipBehavior: Clip.antiAlias,
      elevation: 1,
      shadowColor: AppColors.primary.withValues(alpha: 0.1),
      child: InkWell(
        key: Key('bloom_alert_${bloom.bloomId}'),
        onTap: () async {
          if (bloom.read) {
            return;
          }
          try {
            await ref.read(bloomRepositoryProvider).markRead(bloom.bloomId);
          } catch (_) {
            // 읽음 처리 실패는 치명적이지 않다.
          }
          ref.invalidate(bloomsProvider);
        },
        child: Container(
          decoration: const BoxDecoration(
            border: Border(
              left: BorderSide(color: Color(0xFFB07E09), width: 4),
            ),
          ),
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: const BoxDecoration(
                  color: Color(0xFFFBEFD4),
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.celebration_outlined,
                  color: Color(0xFFB07E09),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${bloom.plantName}가 꽃을 피웠어요!',
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: bloom.read
                            ? FontWeight.w500
                            : FontWeight.w800,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${bloom.bloomDate.year}년 ${bloom.bloomDate.month}월 '
                      '${bloom.bloomDate.day}일',
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textMuted,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _HistoryAlertCard extends ConsumerWidget {
  const _HistoryAlertCard({required this.alert});

  final PlantAlert alert;

  static IconData _metricIcon(AlertMetric metric) {
    return switch (metric) {
      AlertMetric.temperature => Icons.thermostat_outlined,
      AlertMetric.humidity => Icons.opacity_outlined,
      AlertMetric.soilMoisture => Icons.water_drop_outlined,
      AlertMetric.dailyLight => Icons.wb_sunny_outlined,
      AlertMetric.photoperiod => Icons.schedule_outlined,
      AlertMetric.stationWaterLow => Icons.local_drink_outlined,
      AlertMetric.drainageTray => Icons.delete_outline_rounded,
      AlertMetric.unknown => Icons.notifications_none_rounded,
    };
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Material(
      color: AppColors.surfaceLow,
      borderRadius: BorderRadius.circular(20),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: Key('alert_card_${alert.alertId}'),
        onTap: () => _markAlertRead(ref, alert),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(
                _metricIcon(alert.metric),
                color: AppColors.primaryContainer,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      alert.message,
                      style: TextStyle(
                        fontSize: 14,
                        height: 1.4,
                        fontWeight: alert.read
                            ? FontWeight.w500
                            : FontWeight.w800,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      relativeTimeLabel(alert.occurredAt),
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textMuted,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
