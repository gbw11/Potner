import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/home/presentation/controllers/home_controller.dart';
import 'package:potner_app/features/photo/data/photo_repository_impl.dart';
import 'package:potner_app/features/photo/domain/photo_models.dart';
import 'package:potner_app/features/photo/presentation/photo_providers.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 사진 상세다. 원본을 크게 보여 주고 대표 사진 지정으로 잇는다.
class PhotoDetailPage extends ConsumerWidget {
  const PhotoDetailPage({
    required this.plantId,
    required this.photoId,
    super.key,
  });

  final String plantId;
  final String photoId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final photos = ref.watch(plantPhotosProvider(plantId));

    return Scaffold(
      body: SafeArea(
        child: photos.when(
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
                    key: const Key('photo_detail_retry'),
                    onPressed: () =>
                        ref.invalidate(plantPhotosProvider(plantId)),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (items) {
            PlantPhoto? photo;
            for (final item in items) {
              if (item.photoId == photoId) {
                photo = item;
                break;
              }
            }
            if (photo == null) {
              return const Center(
                child: Text(
                  '사진을 찾을 수 없습니다.',
                  style: TextStyle(color: AppColors.textMuted),
                ),
              );
            }
            return _PhotoDetailBody(plantId: plantId, photo: photo);
          },
        ),
      ),
    );
  }
}

class _PhotoDetailBody extends ConsumerStatefulWidget {
  const _PhotoDetailBody({required this.plantId, required this.photo});

  final String plantId;
  final PlantPhoto photo;

  @override
  ConsumerState<_PhotoDetailBody> createState() => _PhotoDetailBodyState();
}

class _PhotoDetailBodyState extends ConsumerState<_PhotoDetailBody> {
  bool _isSubmitting = false;

  @override
  Widget build(BuildContext context) {
    final photo = widget.photo;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
          child: Row(
            children: [
              BackButton(
                key: const Key('photo_detail_back'),
                onPressed: () => context.go(
                  withCurrentNavigationOrigin(context, '/growth/photos'),
                ),
              ),
              Expanded(
                child: Text(
                  photoDateShort(photo.photoDate),
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w800,
                    color: AppColors.text,
                  ),
                ),
              ),
              const SizedBox(width: 48),
            ],
          ),
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(26),
                      child: Image.network(
                        photo.originalUrl,
                        key: const Key('photo_detail_image'),
                        fit: BoxFit.cover,
                        errorBuilder: (_, _, _) => Container(
                          height: 320,
                          color: AppColors.surfaceLow,
                          child: const Icon(
                            Icons.image_not_supported_outlined,
                            color: AppColors.textMuted,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 24),
                    FilledButton.icon(
                      key: const Key('photo_set_representative'),
                      onPressed: _isSubmitting ? null : _setRepresentative,
                      icon: _isSubmitting
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(
                                strokeWidth: 2.2,
                                color: Colors.white,
                              ),
                            )
                          : const Icon(Icons.star_outline_rounded),
                      label: const Text('대표 사진으로 지정'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Future<void> _setRepresentative() async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isSubmitting = true);
    try {
      await ref
          .read(photoRepositoryProvider)
          .selectRepresentativePhoto(
            plantId: widget.plantId,
            photoId: widget.photo.photoId,
          );
      if (!mounted) {
        return;
      }
      ref.invalidate(myPlantsProvider);
      ref.invalidate(homeControllerProvider);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('대표 사진으로 지정했어요.')));
    } catch (error) {
      if (!mounted) {
        return;
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(plantErrorMessage(error, '대표 사진을 지정하지 못했습니다.')),
          ),
        );
    } finally {
      if (mounted) {
        setState(() => _isSubmitting = false);
      }
    }
  }
}
