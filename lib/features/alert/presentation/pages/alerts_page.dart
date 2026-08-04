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

/// 사용자가 화면에서 치운 알림이다.
///
/// 치운 알림을 화면에서 즉시 감추는 자리다.
///
/// 서버가 `dismissed_at` 으로 기록하므로 **다시 들어와도 돌아오지 않는다.** 이 집합은 서버 응답이
/// 갱신되기 전까지의 즉각 반응과 되돌리기 창을 위한 것이다 — 밀어낸 카드가 다음 조회까지 남아
/// 있으면 치운 것처럼 보이지 않는다.
///
/// autoDispose 를 걸지 않는다. 화면을 나갔다 들어오는 사이 서버 조회가 아직 안 끝났으면 치운 것이
/// 잠깐 되살아나 보인다.
final dismissedAlertIdsProvider =
    NotifierProvider<DismissedAlertIds, Set<String>>(DismissedAlertIds.new);

class DismissedAlertIds extends Notifier<Set<String>> {
  @override
  Set<String> build() => const {};

  void dismiss(String alertId) => state = {...state, alertId};

  /// 잘못 치운 것을 되돌린다. 스와이프는 눌러서 확인하는 동작이 없어 오조작이 쉽다.
  void restore(String alertId) => state = {...state}..remove(alertId);
}

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
            // 치우는 처리를 화면 쪽에서 한다. 카드는 밀려 나가면서 트리에서 빠지므로 그쪽
            // ref 로는 되돌리기를 처리할 수 없다.
            /// 서버가 받아들였을 때만 카드를 내보낸다.
            ///
            /// 읽음 처리를 따로 부르지 않는다 — 서버가 치우기와 함께 남긴다.
            Future<bool> confirmDismiss(PlantAlert alert) async {
              final messenger = ScaffoldMessenger.of(context);
              try {
                await ref.read(alertRepositoryProvider).dismiss(alert.alertId);
              } catch (_) {
                messenger
                  ..hideCurrentSnackBar()
                  ..showSnackBar(
                    const SnackBar(
                      content: Text('알림을 치우지 못했어요. 잠시 뒤 다시 시도해 주세요.'),
                    ),
                  );
                return false;
              }
              return true;
            }

            void dismiss(PlantAlert alert) {
              final dismissals = ref.read(dismissedAlertIdsProvider.notifier);
              final messenger = ScaffoldMessenger.of(context);
              // 서버는 이미 받아들였다. 다음 조회 전까지 화면에서 빼 둔다.
              dismissals.dismiss(alert.alertId);
              // 홈의 안 읽음 배지가 서버에서 내려간 것을 반영해야 한다.
              ref.invalidate(homeControllerProvider);
              messenger
                ..hideCurrentSnackBar()
                ..showSnackBar(
                  SnackBar(
                    content: const Text('알림을 치웠어요.'),
                    action: SnackBarAction(
                      label: '되돌리기',
                      onPressed: () =>
                          _restoreOnServer(ref, alert, dismissals, messenger),
                    ),
                  ),
                );
            }

            final dismissed = ref.watch(dismissedAlertIdsProvider);
            final visible = items.where(
              (alert) => !dismissed.contains(alert.alertId),
            );
            final active = visible
                .where((alert) => alert.active)
                .toList(growable: false);
            final history = visible
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
                      _DismissibleAlert(
                        alert: alert,
                        confirmDismiss: () => confirmDismiss(alert),
                        onDismissed: () => dismiss(alert),
                        child: _ActiveAlertCard(alert: alert),
                      ),
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
                      _DismissibleAlert(
                        alert: alert,
                        confirmDismiss: () => confirmDismiss(alert),
                        onDismissed: () => dismiss(alert),
                        child: _HistoryAlertCard(alert: alert),
                      ),
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

/// 되돌리기다. 화면을 먼저 되살리고 서버에 알린다.
///
/// 여기서는 먼저 되살려도 된다. 치우기와 달리 `Dismissible` 이 이미 트리에서 빠진 뒤라, 목록에
/// 다시 넣는 것은 새 위젯을 만드는 일이다.
Future<void> _restoreOnServer(
  WidgetRef ref,
  PlantAlert alert,
  DismissedAlertIds dismissals,
  ScaffoldMessengerState messenger,
) async {
  dismissals.restore(alert.alertId);
  try {
    await ref.read(alertRepositoryProvider).restore(alert.alertId);
  } catch (_) {
    // 서버에는 치운 채로 남았다. 다음 조회에서 다시 사라지므로 그 사실을 알린다.
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(const SnackBar(content: Text('되돌리지 못했어요. 새로 고치면 다시 사라집니다.')));
    return;
  }
  ref.invalidate(alertsProvider);
  ref.invalidate(homeControllerProvider);
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

/// 왼쪽으로 밀어 알림을 치운다.
///
/// 서버에서 지우는 것이 아니라 목록에서만 빼는 것이다. 오른쪽으로 미는 방향은 두지 않는다 —
/// 방향마다 다른 뜻을 주면 어느 쪽이 무엇인지 기억해야 한다.
///
/// 치우는 동작을 자기가 하지 않고 [onDismissed] 로 받는다. 밀려 나간 순간 이 위젯은 트리에서
/// 빠지므로, 여기서 잡은 `ref` 로 스낵바의 되돌리기를 처리하면 이미 해제된 ref 를 쓰게 된다.
///
/// [confirmDismiss] 로 서버 응답을 먼저 받는다. 먼저 감추고 실패하면 되살리는 방식은 쓸 수 없다 —
/// 밀려 나간 `Dismissible` 을 같은 자리에 되돌리면 Flutter 가 "A dismissed Dismissible widget is
/// still part of the tree" 로 단정한다. 거절되면 카드가 제자리로 돌아온다.
class _DismissibleAlert extends StatelessWidget {
  const _DismissibleAlert({
    required this.alert,
    required this.confirmDismiss,
    required this.onDismissed,
    required this.child,
  });

  final PlantAlert alert;
  final Future<bool> Function() confirmDismiss;
  final VoidCallback onDismissed;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Dismissible(
      key: ValueKey('alert_dismiss_${alert.alertId}'),
      direction: DismissDirection.endToStart,
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 22),
        decoration: BoxDecoration(
          color: AppColors.surfaceLow,
          borderRadius: BorderRadius.circular(20),
        ),
        child: const Icon(Icons.check_rounded, color: AppColors.textMuted),
      ),
      confirmDismiss: (_) => confirmDismiss(),
      onDismissed: (_) => onDismissed(),
      child: child,
    );
  }
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
