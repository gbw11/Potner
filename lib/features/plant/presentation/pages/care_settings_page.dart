import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';
import 'package:potner_app/features/plant/domain/plant_detail.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

final growthProfileProvider = FutureProvider.autoDispose
    .family<GrowthProfile, String>((ref, plantId) {
      return ref.watch(plantRepositoryProvider).getGrowthProfile(plantId);
    });

/// 케어 설정이다. 식물별 생육 기준 범위를 조절해 저장한다.
class CareSettingsPage extends ConsumerWidget {
  const CareSettingsPage({required this.plantId, super.key});

  final String plantId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profile = ref.watch(growthProfileProvider(plantId));

    return Scaffold(
      appBar: AppBar(
        title: const Text('케어 설정'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('care_settings_back'),
          onPressed: () => context.canPop()
              ? context.pop()
              : context.go('/plants/$plantId'),
        ),
      ),
      body: SafeArea(
        child: profile.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    plantErrorMessage(error, '케어 설정을 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('care_settings_retry'),
                    onPressed: () =>
                        ref.invalidate(growthProfileProvider(plantId)),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (data) => _CareForm(key: ValueKey(data), profile: data),
        ),
      ),
    );
  }
}

class _CareForm extends ConsumerStatefulWidget {
  const _CareForm({required this.profile, super.key});

  final GrowthProfile profile;

  @override
  ConsumerState<_CareForm> createState() => _CareFormState();
}

class _CareFormState extends ConsumerState<_CareForm> {
  late RangeValues? _soilMoisture;
  late RangeValues? _temperature;
  late RangeValues? _humidity;
  late RangeValues? _illuminance;
  late double? _wateringMl;
  late final TextEditingController _cycleController;
  bool _isSaving = false;

  GrowthProfile get _profile => widget.profile;

  @override
  void initState() {
    super.initState();
    _soilMoisture = _range(
      _profile.soilMoistureMinPct,
      _profile.soilMoistureMaxPct,
    );
    _temperature = _range(_profile.temperatureMinC, _profile.temperatureMaxC);
    _humidity = _range(_profile.humidityMinPct, _profile.humidityMaxPct);
    _illuminance = _range(
      _profile.illuminanceMinLux,
      _profile.illuminanceMaxLux,
    );
    _wateringMl = _profile.recommendedWateringMl;
    _cycleController = TextEditingController(
      text: _profile.wateringCycleDays == null
          ? ''
          : _trimNumber(_profile.wateringCycleDays!),
    );
  }

  @override
  void dispose() {
    _cycleController.dispose();
    super.dispose();
  }

  static RangeValues? _range(double? min, double? max) {
    if (min == null || max == null) {
      return null;
    }
    return RangeValues(min, max);
  }

