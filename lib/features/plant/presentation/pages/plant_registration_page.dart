import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/presentation/widgets/form_error.dart';
import 'package:potner_app/features/home/presentation/controllers/home_controller.dart';
import 'package:potner_app/features/photo/data/photo_repository_impl.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';
import 'package:potner_app/features/plant/domain/plant_reference.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 대분류·소분류·자람 수준 트리는 한 번의 조회로 모두 내려온다.
final plantCategoryTreeProvider =
    FutureProvider.autoDispose<List<PlantCategoryOption>>((ref) {
      return ref.watch(plantRepositoryProvider).getCategoryTree();
    });

class PlantRegistrationPage extends ConsumerStatefulWidget {
  const PlantRegistrationPage({super.key});

  @override
  ConsumerState<PlantRegistrationPage> createState() =>
      _PlantRegistrationPageState();
}

class _PlantRegistrationPageState extends ConsumerState<PlantRegistrationPage> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  final _imagePicker = ImagePicker();

  PlantCategoryOption? _category;
  PlantSpeciesOption? _species;
  GrowthStageOption? _stage;
  DateTime? _adoptedDate;
  XFile? _selectedPhoto;
  Uint8List? _selectedPhotoBytes;
  bool _isSubmitting = false;
  bool _isPickingPhoto = false;
  String? _formError;

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tree = ref.watch(plantCategoryTreeProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('내 식물 등록하기'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('plant_registration_back'),
          onPressed: () => returnFromSharedPage(context, fallbackLocation: '/'),
        ),
      ),
      body: SafeArea(
        child: tree.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _TreeLoadFailure(
            message: _messageFor(error),
            onRetry: () => ref.invalidate(plantCategoryTreeProvider),
          ),
          data: (categories) => _buildForm(context, categories),
        ),
      ),
    );
  }

  Widget _buildForm(
    BuildContext context,
    List<PlantCategoryOption> categories,
  ) {
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onTap: () => FocusManager.instance.primaryFocus?.unfocus(),
      child: SingleChildScrollView(
        keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _PhotoPicker(
                  bytes: _selectedPhotoBytes,
                  enabled: !_isSubmitting && !_isPickingPhoto,
                  onTap: _showPhotoSourceSheet,
                ),
                const SizedBox(height: 10),
                const Text(
                  '새로운 식물 친구를 소개해 주세요!',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: AppColors.textMuted),
                ),
                const SizedBox(height: 22),
                Container(
                  padding: const EdgeInsets.all(22),
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
                  child: Form(
                    key: _formKey,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        _FieldLabel(
                          icon: Icons.spa_outlined,
                          label: '식물이름 (애칭)',
                        ),
                        const SizedBox(height: 8),
                        TextFormField(
                          key: const Key('plant_name'),
                          controller: _nameController,
                          enabled: !_isSubmitting,
                          maxLength: 50,
                          decoration: const InputDecoration(
                            hintText: '식물의 이름(애칭)을 입력하세요',
                            counterText: '',
                          ),
                          validator: (value) {
                            if (value == null || value.trim().isEmpty) {
                              return '식물 이름을 입력해 주세요.';
                            }
                            return null;
                          },
                          onChanged: (_) => _clearFormError(),
                        ),
                        const SizedBox(height: 18),
                        _FieldLabel(
                          icon: Icons.calendar_today_outlined,
                          label: '데려온 날짜',
                        ),
                        const SizedBox(height: 8),
                        _AdoptedDateField(
                          key: const Key('plant_adopted_date'),
                          value: _adoptedDate,
                          enabled: !_isSubmitting,
                          onPick: _pickAdoptedDate,
                        ),
                        const SizedBox(height: 18),
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Expanded(child: _buildCategoryField(categories)),
                            const SizedBox(width: 12),
                            Expanded(child: _buildSpeciesField()),
                          ],
                        ),
                        const SizedBox(height: 18),
                        _buildStageField(),
                        if (_formError != null) ...[
                          const SizedBox(height: 18),
                          FormError(message: _formError!),
                        ],
                        const SizedBox(height: 26),
                        FilledButton(
                          key: const Key('plant_submit'),
                          onPressed: _isSubmitting ? null : _submit,
                          child: _isSubmitting
                              ? const SizedBox.square(
                                  dimension: 22,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2.4,
                                    color: Colors.white,
                                  ),
                                )
                              : const Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Text('등록하기'),
                                    SizedBox(width: 10),
                                    Icon(Icons.arrow_forward_rounded),
                                  ],
                                ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildCategoryField(List<PlantCategoryOption> categories) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _FieldLabel(icon: Icons.grass_outlined, label: '대분류'),
        const SizedBox(height: 8),
        DropdownButtonFormField<PlantCategoryOption>(
          key: const Key('plant_category'),
          initialValue: _category,
          isExpanded: true,
          hint: const Text('선택'),
          items: [
            for (final category in categories)
              DropdownMenuItem(value: category, child: Text(category.name)),
          ],
          validator: (value) => value == null ? '대분류를 선택해 주세요.' : null,
          onChanged: _isSubmitting
              ? null
              : (value) => setState(() {
                  _category = value;
                  _species = null;
                  _stage = null;
                  _formError = null;
                }),
        ),
      ],
    );
  }

  Widget _buildSpeciesField() {
    final species = _category?.species ?? const <PlantSpeciesOption>[];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _FieldLabel(icon: Icons.eco_outlined, label: '소분류'),
        const SizedBox(height: 8),
        DropdownButtonFormField<PlantSpeciesOption>(
          key: const Key('plant_species'),
          initialValue: _species,
          isExpanded: true,
          hint: const Text('선택'),
          items: [
            for (final item in species)
              DropdownMenuItem(value: item, child: Text(item.name)),
          ],
          validator: (value) => value == null ? '소분류를 선택해 주세요.' : null,
          onChanged: _isSubmitting || _category == null
              ? null
              : (value) => setState(() {
                  _species = value;
                  _stage = null;
                  _formError = null;
                }),
        ),
      ],
    );
  }

  Widget _buildStageField() {
    final stages = _species?.growthStages ?? const <GrowthStageOption>[];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _FieldLabel(icon: Icons.height_rounded, label: '자람 수준'),
        const SizedBox(height: 8),
        DropdownButtonFormField<GrowthStageOption>(
          key: const Key('plant_growth_stage'),
          initialValue: _stage,
          isExpanded: true,
          hint: const Text('선택'),
          items: [
            for (final stage in stages)
              DropdownMenuItem(value: stage, child: Text(stage.name)),
          ],
          validator: (value) => value == null ? '자람 수준을 선택해 주세요.' : null,
          onChanged: _isSubmitting || _species == null
              ? null
              : (value) => setState(() {
                  _stage = value;
                  _formError = null;
                }),
        ),
      ],
    );
  }

  Future<void> _pickAdoptedDate() async {
    FocusManager.instance.primaryFocus?.unfocus();
    final now = DateTime.now();
    // 서버는 미래 날짜를 거부하지 않으므로 여기에서 오늘까지로 제한한다.
    final picked = await showDatePicker(
      context: context,
      initialDate: _adoptedDate ?? now,
      firstDate: DateTime(2000),
      lastDate: now,
    );
    if (picked != null && mounted) {
      setState(() {
        _adoptedDate = picked;
        _formError = null;
      });
    }
  }

  Future<void> _showPhotoSourceSheet() async {
    FocusManager.instance.primaryFocus?.unfocus();
    final source = await showModalBottomSheet<ImageSource>(
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
                '식물 사진 등록',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 8),
              ListTile(
                key: const Key('plant_photo_camera'),
                leading: const Icon(Icons.photo_camera_outlined),
                title: const Text('카메라로 촬영'),
                onTap: () => Navigator.of(sheetContext).pop(ImageSource.camera),
              ),
              ListTile(
                key: const Key('plant_photo_gallery'),
                leading: const Icon(Icons.photo_library_outlined),
                title: const Text('앨범에서 선택'),
                onTap: () =>
                    Navigator.of(sheetContext).pop(ImageSource.gallery),
              ),
            ],
          ),
        ),
      ),
    );
    if (source != null && mounted) {
      await _pickPhoto(source);
    }
  }

  Future<void> _pickPhoto(ImageSource source) async {
    setState(() => _isPickingPhoto = true);
    try {
      final picked = await _imagePicker.pickImage(
        source: source,
        imageQuality: 85,
        maxWidth: 2048,
        maxHeight: 2048,
      );
      if (picked == null || !mounted) {
        return;
      }
      final bytes = await picked.readAsBytes();
      if (!mounted) {
        return;
      }
      setState(() {
        _selectedPhoto = picked;
        _selectedPhotoBytes = bytes;
        _formError = null;
      });
    } catch (_) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(
          const SnackBar(content: Text('사진을 불러오지 못했습니다. 다시 시도해 주세요.')),
        );
    } finally {
      if (mounted) {
        setState(() => _isPickingPhoto = false);
      }
    }
  }

  void _clearFormError() {
    if (_formError != null) {
      setState(() => _formError = null);
    }
  }

  Future<void> _submit() async {
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() => _formError = null);
    if (!(_formKey.currentState?.validate() ?? false)) {
      return;
    }

    setState(() => _isSubmitting = true);
    try {
      final plant = await ref
          .read(plantRepositoryProvider)
          .createPlant(
            speciesId: _species!.speciesId,
            lifeStageId: _stage!.lifeStageId,
            name: _nameController.text.trim(),
            adoptedDate: _adoptedDate,
          );
      if (!mounted) {
        return;
      }
      final photo = _selectedPhoto;
      if (photo != null) {
        try {
          await ref
              .read(photoRepositoryProvider)
              .uploadRepresentativePhoto(
                plantId: plant.plantId,
                filePath: photo.path,
                fileName: photo.name,
              );
        } catch (_) {
          if (!mounted) {
            return;
          }
          ref.invalidate(myPlantsProvider);
          ref.invalidate(homeControllerProvider);
          ScaffoldMessenger.of(context)
            ..hideCurrentSnackBar()
            ..showSnackBar(
              SnackBar(content: Text('${plant.name} 등록은 완료했지만 사진을 올리지 못했어요.')),
            );
          returnFromSharedPage(context, fallbackLocation: '/');
          return;
        }
      }
      if (!mounted) {
        return;
      }
      ref.invalidate(myPlantsProvider);
      ref.invalidate(homeControllerProvider);
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text('${plant.name} 등록을 완료했어요!')));
      returnFromSharedPage(context, fallbackLocation: '/');
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isSubmitting = false;
        _formError = _messageFor(error);
      });
    }
  }

  String _messageFor(Object error) {
    if (error is ApiException) {
      return switch (error.kind) {
        ApiExceptionKind.connection ||
        ApiExceptionKind.timeout => '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
        ApiExceptionKind.problem => error.problem?.detail ?? '식물을 등록하지 못했습니다.',
        ApiExceptionKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
        _ => '식물을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      };
    }
    return '식물을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.';
  }
}

