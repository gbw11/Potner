import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/home/presentation/controllers/home_controller.dart';
import 'package:potner_app/features/photo/data/photo_repository_impl.dart';
import 'package:potner_app/features/photo/domain/photo_models.dart';
import 'package:potner_app/features/photo/presentation/photo_providers.dart';
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
  final _imagePicker = ImagePicker();
  DateTime? _adoptedDate;
  late GrowthStageOption _stage;
  bool _isSaving = false;
  bool _isChangingPhoto = false;

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
              _ProfilePhoto(
                url: _detail.thumbnailUrl,
                isBusy: _isChangingPhoto,
                onChangePressed: _showPhotoSourceSheet,
              ),
              // 성격은 사진 바로 아래다. 이름·날짜 입력 칸보다 위에 둬야 사진과 함께
              // '이 아이가 어떤 성격인가' 로 읽힌다. 성격이 없으면 아무것도 그리지 않는다.
              if (_detail.persona case final persona? when !persona.isEmpty) ...[
                const SizedBox(height: 16),
                _PersonaCard(persona: persona),
              ],
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

  /// 대표 사진을 바꾸는 세 갈래다. 앨범·카메라로 새로 올리거나, 로봇이 찍어 둔 포토 로그에서
  /// 고르거나, 내린다. 서버가 세 엔드포인트를 모두 제공한다.
  Future<void> _showPhotoSourceSheet() async {
    FocusManager.instance.primaryFocus?.unfocus();
    final action = await showModalBottomSheet<_PhotoAction>(
      context: context,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                '대표 사진 변경',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 8),
              ListTile(
                key: const Key('profile_photo_camera'),
                leading: const Icon(Icons.photo_camera_outlined),
                title: const Text('카메라로 촬영'),
                onTap: () =>
                    Navigator.of(sheetContext).pop(_PhotoAction.camera),
              ),
              ListTile(
                key: const Key('profile_photo_gallery'),
                leading: const Icon(Icons.photo_library_outlined),
                title: const Text('앨범에서 선택'),
                onTap: () =>
                    Navigator.of(sheetContext).pop(_PhotoAction.gallery),
              ),
              ListTile(
                key: const Key('profile_photo_from_log'),
                leading: const Icon(Icons.grid_view_rounded),
                title: const Text('포토 로그에서 고르기'),
                subtitle: const Text('로봇이 매일 찍은 사진 중에서 선택해요.'),
                onTap: () =>
                    Navigator.of(sheetContext).pop(_PhotoAction.photoLog),
              ),
              if (_detail.thumbnailUrl != null)
                ListTile(
                  key: const Key('profile_photo_clear'),
                  leading: const Icon(
                    Icons.hide_image_outlined,
                    color: AppColors.error,
                  ),
                  title: const Text(
                    '대표 사진 해제',
                    style: TextStyle(color: AppColors.error),
                  ),
                  onTap: () =>
                      Navigator.of(sheetContext).pop(_PhotoAction.clear),
                ),
            ],
          ),
        ),
      ),
    );
    if (action == null || !mounted) {
      return;
    }

    switch (action) {
      case _PhotoAction.camera:
        await _uploadPickedPhoto(ImageSource.camera);
      case _PhotoAction.gallery:
        await _uploadPickedPhoto(ImageSource.gallery);
      case _PhotoAction.photoLog:
        await _selectFromPhotoLog();
      case _PhotoAction.clear:
        await _runPhotoChange(
          () => ref
              .read(photoRepositoryProvider)
              .clearRepresentativePhoto(plantId: _detail.plantId),
          successMessage: '대표 사진을 해제했어요.',
          failureMessage: '대표 사진을 해제하지 못했습니다.',
        );
    }
  }

  Future<void> _uploadPickedPhoto(ImageSource source) async {
    final XFile? picked;
    try {
      picked = await _imagePicker.pickImage(
        source: source,
        imageQuality: 85,
        maxWidth: 2048,
        maxHeight: 2048,
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
          ..hideCurrentSnackBar()
          ..showSnackBar(const SnackBar(content: Text('사진을 불러오지 못했어요.')));
      }
      return;
    }
    if (picked == null || !mounted) {
      return;
    }

    await _runPhotoChange(
      () => ref.read(photoRepositoryProvider).uploadRepresentativePhoto(
        plantId: _detail.plantId,
        filePath: picked!.path,
        fileName: picked.name,
      ),
      successMessage: '대표 사진을 변경했어요.',
      failureMessage: '대표 사진을 올리지 못했습니다.',
    );
  }

  Future<void> _selectFromPhotoLog() async {
    final photoId = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (sheetContext) => _PhotoLogPicker(plantId: _detail.plantId),
    );
    if (photoId == null || !mounted) {
      return;
    }

    await _runPhotoChange(
      () => ref.read(photoRepositoryProvider).selectRepresentativePhoto(
        plantId: _detail.plantId,
        photoId: photoId,
      ),
      successMessage: '대표 사진을 변경했어요.',
      failureMessage: '대표 사진을 변경하지 못했습니다.',
    );
  }

  /// 세 갈래가 성공·실패 처리와 갱신 대상이 같다. 대표 사진은 프로필뿐 아니라 식물 목록과
  /// 홈 썸네일에도 나가므로 셋을 함께 무효화해야 화면 간 값이 어긋나지 않는다.
  Future<void> _runPhotoChange(
    Future<void> Function() action, {
    required String successMessage,
    required String failureMessage,
  }) async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _isChangingPhoto = true);
    try {
      await action();
      if (!mounted) {
        return;
      }
      setState(() => _isChangingPhoto = false);
      ref.invalidate(plantDetailProvider(_detail.plantId));
      ref.invalidate(plantPhotosProvider(_detail.plantId));
      ref.invalidate(myPlantsProvider);
      ref.invalidate(homeControllerProvider);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(successMessage)));
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _isChangingPhoto = false);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, failureMessage))),
        );
    }
  }
}

