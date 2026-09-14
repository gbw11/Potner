import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/arrival/application/arrival_debug_controller.dart';
import 'package:potner_app/features/command/application/device_command_debug_controller.dart';
import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/command/presentation/widgets/care_run_card.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/home/presentation/controllers/home_controller.dart';
import 'package:potner_app/features/plant/data/plant_repository_impl.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

final robotsProvider = FutureProvider.autoDispose<List<ManagedRobot>>((ref) {
  return ref.watch(deviceRepositoryProvider).getRobots();
});

/// 행동 상태는 배정된 식물 기준 조회로만 내려오므로 식물별로 따로 불러온다.
final robotLiveStatusProvider = FutureProvider.autoDispose
    .family<RobotLiveStatus?, String>((ref, plantId) {
      return ref.watch(deviceRepositoryProvider).getPlantRobotStatus(plantId);
    });

/// 장치 관리다. 등록한 로봇의 연결 상태·배터리·행동 상태를 보여 주고
/// 업로드 토큰 재발급과 식물 배정 해제를 처리한다.
class DeviceManagementPage extends ConsumerWidget {
  const DeviceManagementPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final robots = ref.watch(robotsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('장치 관리'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('device_management_back'),
          onPressed: () => returnFromSharedPage(context, fallbackLocation: '/'),
        ),
      ),
      body: SafeArea(
        child: robots.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    plantErrorMessage(error, '장치 정보를 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('device_management_retry'),
                    onPressed: () => ref.invalidate(robotsProvider),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (items) => items.isEmpty
              ? _EmptyRobots(
                  onRegister: () {
                    final registration = Uri.parse(
                      withCurrentNavigationOrigin(context, '/devices/register'),
                    );
                    context.go(
                      registration
                          .replace(
                            queryParameters: {
                              ...registration.queryParameters,
                              'completion': 'management',
                            },
                          )
                          .toString(),
                    );
                  },
                )
              : RefreshIndicator(
                  onRefresh: () => ref.refresh(robotsProvider.future),
                  child: ListView(
                    key: const Key('robot_list'),
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
                    children: [
                      if (kDebugMode) ...[
                        const _ArrivalDebugPanel(),
                        const SizedBox(height: 16),
                        // 자동 케어 회차를 수동 명령보다 위에 둔다. 시연에서 먼저 보여줄 것은
                        // 로봇이 다녀오는 회차이고, 수동 명령은 그것이 안 될 때 단계를 하나씩
                        // 짚어보는 통로다.
                        _CareRunSection(robots: items),
                        _DeviceCommandDebugPanel(robots: items),
                        const SizedBox(height: 16),
                      ],
                      for (final robot in items) ...[
                        _RobotCard(robot: robot),
                        const SizedBox(height: 16),
                      ],
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}

class _ArrivalDebugPanel extends ConsumerWidget {
  const _ArrivalDebugPanel();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(arrivalDebugControllerProvider);
    final controller = ref.read(arrivalDebugControllerProvider.notifier);
    final isSuccess = state.phase == ArrivalDebugPhase.success;
    final isFailure = state.phase == ArrivalDebugPhase.failure;
    final statusColor = isSuccess
        ? AppColors.primary
        : isFailure
        ? AppColors.error
        : AppColors.textMuted;

    return Container(
      key: const Key('arrival_debug_panel'),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.primary.withValues(alpha: 0.18)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Row(
            children: [
              Icon(Icons.home_work_outlined, color: AppColors.primary),
              SizedBox(width: 8),
              Text(
                '귀가 통신 테스트',
                style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            state.message,
            key: const Key('arrival_debug_status'),
            style: TextStyle(color: statusColor, height: 1.4),
          ),
          if (state.lastEventId != null) ...[
            const SizedBox(height: 6),
            Text(
              'eventId: ${state.lastEventId}',
              key: const Key('arrival_debug_event_id'),
              style: const TextStyle(
                color: AppColors.textMuted,
                fontSize: 11,
                fontFamily: 'monospace',
              ),
            ),
          ],
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  key: const Key('arrival_debug_start'),
                  onPressed: state.canStart ? controller.startWelcome : null,
                  icon: const Icon(Icons.directions_walk_rounded),
                  label: const Text('마중 시작'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: OutlinedButton.icon(
                  key: const Key('arrival_debug_cancel'),
                  onPressed: state.canCancel ? controller.cancelWelcome : null,
                  icon: const Icon(Icons.home_rounded),
                  label: const Text('취소·HOME'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          const Text(
            '디버그 빌드에서만 표시됩니다.',
            textAlign: TextAlign.right,
            style: TextStyle(color: AppColors.textMuted, fontSize: 11),
          ),
        ],
      ),
    );
  }
}

/// 자동 케어 회차 카드를 배정된 식물에 붙인다.
///
/// 배정이 없으면 아무것도 그리지 않는다. 바로 아래 수동 명령 패널이 같은 이유를 이미 말하므로
/// 같은 문장을 두 번 보여줄 이유가 없다.
class _CareRunSection extends StatelessWidget {
  const _CareRunSection({required this.robots});

  final List<ManagedRobot> robots;

  @override
  Widget build(BuildContext context) {
    // 회차는 식물 단위다. 배정된 식물이 없으면 서버가 대상을 정할 수 없다.
    final assigned = robots
        .where((robot) => robot.assignedPlantId != null)
        .firstOrNull;
    if (assigned == null) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: CareRunCard(
        plantId: assigned.assignedPlantId!,
        plantLabel:
            '${assigned.assignedPlantName ?? '배정된 식물'} · ${assigned.name}',
      ),
    );
  }
}

/// 손으로 장치 명령을 보내는 패널이다. 자동 케어는 센서값과 지도 좌표가 갖춰져야 트리거되므로
/// 시연 자리에서 조건을 만들 수 없을 때 여기서 직접 보낸다.
///
/// 급수·촬영·송풍은 라즈베리파이에게 바로 가고 **좌표를 보지 않는다.** 좌표가 비어 있어도 이
/// 경로로는 로봇이 움직인다. 이동만 목적지 좌표를 요구하며, 없으면 서버가
/// `LOCATION_POSE_NOT_CONFIGURED` 로 거절한다.
class _DeviceCommandDebugPanel extends ConsumerStatefulWidget {
  const _DeviceCommandDebugPanel({required this.robots});

  final List<ManagedRobot> robots;

  @override
  ConsumerState<_DeviceCommandDebugPanel> createState() =>
      _DeviceCommandDebugPanelState();
}

class _DeviceCommandDebugPanelState
    extends ConsumerState<_DeviceCommandDebugPanel> {
  RobotLocationType _destination = RobotLocationType.waterStation;

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(deviceCommandDebugControllerProvider);
    final controller = ref.read(deviceCommandDebugControllerProvider.notifier);

    // 명령은 식물 단위다. 배정된 식물이 없으면 서버가 보낼 대상을 정할 수 없다.
    final assigned = widget.robots
        .where((robot) => robot.assignedPlantId != null)
        .firstOrNull;

    final statusColor = switch (state.phase) {
      DeviceCommandDebugPhase.success => AppColors.primary,
      DeviceCommandDebugPhase.failure => AppColors.error,
      _ => AppColors.textMuted,
    };

    return Container(
      key: const Key('device_command_debug_panel'),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.primary.withValues(alpha: 0.18)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Row(
            children: [
              Icon(Icons.settings_remote_outlined, color: AppColors.primary),
              SizedBox(width: 8),
              Text(
                '수동 명령',
                style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (assigned == null)
            const Text(
              '배정된 식물이 없어 명령을 보낼 수 없어요. 로봇을 식물에 배정해 주세요.',
              key: Key('device_command_debug_unavailable'),
              style: TextStyle(color: AppColors.textMuted, height: 1.4),
            )
          else ...[
            Text(
              '${assigned.assignedPlantName ?? '배정된 식물'} · ${assigned.name}',
              style: const TextStyle(
                color: AppColors.textMuted,
                fontSize: 12,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              state.message,
              key: const Key('device_command_debug_status'),
              style: TextStyle(color: statusColor, height: 1.4),
            ),
            if (state.lastRequestId != null) ...[
              const SizedBox(height: 6),
              Text(
                'requestId: ${state.lastRequestId}',
                key: const Key('device_command_debug_request_id'),
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 11,
                  fontFamily: 'monospace',
                ),
              ),
            ],
            const SizedBox(height: 14),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final type in const [
                  DeviceCommandType.water,
                  DeviceCommandType.capture,
                  DeviceCommandType.fan,
                ])
                  FilledButton.tonal(
                    key: Key('device_command_debug_${type.wireName}'),
                    onPressed: state.isBusy
                        ? null
                        : () => controller.send(
                            plantId: assigned.assignedPlantId!,
                            type: type,
                          ),
                    child: Text(type.label),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: DropdownButtonFormField<RobotLocationType>(
                    key: const Key('device_command_debug_destination'),
                    initialValue: _destination,
                    isExpanded: true,
                    decoration: const InputDecoration(isDense: true),
                    items: [
                      for (final type in RobotLocationType.values)
                        DropdownMenuItem(value: type, child: Text(type.label)),
                    ],
                    onChanged: state.isBusy
                        ? null
                        : (value) => setState(
                            () => _destination = value ?? _destination,
                          ),
                  ),
                ),
                const SizedBox(width: 10),
                OutlinedButton.icon(
                  key: const Key('device_command_debug_NAVIGATE'),
                  onPressed: state.isBusy
                      ? null
                      : () => controller.send(
                          plantId: assigned.assignedPlantId!,
                          type: DeviceCommandType.navigate,
                          destination: _destination,
                        ),
                  icon: const Icon(Icons.navigation_outlined),
                  label: const Text('이동'),
                ),
              ],
            ),
            const Divider(height: 26),
            const Text(
              '표정 — 약 30초 뒤 서버 판정으로 돌아갑니다',
              style: TextStyle(color: AppColors.textMuted, fontSize: 12),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final expression in PlantExpression.values)
                  OutlinedButton(
                    key: Key('device_expression_debug_${expression.wireName}'),
                    onPressed: state.isBusy
                        ? null
                        : () => controller.sendExpression(
                            plantId: assigned.assignedPlantId!,
                            expression: expression,
                          ),
                    child: Text(expression.label),
                  ),
              ],
            ),
            const Divider(height: 26),
            const Text(
              '푸시 — 폰으로 바로 나갑니다',
              style: TextStyle(color: AppColors.textMuted, fontSize: 12),
            ),
            const SizedBox(height: 8),
            _RepottingReminderButton(plantId: assigned.assignedPlantId!),
          ],
          const SizedBox(height: 8),
          const Text(
            '디버그 빌드에서만 표시됩니다.',
            textAlign: TextAlign.right,
            style: TextStyle(color: AppColors.textMuted, fontSize: 11),
          ),
        ],
      ),
    );
  }
}

/// 분갈이 안내 푸시를 손으로 한 번 보낸다.
///
/// 장치 명령이 아니라 서버가 폰으로 보내는 알림이라 회신을 기다릴 것이 없다. 그래서
/// [DeviceCommandDebugController] 의 상태를 쓰지 않고 자기 상태만 갖는다 — 진행 중인 명령
/// 폴링을 이 발송이 끊어서도 안 된다.
///
/// 시기 판정은 서버가 하지 않고 이력도 남기지 않으므로 누를 때마다 나간다.
class _RepottingReminderButton extends ConsumerStatefulWidget {
  const _RepottingReminderButton({required this.plantId});