class _AdoptedDateField extends StatelessWidget {
  const _AdoptedDateField({
    required this.value,
    required this.enabled,
    required this.onPick,
    super.key,
  });

  final DateTime? value;
  final bool enabled;
  final VoidCallback onPick;

  @override
  Widget build(BuildContext context) {
    final text = value == null
        ? null
        : '${value!.year}. ${value!.month.toString().padLeft(2, '0')}. '
              '${value!.day.toString().padLeft(2, '0')}.';
    return InkWell(
      onTap: enabled ? onPick : null,
      borderRadius: BorderRadius.circular(14),
      child: InputDecorator(
        decoration: const InputDecoration(
          hintText: '날짜를 선택하세요',
          suffixIcon: Icon(Icons.calendar_month_outlined),
        ),
        isEmpty: text == null,
        child: text == null ? null : Text(text),
      ),
    );
  }
}

class _FieldLabel extends StatelessWidget {
  const _FieldLabel({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 18, color: AppColors.primary),
        const SizedBox(width: 6),
        Text(
          label,
          style: const TextStyle(
            fontWeight: FontWeight.w700,
            color: AppColors.text,
          ),
        ),
      ],
    );
  }
}

class _PhotoPicker extends StatelessWidget {
  const _PhotoPicker({
    required this.bytes,
    required this.enabled,
    required this.onTap,
  });

