import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/photo/data/photo_repository_impl.dart';
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
              returnFromSharedPage(context, fallbackLocation: '/growth'),
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
                      const Icon(
                        Icons.photo_library_outlined,
                        key: Key('photo_log_empty_icon'),
                        size: 52,
                        color: AppColors.primarySoft,
                      ),
                      const SizedBox(height: 14),
                      const Text(
                        '식물을 등록하면 매일 성장 사진이 쌓여요.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: AppColors.textMuted,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: 16),
                      FilledButton(
                        key: const Key('photo_log_register_plant'),
                        onPressed: () => context.go(
                          withCurrentNavigationOrigin(
                            context,
                            '/plants/register',
                          ),
                        ),
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
              onRetry: () => ref.invalidate(plantPhotosProvider(plant.plantId)),
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
                              withCurrentNavigationOrigin(
                                context,
                                '/growth/photos/${plant.plantId}/${photo.photoId}',
                              ),
                            ),
                            // 길게 눌러 삭제한다. 탭은 이미 상세 보기라 삭제 버튼을 따로 두면
                            // 작은 썸네일 위에 눌릴 것이 둘이 되어 오탭이 난다.
                            onLongPress: () => _confirmDelete(
                              plantId: plant.plantId,
                              photoId: photo.photoId,
                              dateLabel: photoDateLabel(photo.photoDate),
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

  /// 사진 삭제는 되돌릴 수 없어 한 번 묻는다.
  ///
  /// 성공하면 목록을 다시 불러온다 — 로컬에서 빼지 않는 이유는 타임랩스·성장 비교가
  /// 같은 조회를 쓰기 때문이다. 한 곳만 지우면 나머지 화면이 지운 사진을 계속 그린다.
  Future<void> _confirmDelete({
    required String plantId,
    required String photoId,
    required String dateLabel,
  }) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('사진 삭제'),
        content: Text('$dateLabel 사진을 지울까요?\n타임랩스와 성장 비교에서도 함께 사라져요.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('그만두기'),
          ),
          FilledButton(
            key: const Key('photo_delete_confirm'),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('삭제'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }

    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref
          .read(photoRepositoryProvider)
          .deletePhoto(plantId: plantId, photoId: photoId);
      ref.invalidate(plantPhotosProvider(plantId));
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('사진을 지웠어요.')));
    } catch (error) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '사진을 지우지 못했습니다.'))),
        );
    }
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