  final String plantId;

  @override
  ConsumerState<_RepottingReminderButton> createState() =>
      _RepottingReminderButtonState();
}

class _RepottingReminderButtonState
    extends ConsumerState<_RepottingReminderButton> {
  bool _sending = false;

  Future<void> _send() async {
    setState(() => _sending = true);
    String message;
    try {
      await ref
          .read(plantRepositoryProvider)
          .sendRepottingReminder(widget.plantId);
      message = '분갈이 알림을 보냈어요. 폰의 알림을 확인해 주세요.';
    } catch (error) {
      message = plantErrorMessage(error, '분갈이 알림을 보내지 못했습니다.');
    }
    if (!mounted) {
      return;
    }
    setState(() => _sending = false);
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: OutlinedButton.icon(
        key: const Key('device_repotting_reminder_debug'),
        // 테마가 minimumSize 를 Size.fromHeight(56) 으로 둬서 기본값이면 가로를 꽉 채운다.
        style: OutlinedButton.styleFrom(minimumSize: const Size(0, 44)),
        onPressed: _sending ? null : _send,
        icon: const Icon(Icons.yard_outlined),
        label: Text(_sending ? '보내는 중…' : '분갈이 알림'),
      ),
    );
  }
}

class _RobotCard extends ConsumerWidget {
  const _RobotCard({required this.robot});

