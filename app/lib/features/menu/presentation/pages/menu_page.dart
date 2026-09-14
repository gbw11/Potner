import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/domain/auth_state.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';

class MenuPage extends ConsumerWidget {
  const MenuPage({super.key});

  static const _sections = [
    _MenuSection(
      title: '식물 관리',
      entries: [
        _MenuEntry(
          id: 'plants',
          label: '나의 식물',
          icon: Icons.local_florist_outlined,
          action: _MenuAction.myPlants,
        ),
        _MenuEntry(
          id: 'registered_plants',
          label: '등록 식물',
          icon: Icons.format_list_bulleted_rounded,
          action: _MenuAction.registeredPlants,
        ),
        _MenuEntry(
          id: 'plant_registration',
          label: '내 식물 등록하기',
          icon: Icons.add_circle_outline_rounded,
          action: _MenuAction.plantRegistration,
        ),
        _MenuEntry(
          id: 'device_registration',
          label: '디바이스 등록하기',
          icon: Icons.devices_outlined,
          action: _MenuAction.deviceRegistration,
        ),
        _MenuEntry(
          id: 'care_settings',
          label: '케어 설정',
          icon: Icons.tune_rounded,
          action: _MenuAction.careSettings,
        ),
        _MenuEntry(
          id: 'environment',
          label: '환경 정보',
          icon: Icons.eco_outlined,
          action: _MenuAction.environment,
        ),
        _MenuEntry(
          id: 'repotting',
          label: '분갈이 방법',
          icon: Icons.yard_outlined,
          action: _MenuAction.repotting,
        ),
      ],
    ),
    _MenuSection(
      title: '성장 기록',
      entries: [
        _MenuEntry(
          id: 'plant_diary',
          label: '식물 일기',
          icon: Icons.menu_book_outlined,
          action: _MenuAction.plantDiary,
        ),
        _MenuEntry(
          id: 'flowering',
          label: '개화 기록',
          icon: Icons.local_florist_rounded,
          action: _MenuAction.flowering,
        ),
        _MenuEntry(
          id: 'photo_log',
          label: '포토 로그',
          icon: Icons.photo_library_outlined,
          action: _MenuAction.photoLog,
        ),
        _MenuEntry(
          id: 'growth_comparison',
          label: '성장 비교',
          icon: Icons.compare_arrows_rounded,
          action: _MenuAction.growthComparison,
        ),
      ],
    ),
    _MenuSection(
      title: '서비스 알림',
      entries: [
        _MenuEntry(
          id: 'alert_history',
          label: '알림 내역',
          icon: Icons.notifications_none_rounded,
          action: _MenuAction.alerts,
        ),
        _MenuEntry(
          id: 'notification_settings',
          label: '알림 설정',
          icon: Icons.notifications_active_outlined,
          action: _MenuAction.notificationSettings,
        ),
      ],
    ),
    _MenuSection(
      title: '계정 및 설정',
      entries: [
        _MenuEntry(
          id: 'profile',
          label: '내 정보',
          icon: Icons.person_outline_rounded,
          action: _MenuAction.my,
        ),
        _MenuEntry(
          id: 'devices',
          label: '장치 관리',
          icon: Icons.router_outlined,
          action: _MenuAction.deviceManagement,
        ),
        _MenuEntry(
          id: 'robot_drive',
          label: '로봇 조작',
          icon: Icons.gamepad_outlined,
          action: _MenuAction.robotDrive,
        ),
        _MenuEntry(
          id: 'conversations',
          label: '로봇과 나눈 대화',
          icon: Icons.forum_outlined,
          action: _MenuAction.conversations,
        ),
        _MenuEntry(
          id: 'logout',
          label: '로그아웃',
          icon: Icons.logout_rounded,
          action: _MenuAction.logout,
        ),
        _MenuEntry(
          id: 'withdrawal',
          label: '회원 탈퇴',
          icon: Icons.person_remove_outlined,
          destructive: true,
          action: _MenuAction.withdrawal,
        ),
      ],
    ),
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isLoggingOut = ref.watch(
      authControllerProvider.select(
        (state) => state.status == AuthStatus.authenticating,
      ),
    );

    return Scaffold(
      key: const Key('menu_page'),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(2, 8, 8, 4),
              child: Row(
                children: [
                  Image.asset(
                    'assets/images/auth/potner_logo_login.png',
                    height: 80,
                    semanticLabel: 'Potner',
                  ),
                  const Spacer(),
                  IconButton(
                    key: const Key('close_menu_button'),
                    tooltip: '메뉴 닫기',
                    onPressed: () =>
                        context.canPop() ? context.pop() : context.go('/'),
                    icon: const Icon(Icons.close_rounded),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 6, 10, 2),
              child: _MenuTile(
                entry: const _MenuEntry(
                  id: 'home',
                  label: '홈',
                  icon: Icons.home_rounded,
                  action: _MenuAction.home,
                ),
                highlighted: true,
                onTap: () => context.go('/'),
              ),
            ),
            Expanded(
              child: ListView(
                key: const Key('menu_scroll_view'),
                padding: const EdgeInsets.only(bottom: 24),
                children: [
                  for (final section in _sections)
                    _MenuSectionView(
                      section: section,
                      isLoggingOut: isLoggingOut,
                      onEntrySelected: (entry) =>
                          _handleEntry(context, ref, entry),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _handleEntry(
    BuildContext context,
    WidgetRef ref,
    _MenuEntry entry,
  ) async {
    switch (entry.action) {
      case _MenuAction.home:
        context.go('/');
      case _MenuAction.alerts:
        context.go(_fromMenu('/alerts'));
      case _MenuAction.plantRegistration:
        context.go(_fromMenu('/plants/register'));
      case _MenuAction.myPlants:
        context.go(_fromMenu('/plants'));
      case _MenuAction.registeredPlants:
        context.go(_fromMenu('/my/plants'));
      case _MenuAction.deviceRegistration:
        context.go(_fromMenu('/devices/register'));
      case _MenuAction.deviceManagement:
        context.go(_fromMenu('/devices'));
      case _MenuAction.robotDrive:
        context.go(_fromMenu('/devices/drive'));
      case _MenuAction.conversations:
        context.go(_fromMenu('/devices/conversations'));
      case _MenuAction.careSettings:
        context.go(_fromMenu('/plant-selection/care'));
      case _MenuAction.environment:
        context.go(_fromMenu('/plant-selection/environment'));
      case _MenuAction.plantDiary:
        context.go(_fromMenu('/growth/diary'));
      case _MenuAction.photoLog:
        context.go(_fromMenu('/growth/photos'));
      case _MenuAction.growthComparison:
        context.go(_fromMenu('/growth/compare'));
      case _MenuAction.flowering:
        context.go(_fromMenu('/growth/blooms'));
      case _MenuAction.repotting:
        context.go(_fromMenu('/repotting'));
      case _MenuAction.withdrawal:
        context.go(_fromMenu('/my/withdraw'));
      case _MenuAction.notificationSettings:
        context.go(_fromMenu('/my/notifications'));
      case _MenuAction.my:
        context.go(_fromMenu('/my/profile'));
      case _MenuAction.logout:
        await ref.read(authControllerProvider.notifier).logout();
      case _MenuAction.comingSoon:
        ScaffoldMessenger.of(context)
          ..hideCurrentSnackBar()
          ..showSnackBar(
            SnackBar(content: Text('${entry.label} 기능은 다음 작업에서 연결합니다.')),
          );
    }
  }

  String _fromMenu(String location) {
    return withNavigationOrigin(location, AppNavigationOrigin.menu);
  }
}

class _MenuSectionView extends StatelessWidget {
  const _MenuSectionView({
    required this.section,
    required this.isLoggingOut,
    required this.onEntrySelected,
  });

  final _MenuSection section;
  final bool isLoggingOut;
  final ValueChanged<_MenuEntry> onEntrySelected;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(22, 0, 22, 6),
            child: Text(
              section.title,
              style: const TextStyle(
                color: AppColors.textMuted,
                fontSize: 12,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.7,
              ),
            ),
          ),
          for (final entry in section.entries)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 1),
              child: _MenuTile(
                entry: entry,
                onTap: isLoggingOut ? null : () => onEntrySelected(entry),
              ),
            ),
        ],
      ),
    );
  }
}

