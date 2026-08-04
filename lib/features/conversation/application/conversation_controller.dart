import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/conversation/data/conversation_repository_impl.dart';
import 'package:potner_app/features/conversation/domain/conversation_models.dart';
import 'package:potner_app/features/conversation/domain/conversation_repository.dart';

final conversationControllerProvider =
    NotifierProvider<ConversationController, ConversationState>(
      ConversationController.new,
    );

enum ConversationPhase { loading, ready, failure }

class ConversationState {
  const ConversationState({
    this.phase = ConversationPhase.loading,
    this.messages = const [],
    this.hasMore = false,
    this.isLoadingOlder = false,
    this.message,
  });

  final ConversationPhase phase;

  /// **최신이 먼저다.** 채팅 화면은 `reverse: true` 목록으로 그리므로 index 0 이 화면 맨 아래다.
  final List<ConversationMessage> messages;

  final bool hasMore;

  /// 위쪽에 이전 대화를 더 받는 중이다.
  final bool isLoadingOlder;

  /// 첫 로딩 실패 문구다. 이전 대화 추가 실패에는 쓰지 않는다 — 이미 보이는 대화를 지우면 안 된다.
  final String? message;

  bool get isEmpty => phase == ConversationPhase.ready && messages.isEmpty;

  ConversationState copyWith({
    ConversationPhase? phase,
    List<ConversationMessage>? messages,
    bool? hasMore,
    bool? isLoadingOlder,
    String? message,
  }) {
    return ConversationState(
      phase: phase ?? this.phase,
      messages: messages ?? this.messages,
      hasMore: hasMore ?? this.hasMore,
      isLoadingOlder: isLoadingOlder ?? this.isLoadingOlder,
      message: message ?? this.message,
    );
  }
}

/// 로봇과 나눈 대화를 커서로 거슬러 올라가며 읽는 컨트롤러다.
///
/// 페이지 번호를 쓰지 않는다. 대화는 뒤에 계속 붙으므로 번호로 넘기면 스크롤하는 동안 새 발화가
/// 들어와 같은 발화가 두 번 보이거나 빠진다. 서버가 주는 `nextBeforeSeq` 를 그대로 다음 요청에
/// 넘긴다.
///
/// `plantId` 를 생성자가 아니라 [load] 로 받는다. Riverpod 3 에는 family Notifier 의 인자를
/// 클래스가 직접 읽는 자리가 없고, 이 레포의 다른 컨트롤러들(`RobotDriveController`,
/// `DeviceCommandDebugController`)도 같은 방식이다.
class ConversationController extends Notifier<ConversationState> {
  late ConversationRepository _repository;

  String? _plantId;

  /// 다음에 받을 더 오래된 페이지의 커서다. null 이면 끝이다.
  int? _beforeSeq;

  /// 중복 요청을 막는다. 스크롤 리스너는 한 번의 제스처에도 여러 번 불린다.
  bool _inFlight = false;

  @override
  ConversationState build() {
    _repository = ref.read(conversationRepositoryProvider);
    return const ConversationState();
  }

  /// 화면에 들어올 때 부른다. 다른 식물로 바뀌면 앞선 대화를 버리고 처음부터 받는다.
  Future<void> load(String plantId) async {
    if (_inFlight) {
      return;
    }
    _plantId = plantId;
    _inFlight = true;
    state = const ConversationState();
    try {
      final page = await _repository.getMessages(plantId: plantId);
      state = ConversationState(
        phase: ConversationPhase.ready,
        // 서버는 오래된 것부터 준다. reverse 목록이므로 최신이 먼저여야 한다.
        messages: page.messages.reversed.toList(growable: false),
        hasMore: page.hasMore,
      );
      _beforeSeq = page.nextBeforeSeq;
    } catch (error) {
      state = ConversationState(
        phase: ConversationPhase.failure,
        message: _messageFor(error),
      );
    } finally {
      _inFlight = false;
    }
  }

  /// 위로 스크롤해 이전 대화를 더 받는다.
  ///
  /// 실패해도 이미 보이는 대화를 지우지 않는다 — 스크롤 한 번이 화면을 날리면 안 된다. 다시
  /// 올리면 재시도된다.
  Future<void> loadOlder() async {
    final plantId = _plantId;
    final cursor = _beforeSeq;
    if (_inFlight || !state.hasMore || plantId == null || cursor == null) {
      return;
    }
    _inFlight = true;
    state = state.copyWith(isLoadingOlder: true);
    try {
      final page = await _repository.getMessages(
        plantId: plantId,
        beforeSeq: cursor,
      );
      state = state.copyWith(
        // 더 오래된 것은 reverse 목록의 뒤에 붙는다.
        messages: [...state.messages, ...page.messages.reversed],
        hasMore: page.hasMore,
        isLoadingOlder: false,
      );
      _beforeSeq = page.nextBeforeSeq;
    } catch (_) {
      state = state.copyWith(isLoadingOlder: false);
    } finally {
      _inFlight = false;
    }
  }

  String _messageFor(Object error) {
    if (error is ApiException) {
      return switch (error.kind) {
        ApiExceptionKind.connection ||
        ApiExceptionKind.timeout => '서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.',
        ApiExceptionKind.problem =>
          error.problem?.detail ?? '대화를 불러오지 못했습니다.',
        ApiExceptionKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
        _ => '대화를 불러오는 중 오류가 발생했습니다.',
      };
    }
    return '대화를 불러오는 중 오류가 발생했습니다.';
  }
}
