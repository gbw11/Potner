import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/arrival/application/home_geofence_controller.dart';
import 'package:potner_app/features/arrival/domain/home_geofence_state.dart';

class ArrivalSettingPage extends ConsumerStatefulWidget {
  const ArrivalSettingPage({super.key});

  @override
  ConsumerState<ArrivalSettingPage> createState() => _ArrivalSettingPageState();
}

class _ArrivalSettingPageState extends ConsumerState<ArrivalSettingPage>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      ref.read(homeGeofenceControllerProvider.notifier).load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(homeGeofenceControllerProvider);
    final controller = ref.read(homeGeofenceControllerProvider.notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('귀가 감지 설정'),
        centerTitle: true,
        leading: BackButton(
          onPressed: () => returnFromSharedPage(
            context,
            fallbackLocation: '/my',
          ),
        ),
      ),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: controller.load,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
            children: [
              _StatusCard(
                state: state,
                onToggle: (value) => _toggle(context, value),
              ),
              const SizedBox(height: 16),
              _HomeLocationCard(
                state: state,
                onUseCurrentLocation: controller.useCurrentLocationAsHome,
              ),
              const SizedBox(height: 16),
              _RadiusCard(
                state: state,
                onApproachChanged: controller.setApproachRadius,
                onCancelChanged: controller.setCancelRadius,
              ),
              const SizedBox(height: 16),
              _HowItWorksCard(
                approachRadius: state.approachRadius,
                cancelRadius: state.cancelRadius,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _toggle(BuildContext context, bool enabled) async {
    final controller = ref.read(homeGeofenceControllerProvider.notifier);
    if (!enabled) {
      await controller.disable();
      return;
    }
    final outcome = await controller.enable();
    if (outcome != HomeGeofenceEnableResult.backgroundPermissionRequired ||
        !context.mounted) {
      return;
    }
    final openSettings = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('항상 위치 허용이 필요해요'),
        content: const Text(
          '앱이 닫힌 뒤에도 집 근처 진입을 감지하려면 앱 권한 설정에서 위치를 "항상 허용"으로 선택해 주세요.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('나중에'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('설정 열기'),
          ),
        ],
      ),
    );
    if (openSettings == true) {
      await controller.openBackgroundLocationSettings();
    }
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.state, required this.onToggle});

  final HomeGeofenceViewState state;
  final ValueChanged<bool> onToggle;

  @override
  Widget build(BuildContext context) {
    final enabled = state.status.enabled;
    return _Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Icon(
                enabled
                    ? Icons.location_on_rounded
                    : Icons.location_off_outlined,
                color: enabled ? AppColors.primary : AppColors.textMuted,
              ),
              const SizedBox(width: 10),
              const Expanded(
                child: Text(
                  'GPS 귀가 감지',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
                ),
              ),
              Switch(
                key: const Key('arrival_geofence_toggle'),
                value: enabled,
                onChanged: state.busy ? null : onToggle,
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (state.busy) ...[
            const LinearProgressIndicator(),
            const SizedBox(height: 10),
          ],
          Text(
            state.message,
            key: const Key('arrival_geofence_message'),
            style: const TextStyle(color: AppColors.textMuted, height: 1.45),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _StatusChip(
                label: _permissionLabel(state.status),
                ok:
                    state.status.permission ==
                        HomeLocationPermission.background &&
                    state.status.preciseLocationGranted,
              ),
              _StatusChip(
                label: state.status.locationServicesEnabled
                    ? '위치 서비스 켜짐'
                    : '위치 서비스 꺼짐',
                ok: state.status.locationServicesEnabled,
              ),
              _StatusChip(
                label: _registrationLabel(state.status.registration),
                ok:
                    state.status.registration ==
                    HomeGeofenceRegistration.registered,
              ),
            ],
          ),
          if (state.status.lastTransitionAt != null) ...[
            const SizedBox(height: 12),
            Text(
              '최근 경계 감지: ${_dateTimeLabel(state.status.lastTransitionAt!)}',
              style: const TextStyle(fontSize: 12, color: AppColors.textMuted),
            ),
          ],
        ],
      ),
    );
  }

  static String _permissionLabel(HomeGeofenceStatus status) {
    if (!status.preciseLocationGranted) {
      return '정확한 위치 필요';
    }
    return switch (status.permission) {
      HomeLocationPermission.background => '항상 위치 허용',
      HomeLocationPermission.foregroundOnly => '사용 중만 허용',
      HomeLocationPermission.denied => '위치 권한 없음',
    };
  }

  static String _registrationLabel(HomeGeofenceRegistration registration) =>
      switch (registration) {
        HomeGeofenceRegistration.registered => '경계 등록 완료',
        HomeGeofenceRegistration.registering => '경계 등록 중',
        HomeGeofenceRegistration.error => '경계 등록 실패',
        HomeGeofenceRegistration.notRegistered => '경계 미등록',
      };

  static String _dateTimeLabel(DateTime value) {
    final local = value.toLocal();
    String two(int number) => number.toString().padLeft(2, '0');
    return '${local.year}.${two(local.month)}.${two(local.day)} '
        '${two(local.hour)}:${two(local.minute)}';
  }
}

class _HomeLocationCard extends StatelessWidget {
  const _HomeLocationCard({
    required this.state,
    required this.onUseCurrentLocation,
  });

  final HomeGeofenceViewState state;
  final Future<bool> Function() onUseCurrentLocation;