  final Uint8List? bytes;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Semantics(
        button: true,
        label: bytes == null ? '식물 사진 등록' : '식물 사진 변경',
        child: SizedBox(
          key: const Key('plant_photo_picker'),
          width: 156,
          height: 156,
          child: Stack(
            children: [
              Positioned.fill(
                child: CustomPaint(
                  painter: _DashedCirclePainter(color: AppColors.outline),
                  child: ClipOval(
                    child: bytes == null
                        ? const ColoredBox(
                            color: AppColors.surfaceLow,
                            child: Icon(
                              Icons.local_florist_outlined,
                              size: 54,
                              color: AppColors.primarySoft,
                            ),
                          )
                        : Image.memory(
                            bytes!,
                            key: const Key('plant_photo_preview'),
                            fit: BoxFit.cover,
                            gaplessPlayback: true,
                          ),
                  ),
                ),
              ),
              Positioned(
                right: 2,
                bottom: 2,
                child: Material(
                  color: AppColors.primary,
                  shape: const CircleBorder(),
                  elevation: 4,
                  child: IconButton(
                    key: const Key('plant_photo_button'),
                    tooltip: bytes == null ? '식물 사진 등록' : '식물 사진 변경',
                    onPressed: enabled ? onTap : null,
                    color: Colors.white,
                    iconSize: 22,
                    icon: enabled
                        ? const Icon(Icons.photo_camera)
                        : const SizedBox.square(
                            dimension: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          ),
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

class _DashedCirclePainter extends CustomPainter {
  const _DashedCirclePainter({required this.color});

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..strokeCap = StrokeCap.round;
    final radius = (size.shortestSide - paint.strokeWidth) / 2;
    const dashRadians = 0.09;
    const gapRadians = 0.055;
    var start = 0.0;
    while (start < 6.283185307179586) {
      canvas.drawArc(
        Rect.fromCircle(center: size.center(Offset.zero), radius: radius),
        start,
        dashRadians,
        false,
        paint,
      );
      start += dashRadians + gapRadians;
    }
  }

  @override
  bool shouldRepaint(covariant _DashedCirclePainter oldDelegate) {
    return oldDelegate.color != color;
  }
}

class _TreeLoadFailure extends StatelessWidget {
  const _TreeLoadFailure({required this.message, required this.onRetry});

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
              key: const Key('plant_tree_retry'),
              onPressed: onRetry,
              child: const Text('다시 시도'),
            ),
          ],
        ),
      ),
    );
  }
}
