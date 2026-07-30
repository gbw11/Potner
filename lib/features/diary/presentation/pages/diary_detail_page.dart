import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/diary/data/diary_repository_impl.dart';
import 'package:potner_app/features/diary/domain/diary_models.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

final diaryDetailProvider = FutureProvider.autoDispose
    .family<DiaryDetail, ({String plantId, String diaryId})>((ref, arg) {
      return ref
          .read(diaryRepositoryProvider)
          .getDiary(plantId: arg.plantId, diaryId: arg.diaryId);
    });

/// 일기 상세다. 본문과 그날 장치 사진을 함께 보여 준다.
class DiaryDetailPage extends ConsumerWidget {
  const DiaryDetailPage({
    required this.plantId,
    required this.diaryId,
    super.key,
  });

  final String plantId;
  final String diaryId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(
      diaryDetailProvider((plantId: plantId, diaryId: diaryId)),
    );

    return Scaffold(
      appBar: AppBar(
        title: const Text('일기 상세'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('diary_detail_back'),
          onPressed: () =>
              context.canPop() ? context.pop() : context.go('/growth/diary'),
        ),
      ),
      body: SafeArea(
        child: detail.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    plantErrorMessage(error, '일기를 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('diary_detail_retry'),
                    onPressed: () => ref.invalidate(
                      diaryDetailProvider((
                        plantId: plantId,
                        diaryId: diaryId,
                      )),
                    ),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (diary) => SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${diary.diaryDate.year}년 ${diary.diaryDate.month}월 '
                      '${diary.diaryDate.day}일',
                      style: const TextStyle(
                        color: AppColors.primaryContainer,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      diary.title,
                      style: Theme.of(context).textTheme.headlineMedium
                          ?.copyWith(color: AppColors.text),
                    ),
                    const SizedBox(height: 18),
                    if (diary.photoUrl != null)
                      ClipRRect(
                        borderRadius: BorderRadius.circular(24),
                        child: Stack(
                          children: [
                            AspectRatio(
                              aspectRatio: 1,
                              child: Image.network(
                                diary.photoUrl!,
                                key: const Key('diary_photo'),
                                fit: BoxFit.cover,
                                errorBuilder: (_, _, _) => const ColoredBox(
                                  color: AppColors.surfaceLow,
                                  child: Center(
                                    child: Icon(
                                      Icons.image_not_supported_outlined,
                                      color: AppColors.textMuted,
                                    ),
                                  ),
                                ),
                              ),
                            ),
                            Positioned(
                              right: 12,
                              bottom: 12,
                              child: Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 6,
                                ),
                                decoration: BoxDecoration(
                                  color: Colors.white.withValues(alpha: 0.9),
                                  borderRadius: BorderRadius.circular(999),
                                ),
                                child: const Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(
                                      Icons.photo_camera_outlined,
                                      size: 16,
                                      color: AppColors.primary,
                                    ),
                                    SizedBox(width: 6),
                                    Text(
                                      "Today's Shot",
                                      style: TextStyle(
                                        fontSize: 12,
                                        fontWeight: FontWeight.w700,
                                        color: AppColors.primary,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ],
                        ),
                      )
                    else
                      Container(
                        padding: const EdgeInsets.all(20),
                        decoration: BoxDecoration(
                          color: AppColors.surfaceLow,
                          borderRadius: BorderRadius.circular(20),
                        ),
                        child: const Row(
                          children: [
                            Icon(
                              Icons.no_photography_outlined,
                              color: AppColors.textMuted,
                            ),
                            SizedBox(width: 10),
                            Text(
                              '이 날은 촬영된 사진이 없어요.',
                              style: TextStyle(color: AppColors.textMuted),
                            ),
                          ],
                        ),
                      ),
                    const SizedBox(height: 22),
                    const Text(
                      '❝',
                      style: TextStyle(
                        fontSize: 30,
                        color: AppColors.primarySoft,
                      ),
                    ),
                    Text(
                      diary.content,
                      style: const TextStyle(fontSize: 16, height: 1.7),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