class _MenuTile extends StatelessWidget {
  const _MenuTile({
    required this.entry,
    required this.onTap,
    this.highlighted = false,
  });

  final _MenuEntry entry;
  final VoidCallback? onTap;
  final bool highlighted;

  @override
  Widget build(BuildContext context) {
    final foreground = entry.destructive
        ? AppColors.error
        : highlighted
        ? const Color(0xFF092003)
        : AppColors.textMuted;

    return Material(
      color: highlighted ? const Color(0xFFCCEBC0) : Colors.transparent,
      borderRadius: BorderRadius.circular(14),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: Key('menu_item_${entry.id}'),
        onTap: onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 52),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            child: Row(
              children: [
                Icon(entry.icon, color: foreground, size: 24),
                const SizedBox(width: 16),
                Expanded(
                  child: Text(
                    entry.label,
                    style: TextStyle(
                      color: foreground,
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                if (highlighted)
                  Icon(
                    Icons.arrow_forward_ios_rounded,
                    color: foreground.withValues(alpha: 0.55),
                    size: 16,
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _MenuSection {
  const _MenuSection({required this.title, required this.entries});

  final String title;
  final List<_MenuEntry> entries;
}

class _MenuEntry {
  const _MenuEntry({
    required this.id,
    required this.label,
    required this.icon,
    this.action = _MenuAction.comingSoon,
    this.destructive = false,
  });

  final String id;
  final String label;
  final IconData icon;
  final _MenuAction action;
  final bool destructive;
}

enum _MenuAction {
  home,
  alerts,
  plantRegistration,
  myPlants,
  registeredPlants,
  deviceRegistration,
  deviceManagement,
  robotDrive,
  conversations,
  careSettings,
  environment,
  repotting,
  plantDiary,
  photoLog,
  growthComparison,
  flowering,
  withdrawal,
  notificationSettings,
  my,
  logout,
  comingSoon,
}
