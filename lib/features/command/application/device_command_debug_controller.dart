import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/command/data/command_repository_impl.dart';
import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/command/domain/command_repository.dart';
import 'package:potner_app/features/device/domain/device_models.dart';

final deviceCommandDebugControllerProvider =
    NotifierProvider<DeviceCommandDebugController, DeviceCommandDebugState>(
      DeviceCommandDebugController.new,
    );

enum DeviceCommandDebugPhase { idle, sending, waiting, success, failure }

class DeviceCommandDebugState {
  const DeviceCommandDebugState({
    this.phase = DeviceCommandDebugPhase.idle,
    this.lastRequestId,
    this.lastType,
    this.status,
    this.message = '아직 명령을 보내지 않았어요.',
  });

  final DeviceCommandDebugPhase phase;
  final String? lastRequestId;
  final DeviceCommandType? lastType;
  final DeviceCommandStatus? status;
  final String message;

  bool get isBusy =>
      phase == DeviceCommandDebugPhase.sending ||
      phase == DeviceCommandDebugPhase.waiting;
}

/// 손으로 장치 명령을 보내고 회신까지 지켜보는 컨트롤러다.
///
/// 자동 케어(수분 부족 → 이동 → 급수 → 복귀)는 센서값과 지도 좌표가 모두 갖춰져야 트리거되는데,
/// 시연 자리에서 그 조건을 만들 수 없을 때가 있다. 급수·촬영·송풍은 라즈베리파이에게 바로 가고
/// **좌표를 보지 않으므로**, 좌표가 비어 있어도 이 경로로는 로봇을 움직일 수 있다.
/// 이동(NAVIGATE)만 목적지 좌표를 요구한다.
///
/// 발행 응답은 `issued` 까지만 말해 준다. 수행 결과는 장치의 MQTT 회신이 반영된 뒤 이력에
/// 나타나므로 폴링으로 확인한다. `ArrivalDebugController` 와 같은 구조다.
class DeviceCommandDebugController extends Notifier<DeviceCommandDebugState> {
  static const _pollInterval = Duration(seconds: 2);

  /// 서버 기본 타임아웃이 120초다. 그보다 넉넉히 두어 서버가 `TIMED_OUT` 을 기록한 것을
  /// 앱도 보고 끝낼 수 있게 한다 — 앱이 먼저 포기하면 원인이 장치인지 앱인지 흐려진다.
  static const _maxPollAttempts = 66;

  late DeviceCommandRepository _repository;
  int _pollGeneration = 0;

  @override
  DeviceCommandDebugState build() {
    _repository = ref.read(deviceCommandRepositoryProvider);
    ref.onDispose(() => _pollGeneration++);
    return const DeviceCommandDebugState();
  }

  Future<void> send({
    required String plantId,
    required DeviceCommandType type,
    RobotLocationType? destination,
  }) async {
    if (state.isBusy) {
      return;
    }
    final generation = ++_pollGeneration;
    state = DeviceCommandDebugState(
      phase: DeviceCommandDebugPhase.sending,
      lastType: type,
      message: '${_describe(type, destination)} 명령을 보내는 중이에요.',
    );

    final DeviceCommandRecord issued;
    try {
      issued = await _repository.issue(
        plantId: plantId,
        type: type,
        destination: destination,
      );
    } catch (error) {
      if (generation != _pollGeneration) {
        return;
      }
      state = DeviceCommandDebugState(
        phase: DeviceCommandDebugPhase.failure,
        lastType: type,
        message: _messageFor(error),
      );
      return;
    }

    if (generation != _pollGeneration) {
      return;
    }
    state = DeviceCommandDebugState(
      phase: DeviceCommandDebugPhase.waiting,
      lastRequestId: issued.requestId,
      lastType: type,
      status: issued.status,
      message: '서버가 발행했어요. 장치 회신을 기다립니다.',
    );
    unawaited(_poll(plantId, issued.requestId, type, generation));
  }