  final ManagedRobot robot;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final assignedPlantId = robot.assignedPlantId;

    return Container(
      key: Key('robot_card_${robot.robotId}'),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(24),
        boxShadow: [
          BoxShadow(
            color: AppColors.primary.withValues(alpha: 0.08),
            blurRadius: 16,
            offset: const Offset(0, 5),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      robot.name,
                      style: const TextStyle(
                        fontSize: 19,
                        fontWeight: FontWeight.w800,
                        color: AppColors.text,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      robot.deviceUid,
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textMuted,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ],
                ),
              ),
              _ConnectionBadge(status: robot.connectionStatus),
            ],
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              _BatteryDonut(percent: robot.batteryPercent),
              const SizedBox(width: 20),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '배터리 잔량',
                      style: TextStyle(color: AppColors.textMuted),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      robot.batteryPercent == null
                          ? '수집 전'
                          : '${robot.batteryPercent}%',
                      style: const TextStyle(
                        fontSize: 26,
                        fontWeight: FontWeight.w800,
                        color: AppColors.primary,
                      ),
                    ),
                    if (assignedPlantId != null) ...[
                      const SizedBox(height: 8),
                      _ActivityChip(plantId: assignedPlantId),
                    ],
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          _InfoRow(
            icon: Icons.local_florist_outlined,
            label: '담당 식물',
            value: robot.assignedPlantName ?? '아직 배정되지 않음 (측정값이 저장되지 않아요)',
          ),
          if (robot.firmwareVersion != null)
            _InfoRow(
              icon: Icons.memory_rounded,
              label: '펌웨어',
              value: robot.firmwareVersion!,
            ),
          if (robot.devices.isNotEmpty) ...[
            const Divider(height: 26),
            for (final device in robot.devices)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  children: [
                    const Icon(
                      Icons.sensors_rounded,
                      size: 18,
                      color: AppColors.textMuted,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '${_deviceTypeLabel(device.deviceType)} · ${device.deviceUid}',
                        style: const TextStyle(fontSize: 13),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    _ConnectionBadge(
                      status: device.connectionStatus,
                      dense: true,
                    ),
                  ],
                ),
              ),
          ],
          const Divider(height: 26),
          Wrap(
            alignment: WrapAlignment.end,
            spacing: 4,
            children: [
              TextButton(
                key: Key('robot_locations_${robot.robotId}'),
                onPressed: () => context.go(
                  withCurrentNavigationOrigin(
                    context,
                    '/devices/${robot.robotId}/locations',
                  ),
                ),
                child: const Text('위치 설정'),
              ),
              TextButton(
                key: Key('reissue_token_${robot.robotId}'),
                onPressed: () => _confirmAndReissueToken(context, ref),
                child: const Text('토큰 재발급'),
              ),
              if (assignedPlantId != null)
                TextButton(
                  key: Key('unassign_${robot.robotId}'),
                  style: TextButton.styleFrom(foregroundColor: AppColors.error),
                  onPressed: () =>
                      _confirmAndUnassign(context, ref, assignedPlantId),
                  child: const Text('배정 해제'),
                ),
              TextButton(
                key: Key('delete_robot_${robot.robotId}'),
                style: TextButton.styleFrom(foregroundColor: AppColors.error),
                onPressed: () => _confirmAndDelete(context, ref),
                child: const Text('삭제'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  static String _deviceTypeLabel(String deviceType) {
    return switch (deviceType) {
      'RASPBERRY_PI' => '스테이션(라즈베리파이)',
      'JETSON_ORIN' => '젯슨 오린',
      _ => deviceType,
    };
  }

  Future<void> _confirmAndReissueToken(
    BuildContext context,
    WidgetRef ref,
  ) async {
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('업로드 토큰 재발급'),
        content: const Text('재발급 즉시 이전 토큰이 무효가 되어 라즈베리 설정도 함께 바꿔야 해요. 계속할까요?'),
        actions: [
          TextButton(
            key: const Key('reissue_cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('취소'),
          ),
          FilledButton(
            key: const Key('reissue_confirm'),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('재발급'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) {
      return;
    }

    final String token;
    try {
      token = await ref
          .read(deviceRepositoryProvider)
          .reissueUploadToken(robot.robotId);
    } catch (error) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '토큰을 재발급하지 못했습니다.'))),
        );
      return;
    }
    if (!context.mounted) {
      return;
    }
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: const Text('새 업로드 토큰'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '아래 토큰은 지금만 볼 수 있어요. 라즈베리 설정에 넣어 주세요.',
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
                token,
                key: const Key('reissued_upload_token'),
                style: const TextStyle(fontFamily: 'monospace'),
              ),
            ),
          ],
        ),
        actions: [
          TextButton.icon(
            key: const Key('reissued_token_copy'),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: token));
              messenger
                ..hideCurrentSnackBar()
                ..showSnackBar(const SnackBar(content: Text('토큰을 복사했어요.')));
            },
            icon: const Icon(Icons.copy_rounded, size: 18),
            label: const Text('복사'),
          ),
          FilledButton(
            key: const Key('reissued_token_done'),
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('저장했어요'),
          ),
        ],
      ),
    );
  }

  Future<void> _confirmAndUnassign(
    BuildContext context,
    WidgetRef ref,
    String plantId,
  ) async {
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('배정 해제'),
        content: Text(
          '${robot.assignedPlantName ?? '식물'}에서 ${robot.name}을(를) 해제하면 '
          '측정값이 더 이상 저장되지 않아요. 계속할까요?',
        ),
        actions: [
          TextButton(
            key: const Key('unassign_cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('취소'),
          ),
          FilledButton(
            key: const Key('unassign_confirm'),
            style: FilledButton.styleFrom(backgroundColor: AppColors.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('해제'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) {
      return;
    }

    try {
      await ref.read(deviceRepositoryProvider).unassignPlant(plantId);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('배정을 해제했어요.')));
    } catch (error) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text(plantErrorMessage(error, '배정을 해제하지 못했습니다.'))),
        );
    } finally {
      ref.invalidate(robotsProvider);
      ref.invalidate(homeControllerProvider);
    }
  }

  /// 서버는 행을 지우지 않고 해제 시각만 남긴다. 측정 이력은 원래 식물에 그대로 있고, 해제된
  /// 코드는 곧바로 다시 등록할 수 있다. 그래서 "삭제" 가 아니라 "해제" 로 말한다 — 영구 삭제로
  /// 읽히면 사용자가 되돌릴 수 없다고 오해한다.
  Future<void> _confirmAndDelete(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('기기 해제'),
        content: Text(
          '${robot.name}의 연결을 해제하면 하위 장치와 식물 배정이 함께 풀려요. '
          '지금까지 쌓인 측정 기록은 그대로 남고, 같은 코드로 다시 등록할 수 있어요.\n\n'
          '등록해 둔 위치와 좌표는 따라오지 않으니 다시 입력해야 해요.',
        ),
        actions: [
          TextButton(
            key: const Key('delete_robot_cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('취소'),
          ),
          FilledButton(
            key: const Key('delete_robot_confirm'),
            style: FilledButton.styleFrom(backgroundColor: AppColors.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('해제'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) {
      return;
    }

    try {
      await ref.read(deviceRepositoryProvider).deleteRobot(robot.robotId);
      if (!context.mounted) {
        return;
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text('${robot.name}의 연결을 해제했어요.')));
    } catch (error) {
      if (!context.mounted) {
        return;
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(plantErrorMessage(error, '기기를 해제하지 못했습니다.')),
          ),
        );
    } finally {
      ref.invalidate(robotsProvider);
      ref.invalidate(homeControllerProvider);
    }
  }
}

class _ActivityChip extends ConsumerWidget {
  const _ActivityChip({required this.plantId});

  final String plantId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(robotLiveStatusProvider(plantId));
    final label = status.whenOrNull(
      data: (value) => value == null ? null : _stateLabel(value.currentState),
    );
    if (label == null) {
      return const SizedBox.shrink();
    }
    return Container(
      key: Key('robot_state_$plantId'),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: const Color(0xFFCCEBC0),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: const TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w700,
          color: AppColors.primary,
        ),
      ),
    );
  }

  static String _stateLabel(RobotActivityState state) {
    return switch (state) {
      RobotActivityState.idle => '대기 중',
      RobotActivityState.navigating => '이동 중',
      RobotActivityState.docking => '도킹 중',
      // 서버가 급수와 송풍을 구별하지 못하므로 문구도 하나로 묶는다.
      RobotActivityState.servicing => '케어 중 (급수·송풍)',
      RobotActivityState.greeting => '인사 중',
      RobotActivityState.unknown => '상태 확인 중',
    };
  }
}

