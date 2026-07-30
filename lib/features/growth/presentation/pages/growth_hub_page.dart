import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_menu_button.dart';
import 'package:potner_app/core/theme/app_theme.dart';

/// 성장기록 탭의 메인이다. 기록 화면들로 이어 주는 허브 역할만 한다.
class GrowthHubPage extends StatelessWidget {
  const GrowthHubPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: const Key('growth_hub_page'),
      appBar: AppBar(
        title: const Text('성장기록'),
        centerTitle: true,
        automaticallyImplyLeading: false,
        actions: const [AppMenuButton(), SizedBox(width: 8)],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _HubCard(
                    cardKey: const Key('growth_hub_diary'),
                    icon: Icons.menu_book_outlined,
                    iconBackground: const Color(0xFFCCEBC0),
                    iconColor: AppColors.primary,
                    title: '식물 일기',
                    description: '소중한 성장순간들의 기록',
                    onTap: () => context.go('/growth/diary'),
                  ),
                  _HubCard(
                    cardKey: const Key('growth_hub_blooms'),
                    icon: Icons.filter_vintage_outlined,
                    iconBackground: const Color(0xFFF5DFA1),
                    iconColor: const Color(0xFFB07E09),
                    title: '개화 기록',
                    description: '꽃이 피어난 특별한 날의 기록',
                    onTap: () => context.go('/growth/blooms'),
                  ),
                  _HubCard(
                    cardKey: const Key('growth_hub_photos'),
                    icon: Icons.photo_camera_outlined,
                    iconBackground: const Color(0xFFCCEBC0),
                    iconColor: AppColors.primary,
                    title: '포토 로그',
                    description: '식물의 성장 과정을 사진으로 확인하세요!',
                    onTap: () => context.go('/growth/photos'),
                  ),
                  _HubCard(
                    cardKey: const Key('growth_hub_compare'),
                    icon: Icons.auto_stories_outlined,
                    iconBackground: const Color(0xFFF5DFA1),
                    iconColor: const Color(0xFFB07E09),
                    title: '성장 비교',
                    description: '시간의 흐름에 따른 성장을 한눈에 비교해 보세요!',
                    onTap: () => context.go('/growth/compare'),
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

class _HubCard extends StatelessWidget {
  const _HubCard({
    required this.cardKey,
    required this.icon,
    required this.iconBackground,
    required this.iconColor,
    required this.title,
    required this.description,
    required this.onTap,
  });

  final Key cardKey;
  final IconData icon;
  final Color iconBackground;
  final Color iconColor;
  final String title;
  final String description;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Material(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(24),
        clipBehavior: Clip.antiAlias,
        elevation: 1,
        shadowColor: AppColors.primary.withValues(alpha: 0.12),
        child: InkWell(
          key: cardKey,
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Row(
              children: [
                Container(
                  width: 52,
                  height: 52,
                  decoration: BoxDecoration(
                    color: iconBackground,
                    shape: BoxShape.circle,
                  ),
                  child: Icon(icon, color: iconColor),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                          color: AppColors.text,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        description,
                        style: const TextStyle(
                          color: AppColors.textMuted,
                          height: 1.35,
                        ),
                      ),
                    ],
                  ),
                ),
                const Icon(
                  Icons.chevron_right_rounded,
                  color: AppColors.textMuted,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
