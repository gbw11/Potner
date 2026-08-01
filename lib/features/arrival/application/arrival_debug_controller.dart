import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/arrival/data/arrival_repository_impl.dart';
import 'package:potner_app/features/arrival/domain/arrival_models.dart';
import 'package:potner_app/features/arrival/domain/arrival_repository.dart';
import 'package:uuid/uuid.dart';

final arrivalDebugControllerProvider =
    NotifierProvider<ArrivalDebugController, ArrivalDebugState>(
      ArrivalDebugController.new,
    );

enum ArrivalDebugPhase { idle, sending, waiting, success, failure }

class ArrivalDebugState {
  const ArrivalDebugState({
    this.phase = ArrivalDebugPhase.idle,
    this.activeVisitId,
    this.lastEventId,
    this.lastEventType,
    this.processingStatus,
    this.message = '아직 테스트 명령을 보내지 않았어요.',
  });

  final ArrivalDebugPhase phase;
  final String? activeVisitId;
  final String? lastEventId;
  final ArrivalEventType? lastEventType;
  final ArrivalProcessingStatus? processingStatus;
  final String message;

  bool get isSending => phase == ArrivalDebugPhase.sending;
  bool get canStart => !isSending && activeVisitId == null;
  bool get canCancel => !isSending && activeVisitId != null;
}

class ArrivalDebugController extends Notifier<ArrivalDebugState> {
  static const _pollInterval = Duration(seconds: 2);
  static const _maxPollAttempts = 61;
  static const _uuid = Uuid();

  late ArrivalRepository _repository;
  int _pollGeneration = 0;

  @override
  ArrivalDebugState build() {
    _repository = ref.read(arrivalRepositoryProvider);
    ref.onDispose(() => _pollGeneration++);
    return const ArrivalDebugState();
  }

  Future<void> startWelcome() async {
    if (!state.canStart) {
      return;
    }
    final visitId = _uuid.v4();
    await _send(ArrivalEventType.approach, visitId);
  }

  Future<void> cancelWelcome() async {
    final visitId = state.activeVisitId;
    if (!state.canCancel || visitId == null) {
      return;
    }
    await _send(ArrivalEventType.cancel, visitId);
  }

  Future<void> _send(ArrivalEventType type, String visitId) async {
    final eventId = _uuid.v4();
    final generation = ++_pollGeneration;
    state = ArrivalDebugState(
      phase: ArrivalDebugPhase.sending,
      activeVisitId: visitId,
      lastEventId: eventId,
      lastEventType: type,
      message: type == ArrivalEventType.approach
          ? '마중 시작 명령을 보내는 중이에요.'
          : '마중 취소 명령을 보내는 중이에요.',
    );

    try {
      final receipt = await _repository.sendEvent(
        ArrivalEventCall(
          eventId: eventId,
          visitId: visitId,
          eventType: type,
          source: ArrivalEventSource.debugButton,
          geofenceId: 'DEBUG_BUTTON',
          occurredAt: DateTime.now(),
        ),
      );
      if (generation != _pollGeneration) {
        return;
      }
      state = ArrivalDebugState(
        phase: ArrivalDebugPhase.waiting,
        activeVisitId: visitId,
        lastEventId: receipt.eventId,
        lastEventType: type,
        processingStatus: ArrivalProcessingStatus.commandPublished,
        message: type == ArrivalEventType.approach
            ? '서버가 명령을 발행했어요. GREETING 도착 응답을 기다립니다.'
            : '서버가 취소 명령을 발행했어요. Jetson 응답을 기다립니다.',
      );
      unawaited(_poll(receipt.eventId, visitId, type, generation));
    } catch (error) {
      if (generation != _pollGeneration) {
        return;
      }
      state = ArrivalDebugState(
        phase: ArrivalDebugPhase.failure,
        activeVisitId: type == ArrivalEventType.approach ? null : visitId,
        lastEventId: eventId,
        lastEventType: type,
        message: _messageFor(error),
      );
    }
  }

  Future<void> _poll(
    String eventId,
    String visitId,
    ArrivalEventType type,
    int generation,
  ) async {
    for (var attempt = 0; attempt < _maxPollAttempts; attempt++) {
      if (generation != _pollGeneration) {
        return;
      }
      try {
        final result = await _repository.getEventStatus(eventId);
        if (generation != _pollGeneration) {
          return;
        }
        if (result.status.isTerminal) {
          _applyTerminal(result, visitId, type);
          return;
        }
      } catch (error) {
        if (generation != _pollGeneration) {
          return;
        }
        state = ArrivalDebugState(
          phase: ArrivalDebugPhase.failure,
          activeVisitId: type == ArrivalEventType.approach ? null : visitId,
          lastEventId: eventId,
          lastEventType: type,
          message: _messageFor(error),
        );
        return;
      }
      await Future<void>.delayed(_pollInterval);
    }

    if (generation == _pollGeneration) {
      state = ArrivalDebugState(
        phase: ArrivalDebugPhase.failure,
        activeVisitId: type == ArrivalEventType.approach ? null : visitId,
        lastEventId: eventId,
        lastEventType: type,
        processingStatus: ArrivalProcessingStatus.timedOut,
        message: 'Jetson 응답 대기 시간이 지났어요. 서버와 Jetson 로그를 확인해 주세요.',
      );
    }
  }

  void _applyTerminal(
    ArrivalEventStatus result,
    String visitId,
    ArrivalEventType type,
  ) {
    final success = result.status == ArrivalProcessingStatus.ok;
    final String? activeVisitId = switch ((type, success)) {
      (ArrivalEventType.approach, true) => visitId,
      (ArrivalEventType.cancel, false) => visitId,
      _ => null,
    };
    state = ArrivalDebugState(
      phase: success ? ArrivalDebugPhase.success : ArrivalDebugPhase.failure,
      activeVisitId: activeVisitId,
      lastEventId: result.eventId,
      lastEventType: type,
      processingStatus: result.status,
      message: switch (result.status) {
        ArrivalProcessingStatus.ok when type == ArrivalEventType.approach =>
          'Jetson이 GREETING 위치 도착을 확인했어요.',
        ArrivalProcessingStatus.ok => 'Jetson이 HOME 위치 복귀를 확인했어요.',
        ArrivalProcessingStatus.busy => '로봇이 다른 작업 중이라 명령을 거절했어요.',
        ArrivalProcessingStatus.timedOut => 'Jetson 응답 시간이 초과됐어요.',
        ArrivalProcessingStatus.error =>
          result.errorMessage ?? 'Jetson이 명령 수행 실패를 보고했어요.',
        _ => '알 수 없는 결과를 받았어요.',
      },
    );
  }

  String _messageFor(Object error) {
    if (error is ApiException) {
      return switch (error.kind) {
        ApiExceptionKind.connection ||
        ApiExceptionKind.timeout => '서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.',
        ApiExceptionKind.problem =>
          error.problem?.detail ?? '귀가 테스트 요청을 처리하지 못했습니다.',
        ApiExceptionKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
        _ => '귀가 테스트 중 오류가 발생했습니다.',
      };
    }
    return '귀가 테스트 중 오류가 발생했습니다.';
  }
}
