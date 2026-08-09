import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
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
          onPressed: () => returnFromSharedPage(
            context,
            fallbackLocation: '/plants/$plantId',
          ),
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

  /// 하루 목표 광량(lux·h)이다. 범위가 아니라 목표 하나만 받는다.
  ///
  /// 서버는 min ≤ target ≤ max 를 검증한다. 사용자에게 세 칸을 받으면 그 관계를 직접
  /// 맞추게 되는데, 셋 다 뜻이 비슷해 보여 틀리기 쉽다. 목표만 받고 허용 범위는 원래
  /// 비율을 유지해 저장 직전에 계산한다.
  late double? _dailyLightTarget;
  /// 1회 급수량이다. null 을 두지 않는다 — 값이 없다고 슬라이더를 감추면 값을 넣을 방법이
  /// 사라진다. 저장된 값이 없으면 아래 기본값에서 시작한다.
  late double _wateringMl;

  /// 저장된 급수량이 없을 때 슬라이더가 시작할 값이다. 라즈베리파이 펌프의 1회 기본값
  /// (pump.default_ml)과 같게 둔다. 장치 쪽 안전 상한은 500ml 다.
  static const _defaultWateringMl = 200.0;
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
    _dailyLightTarget = _profile.dailyLightTargetLuxHour;
    _wateringMl = _profile.recommendedWateringMl ?? _defaultWateringMl;
  }

  static RangeValues? _range(double? min, double? max) {
    if (min == null || max == null) {
      return null;
    }
    return RangeValues(min, max);
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
                '건강한 성장을 위해 최적의 케어 범위를 직접 입력하세요.',
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
                  unit: '%',
                  allowDecimal: false,
                  enabled: !_isSaving,
                  onChanged: (values) => setState(() => _soilMoisture = values),
                ),
              if (_temperature != null)
                _RangeSection(
                  sectionKey: const Key('care_temperature'),
                  icon: Icons.thermostat_outlined,
                  title: '온도',
                  values: _temperature!,
                  min: 0,
                  max: 40,
                  unit: '℃',
                  // 온도만 소수를 받는다. 종 기본값이 21.5 처럼 들어와 정수로 반올림하면
                  // 저장할 때마다 값이 조금씩 달라진다.
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
                  unit: '%',
                  allowDecimal: false,
                  enabled: !_isSaving,
                  onChanged: (values) => setState(() => _humidity = values),
                ),
              // 순간 조도(illuminance) 입력을 없앴다. 서버가 그 값으로 판정하지 않는다 —
              // 밤에는 0 lux 가 정상이라 순간값으로 보면 매일 해 질 때 부족 알림이 간다.
              // 실제로 알림과 로봇의 햇빛 자리 이동을 움직이는 것은 아래 '하루 빛의 양' 이다.
              if (_dailyLightTarget != null)
                _SingleValueSection(
                  sectionKey: const Key('care_daily_light'),
                  icon: Icons.wb_sunny_outlined,
                  title: '하루 빛의 양',
                  value: _dailyLightTarget!,
                  min: 0,
                  max: 1000000,
                  unit: 'lux·h',
                  enabled: !_isSaving,
                  onChanged: (value) =>
                      setState(() => _dailyLightTarget = value),
                  note: '하루 동안 받아야 할 빛의 총량이에요. 이만큼 채우지 못하면 알림이 오고, '
                      '로봇이 햇빛 자리로 옮겨 줍니다.',
                ),
              // 값이 없어도 입력 칸을 그린다. 감추면 값을 넣을 방법이 없어지고, 급수량이
              // 비어 있으면 자동 급수가 무엇을 줄지 정하지 못한다.
              _SingleValueSection(
                sectionKey: const Key('care_watering_ml'),
                icon: Icons.local_drink_outlined,
                title: '물의 양',
                value: _wateringMl,
                min: 0,
                max: 1000,
                unit: 'ml',
                enabled: !_isSaving,
                onChanged: (value) => setState(() => _wateringMl = value),
                note: _profile.recommendedWateringMl == null
                    ? '아직 저장된 급수량이 없어요. 저장하면 이 값으로 설정됩니다.'
                    : null,
              ),
              // 급수 주기 입력을 없앴다. 서버는 이 값을 저장만 하고 어디서도 읽지 않는데,
              // 입력 칸이 있으면 "3일마다 준다고 설정했는데 왜 안 주지" 라는 오해를 만든다.
              // 실제 급수는 토양 수분이 기준 아래로 내려갈 때 일어난다.
              _CardSection(
                child: Row(
                  children: [
                    const Icon(
                      Icons.sensors_outlined,
                      size: 18,
                      color: AppColors.primary,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '물은 토양 수분을 보고 자동으로 줘요. 위에서 정한 수분 범위 아래로 '
                        '내려가면 정한 양만큼 급수합니다.',
                        style: const TextStyle(
                          color: AppColors.textMuted,
                          fontSize: 13,
                          height: 1.5,
                        ),
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

  GrowthProfileUpdate _buildUpdate() {
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
      dailyLightTargetLuxHour: changed(
        _dailyLightTarget,
        _profile.dailyLightTargetLuxHour,
      ),
      // 목표만 바꾸면 서버 검증(min ≤ target ≤ max)에 걸린다. 예전 목표에 맞춰진
      // 허용 범위가 그대로 남아 새 목표가 그 밖으로 나가기 때문이다.
      dailyLightMinLuxHour: changed(
        _shiftedDailyLightBound(_profile.dailyLightMinLuxHour),
        _profile.dailyLightMinLuxHour,
      ),
      dailyLightMaxLuxHour: changed(
        _shiftedDailyLightBound(_profile.dailyLightMaxLuxHour),
        _profile.dailyLightMaxLuxHour,
      ),
      recommendedWateringMl: changed(
        _wateringMl,
        _profile.recommendedWateringMl,
      ),
    );
  }

  /// 허용 범위를 새 목표에 맞춰 옮긴다. 목표 대비 비율을 그대로 유지한다.
  ///
  /// 종마다 밴드 폭이 다르다(V7 이 목표의 70~130% 로 채웠다). 고정 비율을 새로 정하면
  /// 종이 정해 둔 여유를 덮어쓰게 되므로 원래 비율을 그대로 옮긴다.
  ///
  /// 범위가 없는 종(min·max 가 둘 다 null)은 그대로 null 이다. 서버 검증이 '둘 다 없음'
  /// 은 허용하고 한쪽만 있는 것을 막는다.
  double? _shiftedDailyLightBound(double? originalBound) {
    final originalTarget = _profile.dailyLightTargetLuxHour;
    final newTarget = _dailyLightTarget;
    if (originalBound == null || originalTarget == null || newTarget == null) {
      return null;
    }
    if (originalTarget == 0) {
      // 비율을 낼 수 없다. 밴드를 옮기지 못하므로 목표도 건드리지 않게 둔다.
      return originalBound;
    }
    return newTarget * (originalBound / originalTarget);
  }

  Future<void> _save() async {
    final messenger = ScaffoldMessenger.of(context);
    final update = _buildUpdate();
    if (update.isEmpty) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('변경된 설정이 없어요.')));
      _returnAfterSave();
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
      _returnAfterSave();
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

  void _returnAfterSave() {
    returnFromSharedPage(
      context,
      fallbackLocation: '/plants/${_profile.plantId}',
    );
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

/// 소수점이 필요 없는 값은 정수로 보여준다. 21.0℃ 를 '21' 로 쓰기 위한 것이다.
String _trimNumber(double value) {
  return value == value.roundToDouble()
      ? value.round().toString()
      : value.toString();
}

/// 최소·최대를 숫자로 직접 받는다.
///
/// <p>슬라이더를 걷어냈다. 온도 21.5℃ 나 광량 24,500 lux 처럼 사용자가 이미 아는 값을
/// 넣으려 해도 손가락으로 정확히 멈출 수 없었다. 광량은 0~100,000 을 200 칸으로 나눠
/// 한 칸이 500 lux 라 원하는 값에 아예 닿지 못한다. 직접 입력이면 그 문제가 사라진다.
class _RangeSection extends StatefulWidget {
  const _RangeSection({
    required this.sectionKey,
    required this.icon,
    required this.title,
    required this.values,
    required this.min,
    required this.max,
    required this.unit,
    required this.enabled,
    required this.onChanged,
    this.allowDecimal = true,
  });

  final Key sectionKey;
  final IconData icon;
  final String title;
  final RangeValues values;

  /// 입력 가능한 한계다. 슬라이더 시절의 양 끝을 그대로 쓴다.
  final double min;
  final double max;
  final String unit;
  final bool enabled;
  final ValueChanged<RangeValues> onChanged;
  final bool allowDecimal;

  @override
  State<_RangeSection> createState() => _RangeSectionState();
}

class _RangeSectionState extends State<_RangeSection> {
  late final TextEditingController _minController;
  late final TextEditingController _maxController;
  String? _error;

  @override
  void initState() {
    super.initState();
    _minController = TextEditingController(text: _text(widget.values.start));
    _maxController = TextEditingController(text: _text(widget.values.end));
  }

  @override
  void didUpdateWidget(covariant _RangeSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    // '종 기본값으로 되돌리기' 처럼 바깥에서 값이 바뀌면 입력 칸도 따라가야 한다.
    // 사용자가 지금 치고 있는 중이면 건드리지 않는다. 소수점을 찍는 순간 "21." 이
    // 파싱되지 않아 덮어쓰면 글자가 사라진다.
    if (oldWidget.values.start != widget.values.start &&
        _parse(_minController.text) != widget.values.start) {
      _minController.text = _text(widget.values.start);
    }
    if (oldWidget.values.end != widget.values.end &&
        _parse(_maxController.text) != widget.values.end) {
      _maxController.text = _text(widget.values.end);
    }
  }

  @override
  void dispose() {
    _minController.dispose();
    _maxController.dispose();
    super.dispose();
  }

  String _text(double value) => widget.allowDecimal
      ? _trimNumber(value)
      : value.round().toString();

  double? _parse(String raw) => double.tryParse(raw.trim().replaceAll(',', ''));

  /// 두 칸을 함께 본다. 한쪽만 봐서는 최소가 최대를 넘었는지 알 수 없다.
  void _handleEdit() {
    final min = _parse(_minController.text);
    final max = _parse(_maxController.text);
    if (min == null || max == null) {
      setState(() => _error = '숫자를 입력해 주세요.');
      return;
    }
    if (min < widget.min || max > widget.max) {
      setState(() {
        _error =
            '${_text(widget.min)}${widget.unit} ~ '
            '${_text(widget.max)}${widget.unit} 안에서 정해 주세요.';
      });
      return;
    }
    if (min >= max) {
      setState(() => _error = '최소가 최대보다 작아야 해요.');
      return;
    }
    setState(() => _error = null);
    widget.onChanged(RangeValues(min, max));
  }

  @override
  Widget build(BuildContext context) {
    return _CardSection(
      key: widget.sectionKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(widget.icon, size: 18, color: AppColors.primary),
              const SizedBox(width: 6),
              Text(
                widget.title,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: _NumberField(
                  fieldKey: Key('${_keyPrefix()}_min'),
                  controller: _minController,
                  label: '최소',
                  unit: widget.unit,
                  enabled: widget.enabled,
                  allowDecimal: widget.allowDecimal,
                  onEdited: _handleEdit,
                ),
              ),
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 8, vertical: 16),
                child: Text('~', style: TextStyle(color: AppColors.textMuted)),
              ),
              Expanded(
                child: _NumberField(
                  fieldKey: Key('${_keyPrefix()}_max'),
                  controller: _maxController,
                  label: '최대',
                  unit: widget.unit,
                  enabled: widget.enabled,
                  allowDecimal: widget.allowDecimal,
                  onEdited: _handleEdit,
                ),
              ),
            ],
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                _error!,
                style: const TextStyle(color: AppColors.error, fontSize: 12),
              ),
            ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  /// 테스트가 칸을 집을 수 있게 섹션 키에서 접두어를 만든다.
  String _keyPrefix() {
    final key = widget.sectionKey;
    return key is ValueKey<String> ? key.value : widget.title;
  }
}

/// 숫자 한 칸이다. 단위를 칸 안에 붙여 무엇을 넣는지 두 번 확인하지 않게 한다.
class _NumberField extends StatelessWidget {
  const _NumberField({
    required this.fieldKey,
    required this.controller,
    required this.label,
    required this.unit,
    required this.enabled,
    required this.allowDecimal,
    required this.onEdited,
  });

  final Key fieldKey;
  final TextEditingController controller;
  final String label;
  final String unit;
  final bool enabled;
  final bool allowDecimal;
  final VoidCallback onEdited;

  @override
  Widget build(BuildContext context) {
    return TextField(
      key: fieldKey,
      controller: controller,
      enabled: enabled,
      // 숫자 키패드를 띄운다. 소수를 받지 않는 항목에는 점을 아예 내주지 않는다.
      keyboardType: TextInputType.numberWithOptions(decimal: allowDecimal),
      textAlign: TextAlign.center,
      style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
      decoration: InputDecoration(
        labelText: label,
        suffixText: unit,
        isDense: true,
      ),
      onChanged: (_) => onEdited(),
    );
  }
}

/// 값 하나를 숫자로 직접 받는다. 범위와 같은 이유로 슬라이더를 걷어냈다.
class _SingleValueSection extends StatefulWidget {
  const _SingleValueSection({
    required this.sectionKey,
    required this.icon,
    required this.title,
    required this.value,
    required this.min,
    required this.max,
    required this.unit,
    required this.enabled,
    required this.onChanged,
    this.note,
  });

  final Key sectionKey;
  final IconData icon;
  final String title;
  final double value;
  final double min;
  final double max;
  final String unit;
  final bool enabled;
  final ValueChanged<double> onChanged;

  /// 입력 칸 아래에 붙일 한 줄 설명이다. 저장된 값이 없을 때 지금 보이는 값이 무엇인지
  /// 밝히는 데 쓴다.
  final String? note;

  @override
  State<_SingleValueSection> createState() => _SingleValueSectionState();
}

class _SingleValueSectionState extends State<_SingleValueSection> {
  late final TextEditingController _controller;
  String? _error;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: _text(widget.value));
  }

  @override
  void didUpdateWidget(covariant _SingleValueSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.value != widget.value &&
        double.tryParse(_controller.text.trim()) != widget.value) {
      _controller.text = _text(widget.value);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  // 급수량(ml)도 광량(lux·h)도 소수를 받지 않는다. 0.5ml 를 구분해 줄 수 있는 펌프가
  // 아니고, lux·h 는 자릿수가 커서 소수점이 뜻을 갖지 않는다.
  String _text(double value) => value.round().toString();

  /// 테스트가 칸을 집을 수 있게 섹션 키에서 접두어를 만든다.
  String _keyPrefix() {
    final key = widget.sectionKey;
    return key is ValueKey<String> ? key.value : widget.title;
  }

  void _handleEdit() {
    final parsed = double.tryParse(_controller.text.trim().replaceAll(',', ''));
    if (parsed == null) {
      setState(() => _error = '숫자를 입력해 주세요.');
      return;
    }
    if (parsed < widget.min || parsed > widget.max) {
      setState(() {
        _error =
            '${_text(widget.min)}${widget.unit} ~ '
            '${_text(widget.max)}${widget.unit} 안에서 정해 주세요.';
      });
      return;
    }
    setState(() => _error = null);
    widget.onChanged(parsed);
  }

  @override
  Widget build(BuildContext context) {
    return _CardSection(
      key: widget.sectionKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(widget.icon, size: 18, color: AppColors.primary),
              const SizedBox(width: 6),
              Text(
                widget.title,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ],
          ),
          const SizedBox(height: 12),
          _NumberField(
            // 섹션 키에서 만든다. 하드코딩하면 이 위젯을 두 번 쓰는 순간 키가 겹쳐
            // 테스트가 어느 칸을 집는지 알 수 없게 된다.
            fieldKey: Key('${_keyPrefix()}_value'),
            controller: _controller,
            label: widget.title,
            unit: widget.unit,
            enabled: widget.enabled,
            allowDecimal: false,
            onEdited: _handleEdit,
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                _error!,
                style: const TextStyle(color: AppColors.error, fontSize: 12),
              ),
            ),
          if (widget.note != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                widget.note!,
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 12,
                  height: 1.45,
                ),
              ),
            ),
          const SizedBox(height: 8),
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