  static String _trimNumber(double value) {
    return value == value.roundToDouble()
        ? value.round().toString()
        : value.toString();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                '건강한 성장을 위해 최적의 케어 범위를 설정하세요.',
                textAlign: TextAlign.center,
                style: TextStyle(color: AppColors.textMuted),
              ),
              const SizedBox(height: 20),
              if (_soilMoisture != null)
                _RangeSection(
                  sectionKey: const Key('care_soil_moisture'),
                  icon: Icons.water_drop_outlined,
                  title: '토양 수분',
                  values: _soilMoisture!,
                  min: 0,
                  max: 100,
                  divisions: 100,
                  format: (value) => '${value.round()}%',
                  enabled: !_isSaving,
                  onChanged: (values) =>
                      setState(() => _soilMoisture = values),
                ),
              if (_temperature != null)
                _RangeSection(
                  sectionKey: const Key('care_temperature'),
                  icon: Icons.thermostat_outlined,
                  title: '온도',
                  values: _temperature!,
                  min: 0,
                  max: 40,
                  divisions: 80,
                  format: (value) => '${_trimNumber(value)}℃',
                  enabled: !_isSaving,
                  onChanged: (values) => setState(() => _temperature = values),
                ),
              if (_humidity != null)
                _RangeSection(
                  sectionKey: const Key('care_humidity'),
                  icon: Icons.opacity_outlined,
                  title: '습도',
                  values: _humidity!,
                  min: 0,
                  max: 100,
                  divisions: 100,
                  format: (value) => '${value.round()}%',
                  enabled: !_isSaving,
                  onChanged: (values) => setState(() => _humidity = values),
                ),
              if (_illuminance != null)
                _RangeSection(
                  sectionKey: const Key('care_illuminance'),
                  icon: Icons.wb_sunny_outlined,
                  title: '광량',
                  values: _illuminance!,
                  min: 0,
                  max: 100000,
                  divisions: 200,
                  format: (value) => '${_thousands(value.round())} Lux',
                  enabled: !_isSaving,
                  onChanged: (values) => setState(() => _illuminance = values),
                ),
              if (_wateringMl != null)
                _SingleSliderSection(
                  sectionKey: const Key('care_watering_ml'),
                  icon: Icons.local_drink_outlined,
                  title: '물의 양',
                  value: _wateringMl!,
                  min: 0,
                  max: 1000,
                  divisions: 100,
                  format: (value) => '${value.round()}ml',
                  enabled: !_isSaving,
                  onChanged: (value) => setState(() => _wateringMl = value),
                ),
              _CardSection(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Row(
                      children: [
                        Icon(
                          Icons.event_repeat_outlined,
                          size: 18,
                          color: AppColors.primary,
                        ),
                        SizedBox(width: 6),
                        Text(
                          '물의 주기',
                          style: TextStyle(fontWeight: FontWeight.w700),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    TextFormField(
                      key: const Key('care_watering_cycle'),
                      controller: _cycleController,
                      enabled: !_isSaving,
                      keyboardType: TextInputType.number,
                      inputFormatters: [
                        FilteringTextInputFormatter.digitsOnly,
                        LengthLimitingTextInputFormatter(3),
                      ],
                      decoration: const InputDecoration(
                        hintText: '며칠에 한 번 물을 줄까요?',
                        suffixText: '일',
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 26),
              FilledButton(
                key: const Key('care_save'),
                onPressed: _isSaving ? null : _save,
                child: _isSaving
                    ? const SizedBox.square(
                        dimension: 22,
                        child: CircularProgressIndicator(
                          strokeWidth: 2.4,
                          color: Colors.white,
                        ),
                      )
                    : const Text('설정하기'),
              ),
              const SizedBox(height: 8),
              TextButton(
                key: const Key('care_reset'),
                onPressed: _isSaving ? null : _confirmAndReset,
                child: const Text(
                  '종 기본값으로 되돌리기',
                  style: TextStyle(color: AppColors.textMuted),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  static String _thousands(int value) {
    final text = value.toString();
    final buffer = StringBuffer();
    for (var i = 0; i < text.length; i++) {
      if (i > 0 && (text.length - i) % 3 == 0) {
        buffer.write(',');
      }
      buffer.write(text[i]);
    }
    return buffer.toString();
  }

  GrowthProfileUpdate _buildUpdate() {
    final cycleText = _cycleController.text.trim();
    final cycleDays = cycleText.isEmpty ? null : double.parse(cycleText);
    double? changed(double? current, double? original) {
      if (current == null || current == original) {
        return null;
      }
      return current;
    }

    return GrowthProfileUpdate(
      soilMoistureMinPct: changed(
        _soilMoisture?.start,
        _profile.soilMoistureMinPct,
      ),
      soilMoistureMaxPct: changed(
        _soilMoisture?.end,
        _profile.soilMoistureMaxPct,
      ),
      temperatureMinC: changed(_temperature?.start, _profile.temperatureMinC),
      temperatureMaxC: changed(_temperature?.end, _profile.temperatureMaxC),
      humidityMinPct: changed(_humidity?.start, _profile.humidityMinPct),
      humidityMaxPct: changed(_humidity?.end, _profile.humidityMaxPct),
      illuminanceMinLux: changed(
        _illuminance?.start,
        _profile.illuminanceMinLux,
      ),
      illuminanceMaxLux: changed(_illuminance?.end, _profile.illuminanceMaxLux),
      recommendedWateringMl: changed(
        _wateringMl,
        _profile.recommendedWateringMl,
      ),
      wateringCycleDays: changed(cycleDays, _profile.wateringCycleDays),
    );
  }

  Future<void> _save() async {
    final messenger = ScaffoldMessenger.of(context);
    final update = _buildUpdate();
    if (update.isEmpty) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('변경된 설정이 없어요.')));
      return;
    }

    setState(() => _isSaving = true);
    try {
      await ref
          .read(plantRepositoryProvider)
          .updateGrowthProfile(plantId: _profile.plantId, update: update);
      if (!mounted) {
        return;
      }
      setState(() => _isSaving = false);
      ref.invalidate(growthProfileProvider(_profile.plantId));
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('케어 설정을 저장했어요.')));
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _isSaving = false);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(plantErrorMessage(error, '케어 설정을 저장하지 못했습니다.')),
          ),
        );
    }
  }

  Future<void> _confirmAndReset() async {
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('기본값으로 되돌리기'),
        content: const Text('직접 조정한 케어 범위가 사라지고 종 기본값으로 돌아가요. 계속할까요?'),
        actions: [
          TextButton(
            key: const Key('care_reset_cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('취소'),
          ),
          FilledButton(
            key: const Key('care_reset_confirm'),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('되돌리기'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }

    setState(() => _isSaving = true);
    try {
      await ref
          .read(plantRepositoryProvider)
          .resetGrowthProfile(_profile.plantId);
      if (!mounted) {
        return;
      }
      setState(() => _isSaving = false);
      ref.invalidate(growthProfileProvider(_profile.plantId));
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('종 기본값으로 되돌렸어요.')));
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => _isSaving = false);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(plantErrorMessage(error, '기본값으로 되돌리지 못했습니다.')),
          ),
        );
    }
  }
}

