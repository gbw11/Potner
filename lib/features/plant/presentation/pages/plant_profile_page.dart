import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/home/presentation/controllers/home_controller.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';
import 'package:potner_app/features/plant/domain/plant_detail.dart';
import 'package:potner_app/features/plant/domain/plant_reference.dart';
import 'package:potner_app/features/plant/presentation/pages/plant_list_page.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

final plantDetailProvider = FutureProvider.autoDispose
    .family<PlantDetail, String>((ref, plantId) {
      return ref.watch(plantRepositoryProvider).getPlantDetail(plantId);
    });

final growthStagesProvider = FutureProvider.autoDispose
    .family<List<GrowthStageOption>, String>((ref, speciesId) {
      return ref.watch(plantRepositoryProvider).getGrowthStages(speciesId);
    });

/// 식물 프로필이다. 이름·데려온 날짜·성장 단계를 이 화면에서 바로 고친다.
class PlantProfilePage extends ConsumerWidget {
  const PlantProfilePage({required this.plantId, super.key});

  final String plantId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(plantDetailProvider(plantId));

    return Scaffold(
      appBar: AppBar(
        title: const Text('식물 프로필'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('plant_profile_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/plants'),
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
                    plantErrorMessage(error, '식물 정보를 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('plant_profile_retry'),
                    onPressed: () =>
                        ref.invalidate(plantDetailProvider(plantId)),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (data) => _ProfileForm(key: ValueKey(data), detail: data),
        ),
      ),
    );
  }
}

class _ProfileForm extends ConsumerStatefulWidget {
  const _ProfileForm({required this.detail, super.key});

  final PlantDetail detail;

  @override
  ConsumerState<_ProfileForm> createState() => _ProfileFormState();
}

class _ProfileFormState extends ConsumerState<_ProfileForm> {
  late final TextEditingController _nameController;
  DateTime? _adoptedDate;
  late GrowthStageOption _stage;
  bool _isSaving = false;

