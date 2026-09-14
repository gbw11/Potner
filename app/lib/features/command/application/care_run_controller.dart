import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/command/data/command_repository_impl.dart';
import 'package:potner_app/features/command/domain/command_models.dart';
import 'package:potner_app/features/command/domain/command_repository.dart';
import 'package:potner_app/features/device/domain/device_models.dart';

final careRunControllerProvider =
    NotifierProvider<CareRunController, CareRunState>(CareRunController.new);

enum CareRunPhase { idle, starting, running, success, failure }

class CareRunState {
  const CareRunState({
    this.phase = CareRunPhase.idle,
    this.purpose,
    this.steps = const [],
    this.message = '버튼을 누르면 자동 케어 한 회차가 지금 시작돼요.',
  });

  final CareRunPhase phase;
  final CareRunPurpose? purpose;

  /// 이 회차에서 지금까지 이력에 나타난 단계들이다. 발행 순이며 서버가 잇는 대로 늘어난다.
  final List<DeviceCommandRecord> steps;

  final String message;

  bool get isBusy =>
      phase == CareRunPhase.starting || phase == CareRunPhase.running;
}

/// 자동 케어 한 회차를 시작하고 회차가 끝날 때까지 지켜보는 컨트롤러다.
///
/// `DeviceCommandDebugController` 와 기다리는 대상이 다르다. 수동 명령은 requestId 하나가
/// 종결되면 그것으로 끝이지만, 회차는 첫 이동이 `OK` 가 된 뒤에도 급수 → 송풍 → 복귀가
/// 이어진다. 첫 명령만 보고 끝내면 "도착했어요" 에서 멈춰 정작 케어가 도는 동안을 못 보여준다.
///
/// **서버는 회차에 식별자를 주지 않는다.** 앱이 아는 것은 첫 명령의 requestId 하나뿐이라,
/// 그 뒤에 발행된 자동 명령을 같은 회차로 본다. 회차가 도는 동안 다른 자동 케어가 끼어들지
/// 못하게 서버가 막으므로(`CARE_RUN_ROBOT_BUSY`) 이 추정이 어긋나는 창은 좁다. 회차를 구분해야
/// 할 만큼 넓어지면 서버가 명령에 회차 식별자를 실어 줘야 한다.
class CareRunController extends Notifier<CareRunState> {
  static const _pollInterval = Duration(seconds: 2);

  /// 회차 하나가 명령 여러 개다. 서버의 명령 타임아웃이 기본 120초이므로 네 단계짜리 급수
  /// 회차는 최악의 경우 8분이 걸린다. 단건을 기다리는 `DeviceCommandDebugController` 보다
  /// 넉넉해야 앱이 먼저 포기해 원인이 장치인지 앱인지 흐려지지 않는다.
  static const _maxPollAttempts = 330;

  late DeviceCommandRepository _repository;
  int _pollGeneration = 0;

  @override
  CareRunState build() {
    _repository = ref.read(deviceCommandRepositoryProvider);
    ref.onDispose(() => _pollGeneration++);
    return const CareRunState();
  }

  Future<void> start({
    required String plantId,
    required CareRunPurpose purpose,
  }) async {
    if (state.isBusy) {
      return;
    }
    final generation = ++_pollGeneration;
    state = CareRunState(
      phase: CareRunPhase.starting,
      purpose: purpose,
      message: '${purpose.label} 한 회차를 시작하는 중이에요.',
    );

    final DeviceCommandRecord first;
    try {
      first = await _repository.startCareRun(
        plantId: plantId,
        purpose: purpose,
      );
    } catch (error) {
      if (generation != _pollGeneration) {
        return;
      }
      state = CareRunState(
        phase: CareRunPhase.failure,
        purpose: purpose,
        message: _messageFor(error),
      );
      return;
    }

    if (generation != _pollGeneration) {
      return;
    }

    if (first.issuedAt == null) {
      // 발행 시각이 없으면 뒤따르는 자동 명령을 이 회차의 것으로 묶을 방법이 없다. 회차는
      // 이미 시작됐으므로 실패로 말하지 않고, 지켜보는 일만 사람에게 넘긴다.
      state = CareRunState(
        phase: CareRunPhase.success,
        purpose: purpose,
        steps: [first],
        message: '${purpose.label} 회차를 시작했어요. 진행은 명령 이력에서 확인해 주세요.',
      );
      return;
    }

    state = CareRunState(
      phase: CareRunPhase.running,
      purpose: purpose,
      steps: [first],
      message: '${first.stepLabel} 단계를 시작했어요.',
    );
    unawaited(_poll(plantId, purpose, first, generation));
  }

