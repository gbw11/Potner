import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/command/data/command_repository_impl.dart';
import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/command/domain/command_repository.dart';

final robotDriveControllerProvider =
    NotifierProvider<RobotDriveController, RobotDriveState>(
      RobotDriveController.new,
    );

enum RobotDrivePhase { idle, sending, success, failure }

class RobotDriveState {
  const RobotDriveState({
    this.phase = RobotDrivePhase.idle,
    this.lastDirection,
    this.message = '방향 버튼을 누르면 로봇이 잠깐 움직였다가 멈춰요.',
  });

  final RobotDrivePhase phase;
  final DriveDirection? lastDirection;
  final String message;
}

/// 방향 버튼으로 로봇을 미는 컨트롤러다.
///
/// `DeviceCommandDebugController` 와 달리 폴링하지 않는다. 방향 버튼에는 result 회신 계약이 없어
/// 서버가 수행 여부를 알 수 없고, 앱이 기다릴 대상도 없다. 로봇을 눈으로 봐야 한다.
///
/// **버튼을 잠그지 않는다.** 앞선 요청이 날아가는 중에도 다음 누름을 받는다. 조작 화면에서
/// 잠금은 두 가지로 나쁘다 — 연타가 정상인 조작이 뚝뚝 끊기고, 무엇보다 **정지가 막힌다.**
/// 로봇이 움직이는 중에 세울 수 없는 버튼은 안전장치가 아니다.
///
/// 그 대신 세대 번호로 늦게 온 응답이 최신 상태를 덮어쓰지 못하게 막는다. 응답 순서는 보장되지
/// 않으므로, 이게 없으면 먼저 보낸 전진의 성공 문구가 나중에 보낸 정지의 문구를 지운다.
class RobotDriveController extends Notifier<RobotDriveState> {
  late DeviceCommandRepository _repository;
  int _generation = 0;

  @override
  RobotDriveState build() {
    _repository = ref.read(deviceCommandRepositoryProvider);
    ref.onDispose(() => _generation++);
    return const RobotDriveState();
  }

  Future<void> send({
    required String plantId,
    required DriveDirection direction,
  }) async {
    final generation = ++_generation;
    state = RobotDriveState(
      phase: RobotDrivePhase.sending,
      lastDirection: direction,
      message: '${direction.label} 명령을 보내는 중이에요.',
    );

    try {
      await _repository.drive(plantId: plantId, direction: direction);
    } catch (error) {
      if (generation != _generation) {
        return;
      }
      state = RobotDriveState(
        phase: RobotDrivePhase.failure,
        lastDirection: direction,
        message: _messageFor(error),
      );
      return;
    }

    if (generation != _generation) {
      return;
    }
    state = RobotDriveState(
      phase: RobotDrivePhase.success,
      lastDirection: direction,
      message: direction == DriveDirection.stop
          ? '정지 명령을 보냈어요.'
          : '${direction.label} 명령을 보냈어요. 로봇이 잠깐 움직였다가 스스로 멈춰요.',
    );
  }

  String _messageFor(Object error) {
    if (error is ApiException) {
      return switch (error.kind) {
        ApiExceptionKind.connection ||
        ApiExceptionKind.timeout => '서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.',
        ApiExceptionKind.problem =>
          error.problem?.detail ?? '명령을 보내지 못했습니다.',
        ApiExceptionKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
        _ => '명령을 보내는 중 오류가 발생했습니다.',
      };
    }
    return '명령을 보내는 중 오류가 발생했습니다.';
  }
}
