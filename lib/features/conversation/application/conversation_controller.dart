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
    this.isJumping = false,
    this.jumpTargetSeq,
    this.jumpNotice,
  });

  final ConversationPhase phase;

  /// **최신이 먼저다.** 채팅 화면은 `reverse: true` 목록으로 그리므로 index 0 이 화면 맨 아래다.
  final List<ConversationMessage> messages;

  final bool hasMore;

  /// 위쪽에 이전 대화를 더 받는 중이다.
  final bool isLoadingOlder;

  /// 첫 로딩 실패 문구다. 이전 대화 추가 실패에는 쓰지 않는다 — 이미 보이는 대화를 지우면 안 된다.
  final String? message;

  /// 고른 날짜까지 거슬러 받는 중이다. 여러 페이지를 이어 받으므로 진행 표시가 필요하다.
  final bool isJumping;

  /// 화면을 옮길 대상 발화의 seq 다. 화면이 그 자리로 스크롤한 뒤 비운다.
  final int? jumpTargetSeq;

  /// "그 날은 대화가 없어요" 처럼 이동하지 못한 이유다. 한 번 보여주고 비운다.
  final String? jumpNotice;

  bool get isEmpty => phase == ConversationPhase.ready && messages.isEmpty;

  /// 화면에 담긴 가장 오래된 발화의 로컬 날짜다. 날짜 선택기의 하한으로 쓴다.
  DateTime? get oldestLocalDate => messages.isEmpty
      ? null
      : _localDate(messages.last.createdAt);

  DateTime? get newestLocalDate => messages.isEmpty
      ? null
      : _localDate(messages.first.createdAt);

  static DateTime _localDate(DateTime utc) {
    final local = utc.toLocal();
    return DateTime(local.year, local.month, local.day);
  }

  ConversationState copyWith({
    ConversationPhase? phase,
    List<ConversationMessage>? messages,
    bool? hasMore,
    bool? isLoadingOlder,
    String? message,
    bool? isJumping,
    // 이동 대상과 안내는 한 번 쓰고 비워야 하므로 null 로 지울 수 있어야 한다. 다른 필드처럼
    // `?? this.x` 로 두면 null 을 넘겨도 옛 값이 살아남아 같은 자리로 계속 스크롤한다.
    bool clearJumpTarget = false,
    int? jumpTargetSeq,
    bool clearJumpNotice = false,
    String? jumpNotice,
  }) {
    return ConversationState(
      phase: phase ?? this.phase,
      messages: messages ?? this.messages,
      hasMore: hasMore ?? this.hasMore,
      isLoadingOlder: isLoadingOlder ?? this.isLoadingOlder,
      message: message ?? this.message,
      isJumping: isJumping ?? this.isJumping,
      jumpTargetSeq: clearJumpTarget
          ? null
          : (jumpTargetSeq ?? this.jumpTargetSeq),
      jumpNotice: clearJumpNotice ? null : (jumpNotice ?? this.jumpNotice),
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

  /// 고른 날짜의 첫 발화로 옮긴다.
  ///
  /// 그 날짜가 아직 안 받아진 상태면 닿을 때까지 이전 페이지를 이어 받는다. 서버에 "그 날로
  /// 건너뛰기" 가 없어서인데, 거슬러 받는 동안 지나온 날짜의 발화가 모두 메모리에 남으므로
  /// 도착 뒤 아래로 스크롤하면 그 사이 날짜가 그대로 이어진다 — 사용자가 기대하는 움직임이
  /// 추가 요청 없이 나온다.
  ///
  /// [maxPages] 는 안전장치다. 서버가 `hasMore` 를 잘못 주거나 커서가 제자리를 돌면 무한히
  /// 요청하게 된다.
  Future<void> jumpToDate(DateTime localDate, {int maxPages = 40}) async {
    final target = DateTime(localDate.year, localDate.month, localDate.day);
    state = state.copyWith(clearJumpNotice: true, clearJumpTarget: true);

    var pages = 0;
    while (_firstSeqOn(target) == null &&
        state.hasMore &&
        pages < maxPages) {
      // 이미 받은 것보다 최신 날짜를 골랐다면 더 받아도 나오지 않는다.
      final oldest = state.oldestLocalDate;
      if (oldest != null && oldest.isBefore(target)) {
        break;
      }
      state = state.copyWith(isJumping: true);
      final before = state.messages.length;
      await loadOlder();
      pages++;
      // 더 받았는데 늘지 않으면 커서가 제자리다. 여기서 끊지 않으면 maxPages 까지 헛돈다.
      if (state.messages.length == before) {
        break;
      }
    }

    final seq = _firstSeqOn(target);
    if (seq == null) {
      state = state.copyWith(
        isJumping: false,
        jumpNotice: _noticeFor(target),
        clearJumpTarget: true,
      );
      return;
    }
    state = state.copyWith(
      isJumping: false,
      jumpTargetSeq: seq,
      clearJumpNotice: true,
    );
  }

  /// 화면이 이동을 마친 뒤 부른다. 비우지 않으면 목록이 다시 그려질 때마다 같은 자리로 끌려간다.
  void clearJumpTarget() {
    if (state.jumpTargetSeq != null) {
      state = state.copyWith(clearJumpTarget: true);
    }
  }

  void clearJumpNotice() {
    if (state.jumpNotice != null) {
      state = state.copyWith(clearJumpNotice: true);
    }
  }

  /// 그 날짜의 **첫** 발화 seq 다. messages 는 최신순이므로 뒤에서부터 찾으면 그 날의 처음이다.
  int? _firstSeqOn(DateTime target) {
    int? found;
    for (final message in state.messages) {
      final local = message.createdAt.toLocal();
      if (local.year == target.year &&
          local.month == target.month &&
          local.day == target.day) {
        found = message.seq;
      }
    }
    return found;
  }

  String _noticeFor(DateTime target) {
    final label = '${target.month}월 ${target.day}일';
    final oldest = state.oldestLocalDate;
    if (oldest != null && target.isBefore(oldest) && !state.hasMore) {
      return '$label 에는 대화가 없어요. 가장 오래된 대화는 ${oldest.month}월 ${oldest.day}일이에요.';
    }
    return '$label 에는 나눈 대화가 없어요.';
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
