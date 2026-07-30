import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/photo/presentation/photo_providers.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/presentation/pages/plant_list_page.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 포토 로그다. 장치가 매일 찍은 성장 사진을 최신 날짜부터 보여 준다.
class PhotoLogPage extends ConsumerStatefulWidget {
  const PhotoLogPage({super.key});

  @override
  ConsumerState<PhotoLogPage> createState() => _PhotoLogPageState();
}

class _PhotoLogPageState extends ConsumerState<PhotoLogPage> {
  MyPlant? _plant;

  @override
  Widget build(BuildContext context) {
    final plants = ref.watch(myPlantsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('포토 로그'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('photo_log_back'),
          onPressed: () =>
              context.canPop() ? context.pop() : context.go('/growth'),
        ),
      ),
      body: SafeArea(
        child: plants.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _CenteredMessage(
            message: plantErrorMessage(error, '식물 목록을 불러오지 못했습니다.'),
            retryKey: const Key('photo_log_retry'),
            onRetry: () => ref.invalidate(myPlantsProvider),
          ),
          data: (items) {
            if (items.isEmpty) {
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(28),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Text(
                        '식물을 등록하면 매일 성장 사진이 쌓여요.',
                        style: TextStyle(color: AppColors.textMuted),
                      ),
                      const SizedBox(height: 16),
                      FilledButton(
                        key: const Key('photo_log_register_plant'),
                        onPressed: () => context.go('/plants/register'),
                        child: const Text('내 식물 등록하기'),
                      ),
                    ],
                  ),
                ),
              );
            }
            final plant = _plant ?? items.first;
            return _buildBody(plant, items);
          },
        ),
      ),
    );
  }

  Widget _buildBody(MyPlant plant, List<MyPlant> plants) {
    final photos = ref.watch(plantPhotosProvider(plant.plantId));

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 4),
          child: _PlantSelectorCard(
            plant: plant,
            plants: plants,
            onChanged: (selected) => setState(() => _plant = selected),
          ),
        ),
        Expanded(
          child: photos.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (error, _) => _CenteredMessage(
              message: plantErrorMessage(error, '사진을 불러오지 못했습니다.'),
              retryKey: const Key('photo_log_photos_retry'),
              onRetry: () =>
                  ref.invalidate(plantPhotosProvider(plant.plantId)),
            ),
            data: (items) {
              if (items.isEmpty) {
                return const _CenteredMessage(
                  message: '아직 촬영된 사진이 없어요.\n로봇이 사진을 찍으면 여기에 쌓여요.',
                );
              }
              final descending = items.reversed.toList(growable: false);
              return RefreshIndicator(
                onRefresh: () =>
                    ref.refresh(plantPhotosProvider(plant.plantId).future),
                child: ListView.builder(
                  key: const Key('photo_log_list'),
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                  itemCount: descending.length,
                  itemBuilder: (context, index) {
                    final photo = descending[index];
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          child: Text(
                            photoDateLabel(photo.photoDate),
                            style: const TextStyle(
                              fontWeight: FontWeight.w700,
                              color: AppColors.text,
                            ),
                          ),
                        ),
                        Material(
                          color: Colors.transparent,
                          child: InkWell(
                            key: Key('photo_card_${photo.photoId}'),
                            borderRadius: BorderRadius.circular(18),
                            onTap: () => context.go(
                              '/growth/photos/${plant.plantId}/${photo.photoId}',
                            ),
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(18),
                              child: Image.network(
                                photo.thumbnailUrl,
                                width: 132,
                                height: 132,
                                fit: BoxFit.cover,
                                errorBuilder: (_, _, _) => Container(
                                  width: 132,
                                  height: 132,
                                  color: AppColors.surfaceLow,
                                  child: const Icon(
                                    Icons.image_not_supported_outlined,
                                    color: AppColors.textMuted,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 10),
                      ],
                    );
                  },
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _PlantSelectorCard extends StatelessWidget {
  const _PlantSelectorCard({
    required this.plant,
    required this.plants,
    required this.onChanged,
  });

  final MyPlant plant;
  final List<MyPlant> plants;
  final ValueChanged<MyPlant> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: AppColors.primary.withValues(alpha: 0.07),
            blurRadius: 14,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Row(
        children: [
          PlantThumbnail(url: plant.thumbnailUrl, size: 44, circular: true),
          const SizedBox(width: 12),
          Expanded(
            child: DropdownButtonHideUnderline(
              child: DropdownButton<String>(
                key: const Key('photo_log_plant_selector'),
                value: plant.plantId,
                isExpanded: true,
                items: [
                  for (final item in plants)
                    DropdownMenuItem(
                      value: item.plantId,
                      child: Text(
                        item.name,
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                          color: AppColors.primary,
                        ),
                      ),
                    ),
                ],
                onChanged: (plantId) {
                  if (plantId == null) {
                    return;
                  }
                  onChanged(
                    plants.firstWhere((item) => item.plantId == plantId),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CenteredMessage extends StatelessWidget {
  const _CenteredMessage({required this.message, this.retryKey, this.onRetry});

  final String message;
  final Key? retryKey;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textMuted, height: 1.5),
            ),
            if (onRetry != null) ...[
              const SizedBox(height: 16),
              FilledButton.tonal(
                key: retryKey,
                onPressed: onRetry,
                child: const Text('다시 시도'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
