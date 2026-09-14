import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/photo/domain/photo_models.dart';
import 'package:potner_app/features/photo/presentation/pages/timelapse_page.dart';
import 'package:potner_app/features/photo/presentation/photo_providers.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 성장 비교다. 전·후 두 날짜의 사진을 나란히 놓고 변화를 본다.
class GrowthComparisonPage extends ConsumerStatefulWidget {
  const GrowthComparisonPage({super.key});

  @override
  ConsumerState<GrowthComparisonPage> createState() =>
      _GrowthComparisonPageState();
}

class _GrowthComparisonPageState extends ConsumerState<GrowthComparisonPage> {
  MyPlant? _plant;
  String? _beforePhotoId;
  String? _afterPhotoId;

  @override
  Widget build(BuildContext context) {
    final plants = ref.watch(myPlantsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('성장 비교'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('growth_comparison_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/growth'),
        ),
      ),
      body: SafeArea(
        child: plants.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Text(
              plantErrorMessage(error, '식물 목록을 불러오지 못했습니다.'),
              style: const TextStyle(color: AppColors.textMuted),
            ),
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
                        Icons.compare_arrows_rounded,
                        size: 52,
                        color: AppColors.primarySoft,
                      ),
                      const SizedBox(height: 14),
                      const Text(
                        '식물을 등록하면 성장 사진을 나란히 비교할 수 있어요.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: AppColors.textMuted,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: 16),
                      FilledButton(
                        key: const Key('growth_comparison_register_plant'),
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

    return photos.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (error, _) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                plantErrorMessage(error, '사진을 불러오지 못했습니다.'),
                textAlign: TextAlign.center,
                style: const TextStyle(color: AppColors.textMuted),
              ),
              const SizedBox(height: 16),
              FilledButton.tonal(
                key: const Key('growth_comparison_retry'),
                onPressed: () =>
                    ref.invalidate(plantPhotosProvider(plant.plantId)),
                child: const Text('다시 시도'),
              ),
            ],
          ),
        ),
      ),
      data: (items) {
        if (items.length < 2) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(28),
              child: Text(
                '비교하려면 사진이 두 장 이상 필요해요.\n로봇이 사진을 더 찍으면 다시 와 주세요.',
                textAlign: TextAlign.center,
                style: TextStyle(color: AppColors.textMuted, height: 1.5),
              ),
            ),
          );
        }
        final before = _findPhoto(items, _beforePhotoId) ?? items.first;
        final after = _findPhoto(items, _afterPhotoId) ?? items.last;

        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Container(
                    padding: const EdgeInsets.all(18),
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
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Text(
                              '식물 선택',
                              style: TextStyle(fontWeight: FontWeight.w700),
                            ),
                            const Spacer(),
                            DropdownButtonHideUnderline(
                              child: DropdownButton<String>(
                                key: const Key('comparison_plant_selector'),
                                value: plant.plantId,
                                items: [
                                  for (final item in plants)
                                    DropdownMenuItem(
                                      value: item.plantId,
                                      child: Text(item.name),
                                    ),
                                ],
                                onChanged: (plantId) {
                                  if (plantId == null) {
                                    return;
                                  }
                                  setState(() {
                                    _plant = plants.firstWhere(
                                      (item) => item.plantId == plantId,
                                    );
                                    _beforePhotoId = null;
                                    _afterPhotoId = null;
                                  });
                                },
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        Row(
                          children: [
                            Expanded(
                              child: _DatePickerDropdown(
                                key: const Key('comparison_before'),
                                label: '전',
                                photos: items,
                                selected: before,
                                onChanged: (photo) => setState(
                                  () => _beforePhotoId = photo.photoId,
                                ),
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: _DatePickerDropdown(
                                key: const Key('comparison_after'),
                                label: '후',
                                photos: items,
                                selected: after,
                                onChanged: (photo) => setState(
                                  () => _afterPhotoId = photo.photoId,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 18),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(24),
                    child: Row(
                      children: [
                        Expanded(
                          child: _ComparisonImage(
                            imageKey: const Key('comparison_before_image'),
                            photo: before,
                          ),
                        ),
                        const SizedBox(width: 2),
                        Expanded(
                          child: _ComparisonImage(
                            imageKey: const Key('comparison_after_image'),
                            photo: after,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 14),
                  Row(
                    children: [
                      Expanded(
                        child: _DateFooter(label: '전', date: before.photoDate),
                      ),
                      Expanded(
                        child: _DateFooter(label: '후', date: after.photoDate),
                      ),
                    ],
                  ),
                  const SizedBox(height: 24),
                  // 두 장 비교 아래에 전체 흐름을 이어 보는 진입을 둔다. 전·후만으로는 그 사이의
                  // 변화가 안 보이고, 이 화면에 이미 같은 사진 목록이 있어 다시 부르지 않는다.
                  Center(
                    child: FilledButton.icon(
                      key: const Key('growth_comparison_timelapse'),
                      // 테마가 minimumSize 를 Size.fromHeight(56) 으로 둬서 기본값이면 가로를
                      // 꽉 채운다. 디자인은 가운데 알약 모양이라 최소 너비를 푼다.
                      style: FilledButton.styleFrom(
                        minimumSize: const Size(0, 48),
                        padding: const EdgeInsets.symmetric(horizontal: 28),
                        shape: const StadiumBorder(),
                      ),
                      onPressed: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => TimelapsePage(
                            plantName: plant.name,
                            photos: items,
                          ),
                        ),
                      ),
                      icon: const Icon(Icons.play_circle_outline_rounded),
                      label: const Text('타임랩스 보기'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  static PlantPhoto? _findPhoto(List<PlantPhoto> photos, String? photoId) {
    if (photoId == null) {
      return null;
    }
    for (final photo in photos) {
      if (photo.photoId == photoId) {
        return photo;
      }
    }
    return null;
  }
}

class _DatePickerDropdown extends StatelessWidget {
  const _DatePickerDropdown({
    required this.label,
    required this.photos,
    required this.selected,
    required this.onChanged,
    super.key,
  });

  final String label;
  final List<PlantPhoto> photos;
  final PlantPhoto selected;
  final ValueChanged<PlantPhoto> onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(color: AppColors.textMuted)),
        const SizedBox(height: 4),
        DropdownButtonFormField<String>(
          initialValue: selected.photoId,
          isExpanded: true,
          decoration: const InputDecoration(
            contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            prefixIcon: Icon(Icons.calendar_today_outlined, size: 18),
          ),
          items: [
            for (final photo in photos)
              DropdownMenuItem(
                value: photo.photoId,
                child: Text(
                  photoDateShort(photo.photoDate),
                  style: const TextStyle(fontSize: 14),
                ),
              ),
          ],
          onChanged: (photoId) {
            if (photoId == null) {
              return;
            }
            onChanged(photos.firstWhere((photo) => photo.photoId == photoId));
          },
        ),
      ],
    );
  }
}

class _ComparisonImage extends StatelessWidget {
  const _ComparisonImage({required this.imageKey, required this.photo});

  final Key imageKey;
  final PlantPhoto photo;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 0.72,
      child: Stack(
        fit: StackFit.expand,
        children: [
          Image.network(
            photo.originalUrl,
            key: imageKey,
            fit: BoxFit.cover,
            errorBuilder: (_, _, _) => const ColoredBox(
              color: AppColors.surfaceLow,
              child: Icon(
                Icons.image_not_supported_outlined,
                color: AppColors.textMuted,
              ),
            ),
          ),
          Positioned(
            left: 8,
            top: 8,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.85),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                photoDateShort(photo.photoDate),
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: AppColors.text,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _DateFooter extends StatelessWidget {
  const _DateFooter({required this.label, required this.date});

  final String label;
  final DateTime date;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text(label, style: const TextStyle(color: AppColors.textMuted)),
        const SizedBox(height: 2),
        Text(
          photoDateShort(date),
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ],
    );
  }
}
