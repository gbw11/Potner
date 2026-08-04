import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/command/application/robot_drive_controller.dart';
import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/device/presentation/pages/device_management_page.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 방향 버튼으로 로봇을 직접 미는 화면이다.
///
/// 목적지 이동(장치 관리의 이동 버튼)과 다른 통로다. 그쪽은 목적지의 지도 좌표가 입력되어 있어야
/// 하고 좌표가 비어 있으면 한 발짝도 움직일 수 없다. 이 화면은 좌표를 보지 않으므로 **SLAM 지도를
/// 만들기 전에도 바퀴가 도는지 확인할 수 있다.**
///
/// 누른 만큼 계속 움직이는 조작(누름 유지)이 아니다. 버튼 한 번이 명령 한 번이고 로봇은 서버가
/// 정한 짧은 시간만 움직이다 스스로 멈춘다. 이것이 안전장치다 — 앱이 죽거나 와이파이가 끊겨
/// 정지 명령이 못 나가도 로봇이 계속 달리지 않는다.
class RobotDrivePage extends ConsumerWidget {
  const RobotDrivePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final robots = ref.watch(robotsProvider);

    return Scaffold(
      key: const Key('robot_drive_page'),
      appBar: AppBar(
        title: const Text('로봇 조작'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('robot_drive_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/devices'),
        ),
      ),
      body: SafeArea(
        child: robots.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _DriveMessage(
            text: plantErrorMessage(error, '장치 정보를 불러오지 못했습니다.'),
            onRetry: () => ref.invalidate(robotsProvider),
          ),
          data: (items) {
            // 명령은 식물 단위다. 배정된 식물이 없으면 서버가 보낼 대상을 정할 수 없다.
            final assigned = items
                .where((robot) => robot.assignedPlantId != null)
                .firstOrNull;
            if (assigned == null) {
              return const _DriveMessage(
                key: Key('robot_drive_unavailable'),
                text: '배정된 식물이 없어 로봇을 움직일 수 없어요.\n장치 관리에서 로봇을 식물에 배정해 주세요.',
              );
            }
            return _DrivePad(robot: assigned);
          },
        ),
      ),
    );
  }
}

class _DriveMessage extends StatelessWidget {
  const _DriveMessage({required this.text, this.onRetry, super.key});

  final String text;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              text,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textMuted, height: 1.5),
            ),
            if (onRetry != null) ...[
              const SizedBox(height: 16),
              FilledButton.tonal(
                key: const Key('robot_drive_retry'),
                onPressed: onRetry,
                child: const Text('다시 시도'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _DrivePad extends ConsumerWidget {
  const _DrivePad({required this.robot});

  final ManagedRobot robot;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(robotDriveControllerProvider);
    final controller = ref.read(robotDriveControllerProvider.notifier);
    final plantId = robot.assignedPlantId!;

    final statusColor = switch (state.phase) {
      RobotDrivePhase.success => AppColors.primary,
      RobotDrivePhase.failure => AppColors.error,
      _ => AppColors.textMuted,
    };

    void send(DriveDirection direction) {
      controller.send(plantId: plantId, direction: direction);
    }

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
      children: [
        Text(
          '${robot.assignedPlantName ?? '배정된 식물'} · ${robot.name}',
          textAlign: TextAlign.center,
          style: const TextStyle(color: AppColors.textMuted, fontSize: 13),
        ),
        const SizedBox(height: 6),
        Text(
          state.message,
          key: const Key('robot_drive_status'),
          textAlign: TextAlign.center,
          style: TextStyle(color: statusColor, height: 1.45),
        ),
        const SizedBox(height: 20),
        // 화면의 배치가 실제 움직임과 같아야 한다. 위가 전진, 아래가 후진, 좌우가 제자리 회전이다.
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [_DriveButton(DriveDirection.forward, onPressed: send)],
        ),
        const SizedBox(height: 10),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            _DriveButton(DriveDirection.left, onPressed: send),
            const SizedBox(width: 10),
            _DriveButton(DriveDirection.stop, onPressed: send),
            const SizedBox(width: 10),
            _DriveButton(DriveDirection.right, onPressed: send),
          ],
        ),
        const SizedBox(height: 10),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [_DriveButton(DriveDirection.backward, onPressed: send)],
        ),
        const SizedBox(height: 24),
        const Text(
          '버튼 한 번에 짧게 움직이고 스스로 멈춥니다. 더 가려면 여러 번 누르세요.\n'
          '속도와 움직이는 시간은 서버 설정을 따릅니다.',
          textAlign: TextAlign.center,
          style: TextStyle(color: AppColors.textMuted, fontSize: 12, height: 1.5),
        ),
      ],
    );
  }
}

/// 방향 버튼 하나다.
///
/// 비활성 상태를 두지 않는다. 앞선 요청이 날아가는 중에도 눌릴 수 있어야 한다 — 특히 정지가
/// 막히면 안 된다. 움직이는 로봇을 세울 수 없는 버튼은 안전장치가 아니다.
class _DriveButton extends StatelessWidget {
  const _DriveButton(this.direction, {required this.onPressed});

  final DriveDirection direction;
  final ValueChanged<DriveDirection> onPressed;

  static const _size = 96.0;

  @override
  Widget build(BuildContext context) {
    final isStop = direction == DriveDirection.stop;
    final icon = switch (direction) {
      DriveDirection.forward => Icons.keyboard_arrow_up_rounded,
      DriveDirection.backward => Icons.keyboard_arrow_down_rounded,
      DriveDirection.left => Icons.rotate_left_rounded,
      DriveDirection.right => Icons.rotate_right_rounded,
      DriveDirection.stop => Icons.stop_rounded,
    };

    return SizedBox(
      width: _size,
      height: _size,
      child: Semantics(
        button: true,
        label: direction.label,
        child: Material(
          color: isStop ? AppColors.error : AppColors.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(20),
            side: BorderSide(
              color: isStop
                  ? AppColors.error
                  : AppColors.primary.withValues(alpha: 0.3),
            ),
          ),
          child: InkWell(
            key: Key('robot_drive_${direction.wireName}'),
            borderRadius: BorderRadius.circular(20),
            onTap: () => onPressed(direction),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  icon,
                  size: 34,
                  color: isStop ? Colors.white : AppColors.primary,
                ),
                const SizedBox(height: 2),
                Text(
                  direction.label,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: isStop ? Colors.white : AppColors.primary,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