  @override
  Widget build(BuildContext context) {
    final configured = state.hasHomeLocation;
    return _Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            '집 위치',
            style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(
            configured
                ? '위도 ${state.homeLatitude!.toStringAsFixed(6)} · 경도 ${state.homeLongitude!.toStringAsFixed(6)}'
                : '아직 집 위치를 등록하지 않았습니다.',
            style: const TextStyle(color: AppColors.textMuted),
          ),
          const SizedBox(height: 14),
          FilledButton.tonalIcon(
            key: const Key('arrival_use_current_location'),
            onPressed: state.busy || state.status.enabled
                ? null
                : onUseCurrentLocation,
            icon: const Icon(Icons.my_location_rounded),
            label: Text(configured ? '현재 위치로 다시 설정' : '현재 위치를 집으로 등록'),
          ),
          if (state.status.enabled) ...[
            const SizedBox(height: 8),
            const Text(
              '집 위치를 바꾸려면 귀가 감지를 먼저 꺼 주세요.',
              style: TextStyle(fontSize: 12, color: AppColors.textMuted),
            ),
          ],
        ],
      ),
    );
  }
}

class _RadiusCard extends StatelessWidget {
  const _RadiusCard({
    required this.state,
    required this.onApproachChanged,
    required this.onCancelChanged,
  });

  final HomeGeofenceViewState state;
  final ValueChanged<double> onApproachChanged;
  final ValueChanged<double> onCancelChanged;

  @override
  Widget build(BuildContext context) {
    final editable = !state.busy && !state.status.enabled;
    return _Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            '감지 반경',
            style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 14),
          _RadiusSlider(
            label: '마중 시작',
            description: '이 거리 안으로 들어오면 APPROACH를 보냅니다.',
            value: state.approachRadius,
            min: 150,
            max: 1000,
            onChanged: editable ? onApproachChanged : null,
          ),
          const Divider(height: 28),
          _RadiusSlider(
            label: '마중 취소·재활성화',
            description: '이 거리 밖으로 나가면 CANCEL 후 다음 방문을 기다립니다.',
            value: state.cancelRadius,
            min: state.approachRadius + 200,
            max: 1600,
            onChanged: editable ? onCancelChanged : null,
          ),
          const SizedBox(height: 6),
          const Text(
            '두 반경 사이를 최소 200m 완충 구역으로 두어 GPS 흔들림에 따른 반복 출발을 막습니다.',
            style: TextStyle(
              fontSize: 12,
              color: AppColors.textMuted,
              height: 1.45,
            ),
          ),
        ],
      ),
    );
  }
}

class _RadiusSlider extends StatelessWidget {
  const _RadiusSlider({
    required this.label,
    required this.description,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
  });

  final String label;
  final String description;
  final double value;
  final double min;
  final double max;
  final ValueChanged<double>? onChanged;

  @override
  Widget build(BuildContext context) {
    final safeValue = value.clamp(min, max).toDouble();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                label,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
            Text(
              '${safeValue.round()}m',
              style: const TextStyle(
                color: AppColors.primary,
                fontWeight: FontWeight.w800,
              ),
            ),
          ],
        ),
        Slider(
          value: safeValue,
          min: min,
          max: max,
          divisions: ((max - min) / 50).round(),
          onChanged: onChanged,
        ),
        Text(
          description,
          style: const TextStyle(fontSize: 12, color: AppColors.textMuted),
        ),
      ],
    );
  }
}

class _HowItWorksCard extends StatelessWidget {
  const _HowItWorksCard({
    required this.approachRadius,
    required this.cancelRadius,
  });

  final double approachRadius;
  final double cancelRadius;

  @override
  Widget build(BuildContext context) {
    return _Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            '동작 기준',
            style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
          ),
          SizedBox(height: 12),
          _FlowRow(
            icon: Icons.directions_walk_rounded,
            text: '${approachRadius.round()}m 안으로 진입 → 오린카 마중 시작',
          ),
          const SizedBox(height: 10),
          _FlowRow(
            icon: Icons.swap_horiz_rounded,
            text:
                '${approachRadius.round()}~${cancelRadius.round()}m → 현재 상태 유지',
          ),
          const SizedBox(height: 10),
          _FlowRow(
            icon: Icons.home_rounded,
            text: '${cancelRadius.round()}m 밖으로 이탈 → 마중 취소·HOME 복귀',
          ),
          const SizedBox(height: 12),
          const Text(
            '집 안에서 처음 켜면 바로 출발하지 않습니다. 먼저 바깥 반경 밖으로 나간 뒤 다음 진입부터 감지합니다.',
            style: TextStyle(color: AppColors.textMuted, height: 1.45),
          ),
        ],
      ),
    );
  }
}

class _FlowRow extends StatelessWidget {
  const _FlowRow({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 20, color: AppColors.primary),
        const SizedBox(width: 10),
        Expanded(child: Text(text)),
      ],
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.label, required this.ok});

  final String label;
  final bool ok;

  @override
  Widget build(BuildContext context) {
    final color = ok ? AppColors.primary : AppColors.textMuted;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          color: color,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(22),
        boxShadow: [
          BoxShadow(
            color: AppColors.primary.withValues(alpha: 0.07),
            blurRadius: 16,
            offset: const Offset(0, 5),
          ),
        ],
      ),
      child: child,
    );
  }
}