  Future<void> _poll(
    String plantId,
    CareRunPurpose purpose,
    DeviceCommandRecord first,
    int generation,
  ) async {
    for (var attempt = 0; attempt < _maxPollAttempts; attempt++) {
      await Future<void>.delayed(_pollInterval);
      if (generation != _pollGeneration) {
        return;
      }

      final List<DeviceCommandRecord> commands;
      try {
        commands = await _findCommands(plantId);
      } catch (error) {
        if (generation != _pollGeneration) {
          return;
        }
        state = CareRunState(
          phase: CareRunPhase.failure,
          purpose: purpose,
          steps: state.steps,
          message: _messageFor(error),
        );
        return;
      }
      if (generation != _pollGeneration) {
        return;
      }

      final steps = _chainSteps(commands, first);
      if (steps.isEmpty) {
        continue;
      }

      // 건너뛴 급수(skipped)는 실패가 아니다. 회차는 송풍·복귀까지 이어진다.
      final failed = steps
          .where((step) => step.status.isTerminal && !step.status.continuesChain)
          .firstOrNull;
      if (failed != null) {
        state = CareRunState(
          phase: CareRunPhase.failure,
          purpose: purpose,
          steps: steps,
          message: _failureMessage(failed),
        );
        return;
      }

      final finished = _finished(purpose, steps);
      state = CareRunState(
        phase: finished ? CareRunPhase.success : CareRunPhase.running,
        purpose: purpose,
        steps: steps,
        message: finished
            ? _successMessage(purpose, steps)
            : _progressMessage(steps),
      );
      if (finished) {
        return;
      }
    }

    if (generation == _pollGeneration) {
      state = CareRunState(
        phase: CareRunPhase.failure,
        purpose: purpose,
        steps: state.steps,
        message: '회차가 끝나기를 기다리는 시간이 지났어요. 로봇이 어디에 서 있는지와 서버 로그를 확인해 주세요.',
      );
    }
  }

  Future<List<DeviceCommandRecord>> _findCommands(String plantId) {
    final now = DateTime.now();
    // 자정을 막 넘겨 시작한 회차가 조회 범위에서 빠지지 않도록 어제까지 함께 본다.
    return _repository.getCommands(
      plantId: plantId,
      from: now.subtract(const Duration(days: 1)),
      to: now,
    );
  }

  /// 이 회차의 단계들을 발행 순으로 추린다.
  ///
  /// 첫 명령은 requestId 로 정확히 집고, 나머지는 그 시각 이후에 발행된 자동 명령으로 본다.
  /// 사용자가 그 사이에 수동 명령을 보내도 `initiator` 가 달라 섞이지 않는다.
  List<DeviceCommandRecord> _chainSteps(
    List<DeviceCommandRecord> commands,
    DeviceCommandRecord first,
  ) {
    final startedAt = first.issuedAt;
    if (startedAt == null) {
      return const [];
    }
    final steps = commands.where((command) {
      if (command.requestId == first.requestId) {
        return true;
      }
      if (command.initiator != CommandInitiator.auto) {
        return false;
      }
      final issuedAt = command.issuedAt;
      return issuedAt != null && !issuedAt.isBefore(startedAt);
    }).toList();
    // 이력은 최신순으로 온다. 회차는 시간 순으로 읽어야 단계가 이어지는 것이 보인다.
    steps.sort((a, b) {
      final left = a.issuedAt ?? startedAt;
      final right = b.issuedAt ?? startedAt;
      return left.compareTo(right);
    });
    return steps;
  }

  /// 회차가 끝났는지 본다.
  ///
  /// 단계 수를 세지 않고 **끝나는 모양**만 본다. 급수·환기·촬영은 대기 장소 복귀로 끝나고,
  /// 재배치는 이어지는 단계가 없는 단발 이동이다. 서버가 체인 중간에 단계를 하나 더 넣어도
  /// 이 판단은 그대로 맞는다.
  bool _finished(CareRunPurpose purpose, List<DeviceCommandRecord> steps) {
    if (!purpose.returnsHome) {
      return steps.first.status.isTerminal;
    }
    final last = steps.last;
    return last.type == DeviceCommandType.navigate &&
        last.destination == RobotLocationType.home &&
        last.status == DeviceCommandStatus.ok;
  }