class _BatteryDonut extends StatelessWidget {
  const _BatteryDonut({required this.percent});

  final int? percent;

  @override
  Widget build(BuildContext context) {
    final value = percent == null ? null : (percent!.clamp(0, 100)) / 100;
    return SizedBox.square(
      dimension: 72,
      child: Stack(
        fit: StackFit.expand,
        children: [
          CircularProgressIndicator(
            value: value ?? 0,
            strokeWidth: 7,
            backgroundColor: AppColors.surfaceLow,
            color: AppColors.primary,
          ),
          Center(
            child: Icon(
              percent == null
                  ? Icons.battery_unknown_rounded
                  : Icons.battery_charging_full_rounded,
              color: AppColors.primary,
            ),
          ),
        ],
      ),
    );
  }
}

class _ConnectionBadge extends StatelessWidget {
  const _ConnectionBadge({required this.status, this.dense = false});

  final DeviceConnectionStatus status;
  final bool dense;

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (status) {
      DeviceConnectionStatus.online => ('온라인', const Color(0xFF2E7D32)),
      DeviceConnectionStatus.offline => ('오프라인', AppColors.textMuted),
      DeviceConnectionStatus.error => ('오류', AppColors.error),
      DeviceConnectionStatus.unknown => ('알 수 없음', AppColors.textMuted),
    };
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: dense ? 8 : 10,
          height: dense ? 8 : 10,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 6),
        Text(
          label,
          style: TextStyle(
            fontSize: dense ? 12 : 13,
            fontWeight: FontWeight.w600,
            color: color,
          ),
        ),
      ],
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: AppColors.textMuted),
          const SizedBox(width: 8),
          Text('$label ', style: const TextStyle(color: AppColors.textMuted)),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

class _EmptyRobots extends StatelessWidget {
  const _EmptyRobots({required this.onRegister});

  final VoidCallback onRegister;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.smart_toy_outlined,
              size: 52,
              color: AppColors.primarySoft,
            ),
            const SizedBox(height: 14),
            const Text(
              '아직 등록한 디바이스가 없어요.\nPotner를 연결해 보세요!',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.textMuted, height: 1.5),
            ),
            const SizedBox(height: 20),
            FilledButton(
              key: const Key('device_management_register'),
              onPressed: onRegister,
              child: const Text('디바이스 등록하기'),
            ),
          ],
        ),
      ),
    );
  }
}
