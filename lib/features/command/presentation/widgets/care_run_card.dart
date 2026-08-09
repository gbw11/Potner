import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/command/application/care_run_controller.dart';
import 'package:potner_app/features/command/domain/command_models.dart';

/// 자동 케어 한 회차를 지금 시작하는 카드다.
///
/// 옆의 수동 명령 패널과 다른 통로다. 수동 송풍은 스테이션에서 팬만 돌 뿐 로봇이 움직이지
/// 않지만, 여기서는 이동 → 작업 → 복귀가 평소 자동 케어 경로 그대로 이어진다. 건너뛰는 것은
/// **언제 시작할지** 하나뿐이다.
///
/// 진행을 단계 목록으로 보여 준다. 응답이 첫 명령까지만 말해 주므로 상태 한 줄만 두면 회차가
/// 어디까지 갔는지 알 수 없고, 시연 자리에서는 그것이 곧 "지금 뭐 하는 중인가" 를 못 답하는
/// 것과 같다.
class CareRunCard extends ConsumerWidget {
  const CareRunCard({required this.plantId, required this.plantLabel, super.key});

  final String plantId;

  /// 어느 식물의 회차인지. 로봇이 여러 대여도 명령은 식물 단위다.
  final String plantLabel;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(careRunControllerProvider);
    final controller = ref.read(careRunControllerProvider.notifier);

    final statusColor = switch (state.phase) {
      CareRunPhase.success => AppColors.primary,
      CareRunPhase.failure => AppColors.error,
      _ => AppColors.textMuted,
    };

    return Container(
      key: const Key('care_run_panel'),
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
              Icon(Icons.auto_mode_rounded, color: AppColors.primary),
              SizedBox(width: 8),
              Text(
                '자동 케어 실행',
                style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
              ),
            ],
          ),
          const SizedBox(height: 8),
          const Text(
            '평소에는 조건이 갖춰져야 도는 자동 케어를 지금 한 회차 돌려요. 건너뛰는 것은 '
            '시작 조건뿐이고 이동 → 작업 → 복귀는 평소 그대로예요.\n'
            '아래 수동 명령과 다른 통로예요 — 수동 송풍은 스테이션에서 팬만 돌지만, 여기서는 '
            '로봇이 직접 다녀와요.',
            style: TextStyle(
              color: AppColors.textMuted,
              height: 1.5,
              fontSize: 13,
            ),
          ),
          const SizedBox(height: 12),
          Text(
            plantLabel,
            style: const TextStyle(color: AppColors.textMuted, fontSize: 12),
          ),
          const SizedBox(height: 6),
          Text(
            state.message,
            key: const Key('care_run_status'),
            style: TextStyle(color: statusColor, height: 1.4),
          ),
          if (state.steps.isNotEmpty) ...[
            const SizedBox(height: 10),
            Column(
              key: const Key('care_run_steps'),
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final step in state.steps) _StepRow(step: step),
              ],
            ),
          ],
          const SizedBox(height: 14),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final purpose in CareRunPurpose.values)
                FilledButton.tonal(
                  key: Key('care_run_${purpose.wireName}'),
                  // 테마가 minimumSize 를 Size.fromHeight(56) 으로 둬서 기본값이면 버튼
                  // 하나가 가로를 꽉 채운다. 넷이 세로로 쌓이면 카드가 화면을 넘긴다.
                  style: FilledButton.styleFrom(minimumSize: const Size(0, 44)),
                  onPressed: state.isBusy
                      ? null
                      : () => controller.start(
                          plantId: plantId,
                          purpose: purpose,
                        ),
                  child: Text(purpose.label),
                ),
            ],
          ),
          const SizedBox(height: 10),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final purpose in CareRunPurpose.values)
                Padding(
                  padding: const EdgeInsets.only(bottom: 2),
                  child: Text(
                    '${purpose.label} · ${purpose.steps}',
                    style: const TextStyle(
                      color: AppColors.textMuted,
                      fontSize: 11,
                    ),
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

/// 회차의 단계 한 줄이다. 무엇을 했고 어떻게 끝났는지, 값이 있으면 값까지 보여 준다.
class _StepRow extends StatelessWidget {
  const _StepRow({required this.step});

  final DeviceCommandRecord step;

  @override
  Widget build(BuildContext context) {
    final (icon, color) = switch (step.status) {
      DeviceCommandStatus.issued => (
        Icons.more_horiz_rounded,
        AppColors.textMuted,
      ),
      DeviceCommandStatus.ok => (
        Icons.check_circle_outline_rounded,
        AppColors.primary,
      ),
      // 건너뛴 단계는 성공도 실패도 아니다. 붉게 칠하면 고장으로 읽힌다.
      DeviceCommandStatus.skipped => (
        Icons.remove_circle_outline_rounded,
        AppColors.textMuted,
      ),
      DeviceCommandStatus.unknown => (
        Icons.help_outline_rounded,
        AppColors.textMuted,
      ),
      _ => (Icons.error_outline_rounded, AppColors.error),
    };

    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '${step.stepLabel}${_detail()}',
              style: TextStyle(fontSize: 12, color: color),
            ),
          ),
        ],
      ),
    );
  }

  /// 값이 있는 단계만 값을 붙인다. 급수량은 서버가 정한 값이고 송풍 시간은 라즈베리가 실제로
  /// 돌린 시간이라, 둘 다 요청이 아니라 결과다.
  String _detail() {
    if (step.status == DeviceCommandStatus.skipped) {
      // 건너뛴 급수는 dispensedMl 이 0 이다. 그대로 두면 "0ml" 로 보여 펌프가 헛돈 것처럼 읽힌다.
      return ' · 건너뜀';
    }
    final dispensedMl = step.dispensedMl;
    if (dispensedMl != null) {
      final text = dispensedMl == dispensedMl.roundToDouble()
          ? dispensedMl.round().toString()
          : dispensedMl.toStringAsFixed(1);
      return ' · ${text}ml';
    }
    final runSeconds = step.runSeconds;
    if (runSeconds != null && step.status == DeviceCommandStatus.ok) {
      return ' · $runSeconds초';
    }
    return '';
  }
}
