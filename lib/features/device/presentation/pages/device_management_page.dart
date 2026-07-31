import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/device/data/device_repository_impl.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/home/presentation/controllers/home_controller.dart';
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

  Future<void> _confirmAndDelete(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('디바이스 삭제'),
        content: Text(
          '${robot.name}을(를) 삭제하면 연결된 장치와 식물 배정 정보를 더 이상 사용할 수 없어요. '
          '정말 삭제할까요?',
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
            child: const Text('삭제'),
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
        ..showSnackBar(SnackBar(content: Text('${robot.name}을(를) 삭제했어요.')));
    } catch (error) {
      if (!context.mounted) {
        return;
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(plantErrorMessage(error, '디바이스를 삭제하지 못했습니다.')),
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