enum _PhotoAction { camera, gallery, photoLog, clear }

/// 포토 로그에서 대표 사진을 고르는 그리드다. 목록은 장치가 찍은 사진만이라(서버가
/// `source=DEVICE` 로 걸러 준다) 로봇이 촬영을 시작하기 전에는 비어 있는 것이 정상이다.
class _PhotoLogPicker extends ConsumerWidget {
  const _PhotoLogPicker({required this.plantId});

  final String plantId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final photos = ref.watch(plantPhotosProvider(plantId));

    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * 0.7,
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                '포토 로그에서 고르기',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 12),
              Flexible(
                child: photos.when(
                  loading: () => const Padding(
                    padding: EdgeInsets.symmetric(vertical: 40),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                  error: (error, _) => Padding(
                    padding: const EdgeInsets.symmetric(vertical: 32),
                    child: Text(
                      plantErrorMessage(error, '사진을 불러오지 못했습니다.'),
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: AppColors.textMuted),
                    ),
                  ),
                  data: (items) => items.isEmpty
                      ? const Padding(
                          padding: EdgeInsets.symmetric(vertical: 32),
                          child: Text(
                            '아직 촬영된 사진이 없어요.\n로봇이 사진을 찍으면 여기에서 고를 수 있어요.',
                            key: Key('profile_photo_log_empty'),
                            textAlign: TextAlign.center,
                            style: TextStyle(color: AppColors.textMuted),
                          ),
                        )
                      : GridView.builder(
                          shrinkWrap: true,
                          padding: EdgeInsets.zero,
                          gridDelegate:
                              const SliverGridDelegateWithFixedCrossAxisCount(
                                crossAxisCount: 3,
                                crossAxisSpacing: 8,
                                mainAxisSpacing: 8,
                              ),
                          // 최신 사진이 위로 오는 것이 고를 때 자연스럽다. 서버는 타임랩스
                          // 순서(오래된 순)로 주므로 여기서만 뒤집는다.
                          itemCount: items.length,
                          itemBuilder: (context, index) {
                            final photo = items[items.length - 1 - index];
                            return _PhotoLogTile(photo: photo);
                          },
                        ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PhotoLogTile extends StatelessWidget {
  const _PhotoLogTile({required this.photo});

  final PlantPhoto photo;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      key: Key('profile_photo_option_${photo.photoId}'),
      borderRadius: BorderRadius.circular(12),
      onTap: () => Navigator.of(context).pop(photo.photoId),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Stack(
          fit: StackFit.expand,
          children: [
            Image.network(
              photo.thumbnailUrl,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => const ColoredBox(
                color: AppColors.surfaceLow,
                child: Icon(
                  Icons.image_not_supported_outlined,
                  color: AppColors.primarySoft,
                ),
              ),
            ),
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: ColoredBox(
                color: Colors.black38,
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Text(
                    photoDateShort(photo.photoDate),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Colors.white, fontSize: 11),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 종에 정해진 성격이다. 해시태그로 먼저 훑고, 아래 문장으로 뜻을 읽는다.
///
/// 일기의 말투가 여기서 나온다. 사용자가 일기를 읽다가 "얘는 왜 이렇게 말하지" 할 때
/// 근거를 찾을 수 있는 유일한 화면이라, 꽃말과 성격을 같은 카드에 붙여 둔다.
class _PersonaCard extends StatelessWidget {
  const _PersonaCard({required this.persona});

  final SpeciesPersona persona;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const Key('profile_persona'),
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 18),
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
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.local_florist_outlined,
                size: 18,
                color: AppColors.primary,
              ),
              const SizedBox(width: 6),
              Text(
                '${persona.characterName}의 성격',
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w800,
                  color: AppColors.text,
                ),
              ),
            ],
          ),
          if (persona.hashtags.isNotEmpty) ...[
            const SizedBox(height: 12),
            // Wrap 이라 글자 크기를 키워도 칩이 잘리지 않고 다음 줄로 넘어간다.
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final tag in persona.hashtags) _PersonaTag(label: tag),
              ],
            ),
          ],
          if (persona.personality.isNotEmpty) ...[
            const SizedBox(height: 14),
            Text(
              persona.personality,
              style: const TextStyle(
                fontSize: 14,
                height: 1.5,
                fontWeight: FontWeight.w600,
                color: AppColors.text,
              ),
            ),
          ],
          if (persona.coreValue.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              '가장 중요하게 여기는 것은 ${persona.coreValue}이에요.',
              style: const TextStyle(
                fontSize: 13,
                height: 1.5,
                color: AppColors.textMuted,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// 해시태그 칩 하나다. 첫 번째가 꽃말이지만 생김새를 나누지 않는다.
/// 꽃말과 성격 키워드는 사용자에게 같은 층위로 읽히는 편이 자연스럽다.
class _PersonaTag extends StatelessWidget {
  const _PersonaTag({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: const Color(0xFFCCEBC0),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        '#$label',
        style: const TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w700,
          color: AppColors.primary,
        ),
      ),
    );
  }
}

class _ProfilePhoto extends StatelessWidget {
  const _ProfilePhoto({
    required this.url,
    required this.isBusy,
    required this.onChangePressed,
  });

  final String? url;
  final bool isBusy;
  final VoidCallback onChangePressed;

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
                  icon: isBusy
                      ? const SizedBox.square(
                          dimension: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(Icons.photo_camera_outlined),
                  onPressed: isBusy ? null : onChangePressed,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
