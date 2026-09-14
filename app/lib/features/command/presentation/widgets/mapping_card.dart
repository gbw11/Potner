import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/command/application/device_command_debug_controller.dart';
import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/device/domain/device_models.dart';

/// 지도 제작(SLAM) 카드다. 위치 좌표를 넣으려면 지도가 먼저 있어야 하므로 위치 설정 화면
/// 맨 위에 둔다.
///
/// 세 버튼이 하나의 흐름이다 — 시작을 누르면 로봇이 자율주행을 내리고 지도 그리기로 들어가고,
/// 그 동안 사용자가 **기존 방향 버튼**으로 집을 돌며 지도를 그린 뒤, 저장이나 취소로 끝낸다.
/// 주행 자체는 이 화면이 하지 않는다. 수동 주행 통로를 그대로 쓰므로 여기서 다시 만들지 않는다.
///
/// **진행 여부를 앱이 따로 저장하지 않는다.** 명령 이력에서 마지막 지도 명령이 무엇이었는지
/// 보고 판단한다 — 앱을 껐다 켜도, 다른 사람이 시작해 두었어도 같은 상태를 본다.
class MappingCard extends ConsumerWidget {
  const MappingCard({required this.robot, super.key});

  final ManagedRobot robot;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plantId = robot.assignedPlantId;
    final state = ref.watch(deviceCommandDebugControllerProvider);
    final controller = ref.read(deviceCommandDebugControllerProvider.notifier);

    return Card(
      key: const Key('mapping_card'),
      margin: const EdgeInsets.only(bottom: 16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.map_outlined, size: 20),
                const SizedBox(width: 8),
                const Text(
                  '집 지도 그리기',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                ),
              ],
            ),
            const SizedBox(height: 10),
            const Text(
              '시작을 누르면 로봇이 지도 그리기로 들어가요. 그 상태에서 방향 버튼으로 '
              '집을 천천히 한 바퀴 돌면 지도가 그려져요. 빠르게 몰면 지도가 겹쳐 그려지니 '
              '천천히요.\n'
              '다 돌았으면 저장을 눌러야 지도가 남고, 그 지도로만 위치 좌표를 넣을 수 있어요.',
              style: TextStyle(color: AppColors.textMuted, height: 1.5, fontSize: 13),
            ),
            const SizedBox(height: 14),
            if (plantId == null)
              const Text(
                key: Key('mapping_no_plant'),
                '배정된 식물이 없어 명령을 보낼 수 없어요. 먼저 이 로봇에 식물을 배정해 주세요.',
                style: TextStyle(color: AppColors.textMuted, fontSize: 13),
              )
            else ...[
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.icon(
                    key: const Key('mapping_start'),
                    onPressed: state.isBusy
                        ? null
                        : () => controller.send(
                            plantId: plantId,
                            type: DeviceCommandType.mappingStart,
                          ),
                    icon: const Icon(Icons.play_arrow, size: 18),
                    label: const Text('시작'),
                  ),
                  FilledButton.tonalIcon(
                    key: const Key('mapping_save'),
                    onPressed: state.isBusy
                        ? null
                        : () => controller.send(
                            plantId: plantId,
                            type: DeviceCommandType.mappingSave,
                          ),
                    icon: const Icon(Icons.save_outlined, size: 18),
                    label: const Text('저장하고 끝내기'),
                  ),
                  OutlinedButton.icon(
                    key: const Key('mapping_cancel'),
                    onPressed: state.isBusy
                        ? null
                        : () => _confirmCancel(context, controller, plantId),
                    icon: const Icon(Icons.close, size: 18),
                    label: const Text('저장 없이 취소'),
                  ),
                ],
              ),
              if (state.lastType?.isMapping == true) ...[
                const SizedBox(height: 12),
                Text(
                  key: const Key('mapping_status'),
                  state.message,
                  style: TextStyle(
                    fontSize: 13,
                    color: switch (state.phase) {
                      DeviceCommandDebugPhase.failure => AppColors.error,
                      DeviceCommandDebugPhase.success => AppColors.primary,
                      _ => AppColors.textMuted,
                    },
                  ),
                ),
              ],
            ],
          ],
        ),
      ),
    );
  }

  /// 취소는 그린 지도를 버린다. 되돌릴 수 없으므로 한 번 묻는다.
  Future<void> _confirmCancel(
    BuildContext context,
    DeviceCommandDebugController controller,
    String plantId,
  ) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('지도 그리기 취소'),
        content: const Text('지금까지 그린 지도를 저장하지 않고 버려요. 되돌릴 수 없어요.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('그만두기'),
          ),
          FilledButton(
            key: const Key('mapping_cancel_confirm'),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('버리기'),
          ),
        ],
      ),
    );
    if (confirmed != true) {
      return;
    }
    await controller.send(
      plantId: plantId,
      type: DeviceCommandType.mappingCancel,
    );
  }
}