  PlantDetail get _detail => widget.detail;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: _detail.name);
    _adoptedDate = _detail.adoptedDate;
    _stage = _detail.lifeStage;
  }

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _ProfilePhoto(url: _detail.thumbnailUrl),
              const SizedBox(height: 24),
              Container(
                padding: const EdgeInsets.fromLTRB(22, 10, 22, 10),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(26),
                  boxShadow: [
                    BoxShadow(
                      color: AppColors.primary.withValues(alpha: 0.08),
                      blurRadius: 20,
                      offset: const Offset(0, 6),
                    ),
                  ],
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextFormField(
                      key: const Key('profile_name'),
                      controller: _nameController,
                      enabled: !_isSaving,
                      maxLength: 50,
                      decoration: const InputDecoration(
                        labelText: '이름',
                        counterText: '',
                        suffixIcon: Icon(Icons.edit_outlined, size: 18),
                        border: UnderlineInputBorder(),
                        enabledBorder: UnderlineInputBorder(),
                        filled: false,
                      ),
                    ),
                    const SizedBox(height: 14),
                    InkWell(
                      key: const Key('profile_adopted_date'),
                      onTap: _isSaving ? null : _pickAdoptedDate,
                      child: InputDecorator(
                        decoration: const InputDecoration(
                          labelText: '데려온 날짜',
                          suffixIcon: Icon(
                            Icons.calendar_month_outlined,
                            size: 20,
                          ),
                          border: UnderlineInputBorder(),
                          enabledBorder: UnderlineInputBorder(),
                          filled: false,
                        ),
                        isEmpty: _adoptedDate == null,
                        child: _adoptedDate == null
                            ? null
                            : Text(_formatDate(_adoptedDate!)),
                      ),
                    ),
                    const SizedBox(height: 14),
                    InputDecorator(
                      decoration: const InputDecoration(
                        labelText: '나이',
                        border: UnderlineInputBorder(),
                        enabledBorder: UnderlineInputBorder(),
                        filled: false,
                      ),
                      child: Row(
                        children: [
                          Text(_ageDaysLabel()),
                          if (_ageDays() != null) ...[
                            const SizedBox(width: 8),
                            Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8,
                                vertical: 2,
                              ),
                              decoration: BoxDecoration(
                                color: const Color(0xFFCCEBC0),
                                borderRadius: BorderRadius.circular(999),
                              ),
                              child: Text(
                                'D+${_ageDays()}',
                                style: const TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w700,
                                  color: AppColors.primary,
                                ),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(height: 14),
                    _buildStageField(),
                    const SizedBox(height: 10),
                  ],
                ),
              ),
              const SizedBox(height: 26),
              FilledButton(
                key: const Key('profile_save'),
                onPressed: _isSaving ? null : _save,
                child: _isSaving
                    ? const SizedBox.square(
                        dimension: 22,
                        child: CircularProgressIndicator(
                          strokeWidth: 2.4,
                          color: Colors.white,
                        ),
                      )
                    : const Text('확인'),
              ),
              const SizedBox(height: 14),
              FilledButton.tonal(
                key: const Key('profile_environment'),
                style: FilledButton.styleFrom(
                  backgroundColor: const Color(0xFFCCEBC0),
                  foregroundColor: AppColors.primary,
                  disabledBackgroundColor: const Color(0xFFE3EEDC),
                  disabledForegroundColor: const Color(0xFF6D7868),
                ),
                onPressed: _isSaving
                    ? null
                    : () => context.go(
                        withPreviousNavigationLocation(
                          context,
                          '/plants/${_detail.plantId}/environment',
                        ),
                      ),
                child: const Text('환경 정보'),
              ),
              const SizedBox(height: 14),
              FilledButton.tonal(
                key: const Key('profile_care_settings'),
                style: FilledButton.styleFrom(
                  backgroundColor: const Color(0xFFF5DFA1),
                  foregroundColor: const Color(0xFF5C4A12),
                ),
                onPressed: _isSaving
                    ? null
                    : () => context.go(
                        withPreviousNavigationLocation(
                          context,
                          '/plants/${_detail.plantId}/care',
                        ),
                      ),
                child: const Text('케어 설정'),
              ),
              const SizedBox(height: 14),
              OutlinedButton(
                key: const Key('profile_delete'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.error,
                  side: const BorderSide(color: AppColors.error),
                ),
                onPressed: _isSaving ? null : _confirmAndDelete,
                child: const Text('식물 삭제'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildStageField() {
    final stages = ref.watch(growthStagesProvider(_detail.speciesId));
    return stages.when(
      loading: () => InputDecorator(
        decoration: const InputDecoration(
          labelText: '성장 단계',
          border: UnderlineInputBorder(),
          enabledBorder: UnderlineInputBorder(),
          filled: false,
        ),
        child: Text(_stage.name),
      ),
      error: (_, _) => InputDecorator(
        decoration: const InputDecoration(
          labelText: '성장 단계',
          border: UnderlineInputBorder(),
          enabledBorder: UnderlineInputBorder(),
          filled: false,
        ),
        child: Text(_stage.name),
      ),
      data: (options) {
        final selected = options.firstWhere(
          (option) => option.lifeStageId == _stage.lifeStageId,
          orElse: () => _stage,
        );
        return DropdownButtonFormField<GrowthStageOption>(
          key: const Key('profile_growth_stage'),
          initialValue: selected,
          decoration: const InputDecoration(
            labelText: '성장 단계',
            border: UnderlineInputBorder(),
            enabledBorder: UnderlineInputBorder(),
            filled: false,
          ),
          items: [
            for (final option in options)
              DropdownMenuItem(value: option, child: Text(option.name)),
          ],
          onChanged: _isSaving
              ? null
              : (value) {
                  if (value != null) {
                    setState(() => _stage = value);
                  }
                },
        );
      },
    );
  }

  int? _ageDays() {
    final adopted = _adoptedDate;
    if (adopted == null) {
      return null;
    }
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final from = DateTime(adopted.year, adopted.month, adopted.day);
    final days = today.difference(from).inDays;
    return days < 0 ? null : days;
  }

  String _ageDaysLabel() {
    final days = _ageDays();
    return days == null ? '데려온 날짜를 입력하면 계산돼요' : '$days일';
  }

  String _formatDate(DateTime value) {
    return '${value.year}년 ${value.month}월 ${value.day}일';
  }

  Future<void> _pickAdoptedDate() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _adoptedDate ?? now,
      firstDate: DateTime(2000),
      lastDate: now,
    );
    if (picked != null && mounted) {
      setState(() => _adoptedDate = picked);
    }
  }

  Future<void> _save() async {
    final messenger = ScaffoldMessenger.of(context);
    final repository = ref.read(plantRepositoryProvider);
    final newName = _nameController.text.trim();
    if (newName.isEmpty) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('식물 이름을 입력해 주세요.')));
      return;
    }

    final nameChanged = newName != _detail.name;
    final dateChanged = _adoptedDate != _detail.adoptedDate;
    final stageChanged = _stage.lifeStageId != _detail.lifeStage.lifeStageId;
    if (!nameChanged && !dateChanged && !stageChanged) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('변경된 내용이 없어요.')));
      return;
    }

    setState(() => _isSaving = true);
    try {
      if (nameChanged || dateChanged) {
        await repository.updatePlant(
          plantId: _detail.plantId,
          name: nameChanged ? newName : null,
          adoptedDate: dateChanged ? _adoptedDate : null,
        );
      }
      if (stageChanged) {
        await repository.changeLifeStage(
          plantId: _detail.plantId,
          lifeStageId: _stage.lifeStageId,
        );
      }
      if (!mounted) {
        return;
      }
      setState(() => _isSaving = false);
      ref.invalidate(plantDetailProvider(_detail.plantId));
      ref.invalidate(myPlantsProvider);
      ref.invalidate(homeControllerProvider);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('식물 정보를 저장했어요.')));
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _isSaving = false);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(plantErrorMessage(error, '식물 정보를 저장하지 못했습니다.')),
          ),
        );
    }
  }

  Future<void> _confirmAndDelete() async {
    final messenger = ScaffoldMessenger.of(context);
    final router = GoRouter.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('${_detail.name} 삭제'),
        content: const Text('식물을 삭제하면 기록도 함께 사라져요. 정말 삭제할까요?'),
        actions: [
          TextButton(
            key: const Key('profile_delete_cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('취소'),
          ),
          FilledButton(
            key: const Key('profile_delete_confirm'),
            style: FilledButton.styleFrom(backgroundColor: AppColors.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('삭제'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }

    setState(() => _isSaving = true);
    try {
      await ref.read(plantRepositoryProvider).deletePlant(_detail.plantId);
      if (!mounted) {
        return;
      }
      ref.invalidate(myPlantsProvider);
      ref.invalidate(homeControllerProvider);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text('${_detail.name}을(를) 삭제했어요.')));
      router.go('/plants');
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _isSaving = false);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '식물을 삭제하지 못했습니다.'))),
        );
    }
  }
}

class _ProfilePhoto extends StatelessWidget {
  const _ProfilePhoto({required this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SizedBox(
        width: 156,
        height: 156,
        child: Stack(
          children: [
            Positioned.fill(
              child: PlantThumbnail(url: url, size: 156, circular: true),
            ),
            Positioned(
              right: 4,
              bottom: 4,
              child: Container(
                width: 40,
                height: 40,
                decoration: const BoxDecoration(
                  color: AppColors.primary,
                  shape: BoxShape.circle,
                ),
                child: IconButton(
                  key: const Key('profile_photo_button'),
                  padding: EdgeInsets.zero,
                  tooltip: '대표 사진 변경',
                  color: Colors.white,
                  iconSize: 20,
                  icon: const Icon(Icons.photo_camera_outlined),
                  onPressed: () {
                    ScaffoldMessenger.of(context)
                      ..hideCurrentSnackBar()
                      ..showSnackBar(
                        const SnackBar(
                          content: Text('대표 사진 변경은 다음 작업에서 연결합니다.'),
                        ),
                      );
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
