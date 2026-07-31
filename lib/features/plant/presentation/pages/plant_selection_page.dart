import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

enum PlantSelectionDestination { care, environment }

class PlantSelectionPage extends ConsumerWidget {
  const PlantSelectionPage({required this.destination, super.key});

  final PlantSelectionDestination destination;

  String get _title => switch (destination) {
    PlantSelectionDestination.care => '케어할 식물 선택',
    PlantSelectionDestination.environment => '환경 정보를 볼 식물 선택',
  };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plants = ref.watch(myPlantsProvider);

    return Scaffold(
      key: Key('plant_selection_${destination.name}'),
      appBar: AppBar(
        title: Text(_title),
        centerTitle: true,
        leading: BackButton(
          key: Key('plant_selection_${destination.name}_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/my'),
        ),
      ),
      body: SafeArea(
        child: plants.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _SelectionMessage(
            icon: Icons.error_outline_rounded,
            message: plantErrorMessage(error, '식물 목록을 불러오지 못했습니다.'),
            actionLabel: '다시 시도',
            onAction: () => ref.invalidate(myPlantsProvider),
          ),
          data: (items) => items.isEmpty
              ? _SelectionMessage(
                  icon: Icons.local_florist_outlined,
                  message: '먼저 식물을 등록해 주세요.',
                  actionLabel: '내 식물 등록하기',
                  onAction: () => context.go(
                    withCurrentNavigationOrigin(context, '/plants/register'),
                  ),
                )
              : RefreshIndicator(
                  onRefresh: () => ref.refresh(myPlantsProvider.future),
                  child: ListView(
                    key: const Key('plant_selection_list'),
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
                    children: [
                      const Text(
                        '설정할 식물을 선택해 주세요.',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: AppColors.textMuted),
                      ),
                      const SizedBox(height: 18),
                      for (final plant in items) ...[
                        _PlantSelectionCard(
                          plant: plant,
                          onTap: () => _select(context, plant.plantId),
                        ),
                        const SizedBox(height: 12),
                      ],
                    ],
                  ),
                ),
        ),
      ),
    );
  }

  void _select(BuildContext context, String plantId) {
    final suffix = switch (destination) {
      PlantSelectionDestination.care => 'care',
      PlantSelectionDestination.environment => 'environment',
    };
    context.go(
      withPreviousNavigationLocation(context, '/plants/$plantId/$suffix'),
    );
  }
}

class _PlantSelectionCard extends StatelessWidget {
  const _PlantSelectionCard({required this.plant, required this.onTap});

  final MyPlant plant;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(20),
      clipBehavior: Clip.antiAlias,
      elevation: 1,
      shadowColor: AppColors.primary.withValues(alpha: 0.08),
      child: InkWell(
        key: Key('plant_selection_${plant.plantId}'),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Row(
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: AppColors.surfaceLow,
                  borderRadius: BorderRadius.circular(15),
                ),
                child: const Icon(
                  Icons.local_florist_rounded,
                  color: AppColors.primary,
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      plant.name,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w800,
                        color: AppColors.text,
                      ),
                    ),
                    const SizedBox(height: 3),
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

class _SelectionMessage extends StatelessWidget {
  const _SelectionMessage({
    required this.icon,
    required this.message,
    required this.actionLabel,
    required this.onAction,
  });

  final IconData icon;
  final String message;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 52, color: AppColors.primarySoft),
            const SizedBox(height: 14),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textMuted),
            ),
            const SizedBox(height: 18),
            FilledButton.tonal(onPressed: onAction, child: Text(actionLabel)),
          ],
        ),
      ),
    );
  }
}