  String _progressMessage(List<DeviceCommandRecord> steps) {
    final last = steps.last;
    if (last.status == DeviceCommandStatus.issued) {
      return '${last.stepLabel} 단계를 하는 중이에요.';
    }
    return '${last.stepLabel} 단계가 끝났어요. 서버가 다음 단계를 잇는 중이에요.';
  }

  String _successMessage(
    CareRunPurpose purpose,
    List<DeviceCommandRecord> steps,
  ) {
    return switch (purpose) {
      CareRunPurpose.watering => _wateringDone(steps),
      CareRunPurpose.drying => '환기 회차를 마쳤어요.${_fanSuffix(steps)}',
      CareRunPurpose.capture => '촬영 회차를 마쳤어요. 포토 로그에 사진이 올라오는지 확인해 주세요.',
      CareRunPurpose.relocation =>
        steps.first.destination == RobotLocationType.home
            ? '로봇을 대기 장소로 되돌렸어요.'
            : '로봇을 햇빛 자리로 옮겼어요. 목표 광량을 채우거나 해가 지면 서버가 데려와요.',
    };
  }

  /// 급수 회차의 결과다.
  ///
  /// 장치가 급수를 건너뛴 회차를 "0ml 나갔다" 고 말하면 펌프가 고장 난 것처럼 읽힌다. 물을
  /// 주지 않은 것과 주려다 못 준 것은 다르므로 문장을 나눈다. 사유(최근 급수량·간격)는 서버가
  /// 저장하지 않아 앱도 말할 수 없다.
  String _wateringDone(List<DeviceCommandRecord> steps) {
    final skipped = steps.any(
      (step) =>
          step.type == DeviceCommandType.water &&
          step.status == DeviceCommandStatus.skipped,
    );
    if (skipped) {
      return '물은 주지 않았어요. 최근에 준 양이 충분해서 장치가 건너뛰었고, 송풍과 복귀는 마쳤어요.';
    }
    return '급수 회차를 마쳤어요. 실제 ${_ml(_dispensedMl(steps))}ml 나갔고 로봇이 대기 장소로 돌아왔어요.';
  }

  String _failureMessage(DeviceCommandRecord failed) {
    return switch (failed.status) {
      DeviceCommandStatus.busy =>
        '${failed.stepLabel} 단계에서 로봇이 다른 작업 중이라 거절했어요. 잠시 뒤 다시 시작하면 됩니다.',
      DeviceCommandStatus.timedOut =>
        '${failed.stepLabel} 단계에서 회신이 없어 서버가 끊었어요. 로봇이 스테이션에 남아 있을 수 있으니 확인해 주세요.',
      DeviceCommandStatus.error =>
        failed.errorMessage ?? '${failed.stepLabel} 단계에서 장치가 실패를 보고했어요.',
      _ => '${failed.stepLabel} 단계에서 알 수 없는 결과를 받았어요.',
    };
  }

  double? _dispensedMl(List<DeviceCommandRecord> steps) {
    return steps
        .where((step) => step.type == DeviceCommandType.water)
        .map((step) => step.dispensedMl)
        .nonNulls
        .firstOrNull;
  }

  /// 송풍 시간은 라즈베리가 자기 설정으로 정하고 회신으로 알려 준다. 서버가 요청한 값이
  /// 아니라 실제로 돈 시간이라 말할 값어치가 있다.
  String _fanSuffix(List<DeviceCommandRecord> steps) {
    final seconds = steps
        .where((step) => step.type == DeviceCommandType.fan)
        .map((step) => step.runSeconds)
        .nonNulls
        .firstOrNull;
    return seconds == null ? '' : ' 팬이 실제로 $seconds초 돌았어요.';
  }

  String _ml(double? value) {
    if (value == null) {
      return '0';
    }
    return value == value.roundToDouble()
        ? value.round().toString()
        : value.toStringAsFixed(1);
  }

  String _messageFor(Object error) {
    if (error is ApiException) {
      return switch (error.kind) {
        ApiExceptionKind.connection ||
        ApiExceptionKind.timeout => '서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.',
        // 시작 거절은 이유가 여러 갈래다(꺼진 케어·작업 중인 로봇·급수량 미설정·좌표 없음).
        // 서버가 그 이유를 문장으로 내려주므로 앱에서 다시 갈래를 만들지 않는다.
        ApiExceptionKind.problem =>
          error.problem?.detail ?? '자동 케어를 시작하지 못했습니다.',
        ApiExceptionKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
        _ => '자동 케어를 시작하는 중 오류가 발생했습니다.',
      };
    }
    return '자동 케어를 시작하는 중 오류가 발생했습니다.';
  }
}
