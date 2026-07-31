import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';
import 'package:potner_app/features/user/data/user_repository_impl.dart';
import 'package:potner_app/features/user/domain/user_repository.dart';

final notificationSettingsProvider =
    FutureProvider.autoDispose<NotificationSettings>((ref) {
      return ref.watch(userRepositoryProvider).getNotificationSettings();
    });

/// 알림 설정이다. 전체 알림 마스터 스위치와 세부 3종을 토글한다.
class NotificationSettingsPage extends ConsumerWidget {
  const NotificationSettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(notificationSettingsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('알림 설정'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('notification_settings_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/my'),
        ),
      ),
      body: SafeArea(
        child: settings.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    plantErrorMessage(error, '알림 설정을 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('notification_settings_retry'),
                    onPressed: () =>
                        ref.invalidate(notificationSettingsProvider),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (data) => _SettingsForm(key: ValueKey(data), settings: data),
        ),
      ),
    );
  }
}

class _SettingsForm extends ConsumerStatefulWidget {
  const _SettingsForm({required this.settings, super.key});

  final NotificationSettings settings;

  @override
  ConsumerState<_SettingsForm> createState() => _SettingsFormState();
}

class _SettingsFormState extends ConsumerState<_SettingsForm> {
  late NotificationSettings _current;
  bool _isSaving = false;

  @override
  void initState() {
    super.initState();
    _current = widget.settings;
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Material(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(24),
                clipBehavior: Clip.antiAlias,
                elevation: 1,
                shadowColor: AppColors.primary.withValues(alpha: 0.14),
                child: SwitchListTile(
                  key: const Key('toggle_all'),
                  value: _current.allEnabled,
                  onChanged: _isSaving
                      ? null
                      : (value) => _update(allEnabled: value),
                  contentPadding: const EdgeInsets.fromLTRB(20, 8, 12, 8),
                  title: const Text(
                    '전체 알림',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
                  ),
                  subtitle: const Text(
                    '모든 알림을 한 번에 제어합니다',
                    style: TextStyle(color: AppColors.textMuted),
                  ),
                ),
              ),
              const SizedBox(height: 22),
              const Padding(
                padding: EdgeInsets.only(left: 4, bottom: 8),
                child: Text(
                  '세부 알림 설정',
                  style: TextStyle(
                    fontWeight: FontWeight.w800,
                    color: AppColors.text,
                  ),
                ),
              ),
              Container(
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(24),
                  boxShadow: [
                    BoxShadow(
                      color: AppColors.primary.withValues(alpha: 0.08),
                      blurRadius: 14,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: Column(
                  children: [
                    _DetailTile(
                      toggleKey: const Key('toggle_push'),
                      icon: Icons.notifications_active_outlined,
                      title: '푸시 알림',
                      subtitle: '기기 상단 팝업 알림',
                      value: _current.pushEnabled,
                      dimmed: !_current.allEnabled,
                      onChanged: _isSaving
                          ? null
                          : (value) => _update(pushEnabled: value),
                    ),
                    const Divider(
                      height: 1,
                      indent: 76,
                      color: AppColors.surfaceLow,
                    ),
                    _DetailTile(
                      toggleKey: const Key('toggle_plant_care'),
                      icon: Icons.local_florist_outlined,
                      title: '식물 케어 알림',
                      subtitle: '이상상황 및 케어 알림',
                      value: _current.plantCareEnabled,
                      dimmed: !_current.allEnabled,
                      onChanged: _isSaving
                          ? null
                          : (value) => _update(plantCareEnabled: value),
                    ),
                    const Divider(
                      height: 1,
                      indent: 76,
                      color: AppColors.surfaceLow,
                    ),
                    _DetailTile(
                      toggleKey: const Key('toggle_marketing'),
                      icon: Icons.campaign_outlined,
                      title: '이벤트 및 공지사항',
                      subtitle: '마케팅 정보 및 앱 업데이트 소식',
                      value: _current.marketingEnabled,
                      dimmed: !_current.allEnabled,
                      onChanged: _isSaving
                          ? null
                          : (value) => _update(marketingEnabled: value),
                    ),
                  ],
                ),
              ),
              if (!_current.allEnabled)
                const Padding(
                  padding: EdgeInsets.only(top: 12, left: 4),
                  child: Text(
                    '전체 알림이 꺼져 있으면 세부 설정과 무관하게 알림이 오지 않아요.',
                    style: TextStyle(fontSize: 12, color: AppColors.textMuted),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _update({
    bool? allEnabled,
    bool? pushEnabled,
    bool? plantCareEnabled,
    bool? marketingEnabled,
  }) async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isSaving = true);
    try {
      final updated = await ref
          .read(userRepositoryProvider)
          .updateNotificationSettings(
            allEnabled: allEnabled,
            pushEnabled: pushEnabled,
            plantCareEnabled: plantCareEnabled,
            marketingEnabled: marketingEnabled,
          );
      if (!mounted) {
        return;
      }
      setState(() => _current = updated);
    } catch (error) {
      if (!mounted) {
        return;
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '설정을 저장하지 못했습니다.'))),
        );
    } finally {
      if (mounted) {
        setState(() => _isSaving = false);
      }
    }
  }
}

class _DetailTile extends StatelessWidget {
  const _DetailTile({
    required this.toggleKey,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.value,
    required this.dimmed,
    required this.onChanged,
  });

  final Key toggleKey;
  final IconData icon;
  final String title;
  final String subtitle;
  final bool value;

  /// 전체 알림이 꺼져 있어 실제로는 발송되지 않는 상태를 시각적으로 알린다.
  final bool dimmed;
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: dimmed ? 0.5 : 1,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 12, 8, 12),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: const Color(0xFFE4F0DC),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(icon, size: 22, color: AppColors.primary),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppColors.textMuted,
                    ),
                  ),
                ],
              ),
            ),
            Switch(key: toggleKey, value: value, onChanged: onChanged),
          ],
        ),
      ),
    );
  }
}
