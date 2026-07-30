import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/auth/presentation/widgets/form_error.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/home/presentation/controllers/home_controller.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 디바이스 등록이다. Potner 코드(로봇), 스테이션 코드(라즈베리)를 연결하고
/// 선택한 식물에 배정까지 마쳐야 측정값이 흐르기 시작한다.
class DeviceRegistrationPage extends ConsumerStatefulWidget {
  const DeviceRegistrationPage({super.key});

  @override
  ConsumerState<DeviceRegistrationPage> createState() =>
      _DeviceRegistrationPageState();
}

class _DeviceRegistrationPageState
    extends ConsumerState<DeviceRegistrationPage> {
  static final _deviceUidPattern = RegExp(r'^[A-Za-z0-9][A-Za-z0-9_-]*$');

  final _formKey = GlobalKey<FormState>();
  final _potnerCodeController = TextEditingController();
  final _nameController = TextEditingController();
  final _stationCodeController = TextEditingController();

  MyPlant? _selectedPlant;
  bool _isSubmitting = false;
  String? _formError;

  @override
  void dispose() {
    _potnerCodeController.dispose();
    _nameController.dispose();
    _stationCodeController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final plants = ref.watch(myPlantsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('디바이스 등록하기'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('device_registration_back'),
          onPressed: () => context.canPop() ? context.pop() : context.go('/'),
        ),
      ),
      body: SafeArea(
        child: GestureDetector(
          behavior: HitTestBehavior.translucent,
          onTap: () => FocusManager.instance.primaryFocus?.unfocus(),
          child: SingleChildScrollView(
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        '디바이스를 연결해 주세요',
                        style: Theme.of(context).textTheme.headlineMedium,
                      ),
                      const SizedBox(height: 10),
                      const Text(
                        'Potner와 스테이션의 코드를 입력하면 내 반려식물의 상태를 확인하고 '
                        '환경을 관리할 수 있어요.',
                        style: TextStyle(color: AppColors.textMuted),
                      ),
                      const SizedBox(height: 24),
                      _FieldLabel(label: 'Potner 코드'),
                      const SizedBox(height: 8),
                      TextFormField(
                        key: const Key('device_potner_code'),
                        controller: _potnerCodeController,
                        enabled: !_isSubmitting,
                        maxLength: 100,
                        decoration: const InputDecoration(
                          hintText: 'Potner 코드를 입력해 주세요',
                          counterText: '',
                          // 기기 스티커의 고유번호를 손으로 입력한다. QR 스캔이 아니다.
                          suffixIcon: Icon(Icons.tag_rounded),
                        ),
                        validator: _validateDeviceUid,
                        onChanged: (_) => _clearFormError(),
                      ),
                      const SizedBox(height: 18),
                      _FieldLabel(label: 'Potner 이름'),
                      const SizedBox(height: 8),
                      TextFormField(
                        key: const Key('device_robot_name'),
                        controller: _nameController,
                        enabled: !_isSubmitting,
                        maxLength: 50,
                        decoration: const InputDecoration(
                          hintText: '로봇을 부를 이름을 입력해 주세요',
                          counterText: '',
                        ),
                        validator: (value) {
                          if (value == null || value.trim().isEmpty) {
                            return 'Potner 이름을 입력해 주세요.';
                          }
                          return null;
                        },
                        onChanged: (_) => _clearFormError(),
                      ),
                      const SizedBox(height: 18),
                      _FieldLabel(label: '스테이션 코드 (선택)'),
                      const SizedBox(height: 8),
                      TextFormField(
                        key: const Key('device_station_code'),
                        controller: _stationCodeController,
                        enabled: !_isSubmitting,
                        maxLength: 100,
                        decoration: const InputDecoration(
                          hintText: '스테이션 코드를 입력해 주세요',
                          counterText: '',
                          suffixIcon: Icon(Icons.sensors_rounded),
                        ),
                        validator: (value) {
                          if (value == null || value.trim().isEmpty) {
                            return null;
                          }
                          return _validateDeviceUid(value);
                        },
                        onChanged: (_) => _clearFormError(),
                      ),
                      const SizedBox(height: 18),
                      _FieldLabel(label: '연결할 식물 (선택)'),
                      const SizedBox(height: 8),
                      plants.when(
                        loading: () => const LinearProgressIndicator(),
                        error: (_, _) => const Text(
                          '식물 목록을 불러오지 못해 배정 없이 등록합니다.',
                          style: TextStyle(color: AppColors.textMuted),
                        ),
                        data: (items) => DropdownButtonFormField<MyPlant>(
                          key: const Key('device_plant'),
                          initialValue: _selectedPlant,
                          isExpanded: true,
                          hint: const Text('나중에 배정하려면 비워 두세요'),
                          items: [
                            for (final plant in items)
                              DropdownMenuItem(
                                value: plant,
                                child: Text('${plant.name} (${plant.speciesName})'),
                              ),
                          ],
                          onChanged: _isSubmitting
                              ? null
                              : (value) =>
                                    setState(() => _selectedPlant = value),
                        ),
                      ),
                      if (_formError != null) ...[
                        const SizedBox(height: 18),
                        FormError(message: _formError!),
                      ],
                      const SizedBox(height: 28),
                      FilledButton(
                        key: const Key('device_submit'),
                        onPressed: _isSubmitting ? null : _submit,
                        child: _isSubmitting
                            ? const SizedBox.square(
                                dimension: 22,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2.4,
                                  color: Colors.white,
                                ),
                              )
                            : const Text('등록하기'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  String? _validateDeviceUid(String? value) {
    final text = value?.trim() ?? '';
    if (text.isEmpty) {
      return '코드를 입력해 주세요.';
    }
    if (!_deviceUidPattern.hasMatch(text)) {
      return '코드는 영문, 숫자, 하이픈, 밑줄만 사용할 수 있습니다.';
    }
    return null;
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

    final repository = ref.read(deviceRepositoryProvider);
    final stationCode = _stationCodeController.text.trim();
    final plant = _selectedPlant;

    setState(() => _isSubmitting = true);

    final RobotRegistration registration;
    try {
      registration = await repository.registerRobot(
        deviceUid: _potnerCodeController.text.trim(),
        name: _nameController.text.trim(),
      );
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isSubmitting = false;
        _formError = plantErrorMessage(error, '디바이스를 등록하지 못했습니다.');
      });
      return;
    }

    // 로봇 등록 후의 실패는 되돌릴 수 없으므로(토큰이 이미 발급됨)
    // 경고로 모아 안내하고 토큰 다이얼로그는 반드시 보여 준다.
    final warnings = <String>[];
    if (stationCode.isNotEmpty) {
      try {
        await repository.registerIotDevice(
          robotId: registration.robotId,
          deviceUid: stationCode,
          deviceType: IotDeviceType.raspberryPi,
        );
      } catch (error) {
        warnings.add(
          plantErrorMessage(error, '스테이션 등록에 실패했어요. 장치 관리에서 다시 시도해 주세요.'),
        );
      }
    }
    if (plant != null) {
      try {
        await repository.assignRobotToPlant(
          plantId: plant.plantId,
          robotId: registration.robotId,
        );
      } catch (error) {
        warnings.add(
          plantErrorMessage(error, '식물 배정에 실패했어요. 장치 관리에서 다시 시도해 주세요.'),
        );
      }
    }

    if (!mounted) {
      return;
    }
    setState(() => _isSubmitting = false);
    ref.invalidate(homeControllerProvider);
    await _showUploadTokenDialog(registration, warnings);
    if (!mounted) {
      return;
    }
    context.go('/');
  }

  Future<void> _showUploadTokenDialog(
    RobotRegistration registration,
    List<String> warnings,
  ) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: Text('${registration.name} 등록 완료!'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '아래 업로드 토큰은 지금만 볼 수 있어요.\n'
              '라즈베리 설정에 넣어야 하며, 잃어버리면 재발급해야 합니다.',
              style: TextStyle(color: AppColors.textMuted, height: 1.5),
            ),
            const SizedBox(height: 14),
            Container(
              width: double.maxFinite,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppColors.surfaceLow,
                borderRadius: BorderRadius.circular(12),
              ),
              child: SelectableText(
                registration.uploadToken,
                key: const Key('device_upload_token'),
                style: const TextStyle(fontFamily: 'monospace'),
              ),
            ),
            for (final warning in warnings) ...[
              const SizedBox(height: 12),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(
                    Icons.warning_amber_rounded,
                    size: 18,
                    color: Color(0xFFB07E09),
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      warning,
                      style: const TextStyle(fontSize: 13, height: 1.4),
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
        actions: [
          TextButton.icon(
            key: const Key('device_token_copy'),
            onPressed: () async {
              final messenger = ScaffoldMessenger.of(context);
              await Clipboard.setData(
                ClipboardData(text: registration.uploadToken),
              );
              messenger
                ..hideCurrentSnackBar()
                ..showSnackBar(const SnackBar(content: Text('토큰을 복사했어요.')));
            },
            icon: const Icon(Icons.copy_rounded, size: 18),
            label: const Text('복사'),
          ),
          FilledButton(
            key: const Key('device_token_done'),
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('저장했어요'),
          ),
        ],
      ),
    );
  }
}

class _FieldLabel extends StatelessWidget {
  const _FieldLabel({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Text(
      label,
      style: const TextStyle(
        fontWeight: FontWeight.w700,
        color: AppColors.text,
      ),
    );
  }
}