class _RangeSection extends StatelessWidget {
  const _RangeSection({
    required this.sectionKey,
    required this.icon,
    required this.title,
    required this.values,
    required this.min,
    required this.max,
    required this.divisions,
    required this.format,
    required this.enabled,
    required this.onChanged,
  });

  final Key sectionKey;
  final IconData icon;
  final String title;
  final RangeValues values;
  final double min;
  final double max;
  final int divisions;
  final String Function(double value) format;
  final bool enabled;
  final ValueChanged<RangeValues> onChanged;

  @override
  Widget build(BuildContext context) {
    return _CardSection(
      key: sectionKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 18, color: AppColors.primary),
              const SizedBox(width: 6),
              Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
              const Spacer(),
              Text(
                '${format(values.start)} ~ ${format(values.end)}',
                style: const TextStyle(
                  color: AppColors.primary,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          RangeSlider(
            values: values,
            min: min,
            max: max,
            divisions: divisions,
            labels: RangeLabels(format(values.start), format(values.end)),
            onChanged: enabled ? onChanged : null,
          ),
        ],
      ),
    );
  }
}

class _SingleSliderSection extends StatelessWidget {
  const _SingleSliderSection({
    required this.sectionKey,
    required this.icon,
    required this.title,
    required this.value,
    required this.min,
    required this.max,
    required this.divisions,
    required this.format,
    required this.enabled,
    required this.onChanged,
  });

  final Key sectionKey;
  final IconData icon;
  final String title;
  final double value;
  final double min;
  final double max;
  final int divisions;
  final String Function(double value) format;
  final bool enabled;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    return _CardSection(
      key: sectionKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 18, color: AppColors.primary),
              const SizedBox(width: 6),
              Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
              const Spacer(),
              Text(
                format(value),
                style: const TextStyle(
                  color: AppColors.primary,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          Slider(
            value: value,
            min: min,
            max: max,
            divisions: divisions,
            label: format(value),
            onChanged: enabled ? onChanged : null,
          ),
        ],
      ),
    );
  }
}

class _CardSection extends StatelessWidget {
  const _CardSection({required this.child, super.key});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 8),
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
      child: child,
    );
  }
}