  Future<void> _poll(
    String plantId,
    String requestId,
    DeviceCommandType type,
    int generation,
  ) async {
    for (var attempt = 0; attempt < _maxPollAttempts; attempt++) {
      await Future<void>.delayed(_pollInterval);
      if (generation != _pollGeneration) {
        return;
      }
      final DeviceCommandRecord? found;
      try {
        found = await _findCommand(plantId, requestId);
      } catch (error) {
        if (generation != _pollGeneration) {
          return;
        }
        state = DeviceCommandDebugState(
          phase: DeviceCommandDebugPhase.failure,
          lastRequestId: requestId,
          lastType: type,
          message: _messageFor(error),
        );
        return;
      }
      if (generation != _pollGeneration) {
        return;
      }
      if (found != null && found.status.isTerminal) {
        _applyTerminal(found, type);
        return;
      }
    }

    if (generation == _pollGeneration) {
      state = DeviceCommandDebugState(
        phase: DeviceCommandDebugPhase.failure,
        lastRequestId: requestId,
        lastType: type,
        message: '회신 대기 시간이 지났어요. 장치가 켜져 있는지와 서버 로그를 확인해 주세요.',
      );
    }
  }

  Future<DeviceCommandRecord?> _findCommand(
    String plantId,
    String requestId,
  ) async {
    final now = DateTime.now();
    // 자정을 막 넘겨 발행한 명령이 조회 범위에서 빠지지 않도록 어제까지 함께 본다.
    final commands = await _repository.getCommands(
      plantId: plantId,
      from: now.subtract(const Duration(days: 1)),
      to: now,
    );
    for (final command in commands) {
      if (command.requestId == requestId) {
        return command;
      }
    }
    return null;
  }

  void _applyTerminal(DeviceCommandRecord command, DeviceCommandType type) {
    final success = command.status == DeviceCommandStatus.ok;
    state = DeviceCommandDebugState(
      phase: success
          ? DeviceCommandDebugPhase.success
          : DeviceCommandDebugPhase.failure,
      lastRequestId: command.requestId,
      lastType: type,
      status: command.status,
      message: switch (command.status) {
        DeviceCommandStatus.ok when type == DeviceCommandType.water =>
          '급수를 마쳤어요. 실제 ${_ml(command.dispensedMl)}ml 나갔습니다.',
        DeviceCommandStatus.ok when type == DeviceCommandType.capture =>
          '촬영을 마쳤어요. 포토 로그에 사진이 올라오는지 확인해 주세요.',
        DeviceCommandStatus.ok when type == DeviceCommandType.navigate =>
          '목적지에 도착했어요(도킹 완료).',
        DeviceCommandStatus.ok => '송풍을 마쳤어요.',
        DeviceCommandStatus.busy => '로봇이 다른 작업 중이라 거절했어요. 잠시 뒤 다시 보내면 됩니다.',
        DeviceCommandStatus.timedOut =>
          '제한 시간 안에 회신이 없어 서버가 끊었어요. 장치의 명령 수신 구현을 확인해 주세요.',
        DeviceCommandStatus.error =>
          command.errorMessage ?? '장치가 수행 실패를 보고했어요.',
        _ => '알 수 없는 결과를 받았어요.',
      },
    );
  }

  String _ml(double? value) {
    if (value == null) {
      return '0';
    }
    return value == value.roundToDouble()
        ? value.round().toString()
        : value.toStringAsFixed(1);
  }

  String _describe(DeviceCommandType type, RobotLocationType? destination) {
    if (type == DeviceCommandType.navigate && destination != null) {
      return '${destination.label}(으)로 이동';
    }
    return type.label;
  }

  String _messageFor(Object error) {
    if (error is ApiException) {
      return switch (error.kind) {
        ApiExceptionKind.connection ||
        ApiExceptionKind.timeout => '서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.',
        ApiExceptionKind.problem =>
          error.problem?.detail ?? '명령을 발행하지 못했습니다.',
        ApiExceptionKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
        _ => '명령을 보내는 중 오류가 발생했습니다.',
      };
    }
    return '명령을 보내는 중 오류가 발생했습니다.';
  }
}
