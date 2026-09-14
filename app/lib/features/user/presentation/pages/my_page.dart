import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/navigation/app_menu_button.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/domain/auth_state.dart';
import 'package:potner_app/features/auth/presentation/controllers/auth_controller.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 마이 탭이다. 계정과 설정으로 이어 주는 허브 역할을 한다.
class MyPage extends ConsumerWidget {
  const MyPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(authControllerProvider).user;
    final isLoggingOut = ref.watch(
      authControllerProvider.select(
        (state) => state.status == AuthStatus.authenticating,
      ),
    );
    final plantCount = ref
        .watch(myPlantsProvider)
        .whenOrNull(data: (plants) => plants.length);

    return Scaffold(
      key: const Key('my_page'),
      appBar: AppBar(
        title: const Text('마이'),
        centerTitle: true,
        automaticallyImplyLeading: false,
        actions: const [AppMenuButton(), SizedBox(width: 8)],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    user?.nickname ?? '',
                    textAlign: TextAlign.center,
                    style: Theme.of(
                      context,
                    ).textTheme.headlineMedium?.copyWith(color: AppColors.text),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    plantCount == null ? ' ' : '식물 $plantCount개 관리 중',
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 22),
                  _TileGroup(
                    tiles: [
                      _Tile(
                        key: const Key('my_profile'),
                        icon: Icons.person_outline_rounded,
                        label: '내 정보',
                        onTap: () => context.go(
                          withNavigationOrigin(
                            '/my/profile',
                            AppNavigationOrigin.my,
                          ),
                        ),
                      ),
                      _Tile(
                        key: const Key('my_plants'),
                        icon: Icons.local_florist_outlined,
                        label: '나의 식물',
                        onTap: () => context.go(
                          withNavigationOrigin(
                            '/plants',
                            AppNavigationOrigin.my,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  _TileGroup(
                    tiles: [
                      _Tile(
                        key: const Key('my_care_settings'),
                        icon: Icons.eco_outlined,
                        label: '케어 설정',
                        onTap: () => context.go(
                          withNavigationOrigin(
                            '/plant-selection/care',
                            AppNavigationOrigin.my,
                          ),
                        ),
                      ),
                      _Tile(
                        key: const Key('my_devices'),
                        icon: Icons.settings_outlined,
                        label: '장치 관리',
                        onTap: () => context.go(
                          withNavigationOrigin(
                            '/devices',
                            AppNavigationOrigin.my,
                          ),
                        ),
                      ),
                      _Tile(
                        key: const Key('my_notification_settings'),
                        icon: Icons.notifications_active_outlined,
                        label: '알림 설정',
                        onTap: () => context.go(
                          withNavigationOrigin(
                            '/my/notifications',
                            AppNavigationOrigin.my,
                          ),
                        ),
                      ),
                      _Tile(
                        key: const Key('my_arrival_settings'),
                        icon: Icons.location_on_outlined,
                        label: '귀가 감지 설정',
                        onTap: () => context.go(
                          withNavigationOrigin(
                            '/my/arrival',
                            AppNavigationOrigin.my,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  _TileGroup(
                    tiles: [
                      _Tile(
                        key: const Key('my_logout'),
                        icon: Icons.logout_rounded,
                        label: '로그아웃',
                        onTap: isLoggingOut
                            ? null
                            : () => ref
                                  .read(authControllerProvider.notifier)
                                  .logout(),
                      ),
                      _Tile(
                        key: const Key('my_withdraw'),
                        icon: Icons.person_remove_outlined,
                        label: '회원 탈퇴',
                        destructive: true,
                        onTap: () => context.go(
                          withNavigationOrigin(
                            '/my/withdraw',
                            AppNavigationOrigin.my,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 28),
                  const Icon(
                    Icons.local_florist_outlined,
                    color: AppColors.primarySoft,
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    'Potner',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: AppColors.textMuted,
                      fontWeight: FontWeight.w700,
                    ),
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

class _TileGroup extends StatelessWidget {
  const _TileGroup({required this.tiles});

  final List<_Tile> tiles;

  @override
  Widget build(BuildContext context) {
    return Container(
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
          for (var i = 0; i < tiles.length; i++) ...[
            if (i > 0)
              const Divider(height: 1, indent: 60, color: AppColors.surfaceLow),
            tiles[i],
          ],
        ],
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({
    required this.icon,
    required this.label,
    required this.onTap,
    this.destructive = false,
    super.key,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    final color = destructive ? AppColors.error : AppColors.text;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
        child: Row(
          children: [
            Container(
              width: 38,
              height: 38,
              decoration: BoxDecoration(
                color: destructive
                    ? const Color(0xFFFBE4E4)
                    : AppColors.surfaceLow,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(
                icon,
                size: 20,
                color: destructive ? AppColors.error : AppColors.primary,
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Text(
                label,
                style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w700,
                  color: color,
                ),
              ),
            ),
            Icon(
              Icons.chevron_right_rounded,
              color: destructive
                  ? AppColors.error.withValues(alpha: 0.4)
                  : AppColors.textMuted,
            ),
          ],
        ),
      ),
    );
  }
}
