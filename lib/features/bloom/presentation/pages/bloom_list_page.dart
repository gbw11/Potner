import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/bloom/data/bloom_repository_impl.dart';
import 'package:potner_app/features/bloom/domain/bloom_models.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

final bloomsProvider = FutureProvider.autoDispose<List<BloomRecord>>((ref) {
  return ref.watch(bloomRepositoryProvider).getBlooms();
});

/// 개화 기록이다. 자동 감지된 개화 이력을 모아 본다.
class BloomListPage extends ConsumerWidget {
  const BloomListPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final blooms = ref.watch(bloomsProvider);
    final plants = ref.watch(myPlantsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('개화 기록'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('bloom_list_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/growth'),
        ),
      ),
      body: SafeArea(
        child: plants.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    plantErrorMessage(error, '식물 목록을 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('bloom_list_retry'),
                    onPressed: () => ref.invalidate(myPlantsProvider),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (plantItems) => plantItems.isEmpty
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(28),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(
                          Icons.filter_vintage_outlined,
                          size: 52,
                          color: AppColors.primarySoft,
                        ),
                        const SizedBox(height: 14),
                        const Text(
                          '식물을 등록하면 꽃이 피어난 특별한 순간을 볼 수 있어요',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: AppColors.textMuted,
                            height: 1.5,
                          ),
                        ),
                        const SizedBox(height: 16),
                        FilledButton(
                          key: const Key('bloom_register_plant'),
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
                )
              : blooms.when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (error, _) => Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            plantErrorMessage(error, '개화 기록을 불러오지 못했습니다.'),
                            textAlign: TextAlign.center,
                            style: const TextStyle(color: AppColors.textMuted),
                          ),
                          const SizedBox(height: 16),
                          FilledButton.tonal(
                            key: const Key('bloom_records_retry'),
                            onPressed: () => ref.invalidate(bloomsProvider),
                            child: const Text('다시 시도'),
                          ),
                        ],
                      ),
                    ),
                  ),
                  data: (items) => items.isEmpty
                      ? const Center(
                          child: Padding(
                            padding: EdgeInsets.all(28),
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  Icons.filter_vintage_outlined,
                                  size: 52,
                                  color: AppColors.primarySoft,
                                ),
                                SizedBox(height: 14),
                                Text(
                                  '아직 기록된 개화가 없어요.\n꽃이 피어난 특별한 날을 남겨 보세요!',
                                  textAlign: TextAlign.center,
                                  style: TextStyle(
                                    color: AppColors.textMuted,
                                    height: 1.5,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        )
                      : RefreshIndicator(
                          onRefresh: () => ref.refresh(bloomsProvider.future),
                          child: ListView(
                            key: const Key('bloom_list'),
                            physics: const AlwaysScrollableScrollPhysics(),
                            padding: const EdgeInsets.fromLTRB(20, 12, 20, 96),
                            children: [
                              for (final bloom in items) ...[
                                _BloomCard(bloom: bloom),
                                const SizedBox(height: 14),
                              ],
                            ],
                          ),
                        ),
                ),
        ),
      ),
    );
  }
}

class _BloomCard extends ConsumerWidget {
  const _BloomCard({required this.bloom});

  final BloomRecord bloom;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      key: Key('bloom_card_${bloom.bloomId}'),
      padding: const EdgeInsets.all(16),
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
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: const BoxDecoration(
              color: Color(0xFFF5DFA1),
              shape: BoxShape.circle,
            ),
            child: const Icon(
              Icons.local_florist_rounded,
              color: Color(0xFFB07E09),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      bloom.plantName,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w800,
                        color: AppColors.text,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: bloom.isUserRecorded
                            ? const Color(0xFFCCEBC0)
                            : AppColors.surfaceLow,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        bloom.isUserRecorded ? '직접 기록' : '자동 감지',
                        style: const TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          color: AppColors.primary,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  '${bloom.bloomDate.year}년 ${bloom.bloomDate.month}월 '
                  '${bloom.bloomDate.day}일에 꽃이 피었어요',
                  style: const TextStyle(color: AppColors.textMuted),
                ),
                if (bloom.note != null) ...[
                  const SizedBox(height: 6),
                  Text(bloom.note!, style: const TextStyle(height: 1.4)),
                ],
              ],
            ),
          ),
          IconButton(
            key: Key('delete_bloom_${bloom.bloomId}'),
            tooltip: '삭제',
            color: AppColors.textMuted,
            icon: const Icon(Icons.delete_outline_rounded, size: 20),
            onPressed: () => _confirmAndDelete(context, ref),
          ),
        ],
      ),
    );
  }

  Future<void> _confirmAndDelete(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('개화 기록 삭제'),
        content: const Text('잘못 남긴 기록을 지울까요? 되돌릴 수 없어요.'),
        actions: [
          TextButton(
            key: const Key('bloom_delete_cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('취소'),
          ),
          FilledButton(
            key: const Key('bloom_delete_confirm'),
            style: FilledButton.styleFrom(backgroundColor: AppColors.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('삭제'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) {
      return;
    }

    try {
      await ref
          .read(bloomRepositoryProvider)
          .deleteBloom(plantId: bloom.plantId, bloomId: bloom.bloomId);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('개화 기록을 삭제했어요.')));
    } catch (error) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '기록을 삭제하지 못했습니다.'))),
        );
    } finally {
      ref.invalidate(bloomsProvider);
    }
  }
}
