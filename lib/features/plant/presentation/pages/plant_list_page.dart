import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 나의 식물 목록이다. 항목을 누르면 식물 프로필로 이어질 예정이다.
class PlantListPage extends ConsumerWidget {
  const PlantListPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plants = ref.watch(myPlantsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('나의 식물'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('plant_list_back'),
          onPressed: () => returnFromSharedPage(context, fallbackLocation: '/'),
        ),
      ),
      body: SafeArea(
        child: plants.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _ListFailure(
            message: plantErrorMessage(error, '식물 목록을 불러오지 못했습니다.'),
            onRetry: () => ref.invalidate(myPlantsProvider),
          ),
          data: (items) => items.isEmpty
              ? _EmptyPlants(
                  onRegister: () => context.go(
                    withCurrentNavigationOrigin(context, '/plants/register'),
                  ),
                )
              : RefreshIndicator(
                  onRefresh: () => ref.refresh(myPlantsProvider.future),
                  child: ListView(
                    key: const Key('plant_list'),
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
                    children: [
                      _CountCard(count: items.length),
                      const SizedBox(height: 18),
                      for (final plant in items) ...[
                        _PlantCard(
                          plant: plant,
                          onTap: () => context.go(
                            withPreviousNavigationLocation(
                              context,
                              '/plants/${plant.plantId}',
                            ),
                          ),
                        ),
                        const SizedBox(height: 14),
                      ],
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}

class _CountCard extends StatelessWidget {
  const _CountCard({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 22),
      decoration: BoxDecoration(
        color: AppColors.surfaceLow,
        borderRadius: BorderRadius.circular(22),
      ),
      child: Row(
        children: [
          const Icon(
            Icons.local_florist_outlined,
            size: 40,
            color: AppColors.primary,
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Text.rich(
              TextSpan(
                text: '총 ',
                children: [
                  TextSpan(
                    text: '$count개',
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      color: AppColors.primary,
                    ),
                  ),
                  const TextSpan(text: '의 식물을\n돌보고 있어요.'),
                ],
              ),
              style: const TextStyle(fontSize: 16, height: 1.4),
            ),
          ),
          const Icon(Icons.eco_outlined, color: AppColors.primarySoft),
        ],
      ),
    );
  }
}

class _PlantCard extends StatelessWidget {
  const _PlantCard({required this.plant, required this.onTap});

  final MyPlant plant;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(24),
      clipBehavior: Clip.antiAlias,
      elevation: 1,
      shadowColor: AppColors.primary.withValues(alpha: 0.14),
      child: InkWell(
        key: Key('plant_card_${plant.plantId}'),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              PlantThumbnail(url: plant.thumbnailUrl, size: 84, circular: true),
              const SizedBox(width: 18),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      plant.name,
                      style: const TextStyle(
                        fontSize: 19,
                        fontWeight: FontWeight.w800,
                        color: AppColors.text,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      plant.speciesName,
                      style: const TextStyle(color: AppColors.textMuted),
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
    );
  }
}

/// 대표 사진이 없으면 기본 아이콘을 그린다. 목록 화면들이 함께 쓴다.
class PlantThumbnail extends StatelessWidget {
  const PlantThumbnail({
    required this.url,
    required this.size,
    this.circular = false,
    super.key,
  });

  final String? url;
  final double size;
  final bool circular;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(circular ? size / 2 : 16);
    return ClipRRect(
      borderRadius: radius,
      child: SizedBox.square(
        dimension: size,
        child: url == null
            ? const ColoredBox(
                color: AppColors.surfaceLow,
                child: Icon(
                  Icons.local_florist_outlined,
                  color: AppColors.primarySoft,
                ),
              )
            : Image.network(
                url!,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => const ColoredBox(
                  color: AppColors.surfaceLow,
                  child: Icon(
                    Icons.local_florist_outlined,
                    color: AppColors.primarySoft,
                  ),
                ),
              ),
      ),
    );
  }
}

class _EmptyPlants extends StatelessWidget {
  const _EmptyPlants({required this.onRegister});

  final VoidCallback onRegister;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.local_florist_outlined,
              size: 52,
              color: AppColors.primarySoft,
            ),
            const SizedBox(height: 14),
            const Text(
              '아직 등록한 식물이 없어요.\n첫 식물 친구를 맞이해 보세요!',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.textMuted, height: 1.5),
            ),
            const SizedBox(height: 20),
            FilledButton(
              key: const Key('plant_list_register'),
              onPressed: onRegister,
              child: const Text('내 식물 등록하기'),
            ),
          ],
        ),
      ),
    );
  }
}

class _ListFailure extends StatelessWidget {
  const _ListFailure({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.cloud_off_rounded,
              size: 42,
              color: AppColors.textMuted,
            ),
            const SizedBox(height: 12),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textMuted),
            ),
            const SizedBox(height: 16),
            FilledButton.tonal(
              key: const Key('plant_list_retry'),
              onPressed: onRetry,
              child: const Text('다시 시도'),
            ),
          ],
        ),
      ),
    );
  }
}
