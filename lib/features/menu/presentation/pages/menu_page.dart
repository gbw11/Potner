import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
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
        ),
        _MenuEntry(
          id: 'flowering',
          label: '개화 기록',
          icon: Icons.local_florist_rounded,
        ),
        _MenuEntry(
          id: 'photo_log',
          label: '포토 로그',
          icon: Icons.photo_library_outlined,
        ),
        _MenuEntry(
          id: 'growth_comparison',
          label: '성장 비교',
          icon: Icons.compare_arrows_rounded,
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
                    onPressed: () => context.pop(),
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
        context.go('/alerts');
      case _MenuAction.plantRegistration:
        context.go('/plants/register');
      case _MenuAction.myPlants:
        context.go('/plants');
      case _MenuAction.registeredPlants:
        context.go('/my/plants');
      case _MenuAction.deviceRegistration:
        context.go('/devices/register');
      case _MenuAction.deviceManagement:
        context.go('/devices');
      case _MenuAction.my:
        context.go('/my');
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
  my,
  logout,
  comingSoon,
}
