/// 발화한 쪽이다.
///
/// 서버가 소문자로 내려준다 — 젯슨이 그대로 LLM 메시지 목록에 넣는 형식이고 그건 LLM API
/// 규약이라 바꿀 수 없다.
enum ConversationRole {
  /// 사용자가 말한 것. 음성이면 STT 결과다.
  user,

  /// 로봇(초록이)이 답한 것.
  assistant,

  /// 서버가 역할을 늘려도 앱이 죽지 않게 하는 자리다. 말풍선은 로봇 쪽으로 그린다.
  unknown;

  bool get isMine => this == ConversationRole.user;
}

/// 발화 한 줄이다.
///
/// [createdAt] 은 **UTC** 다. 화면에 쓸 때 `toLocal()` 을 거쳐야 한다 — 그냥 쓰면 날짜 구분선이
/// 9시간 밀려 자정 근처 대화가 전날로 묶인다.
class ConversationMessage {
  const ConversationMessage({
    required this.seq,
    required this.role,
    required this.content,
    required this.createdAt,
  });

  /// 갈래 안에서 0 부터 늘어나는 순서다. 이전 대화를 더 받을 때 커서로도 쓴다.
  final int seq;
  final ConversationRole role;
  final String content;
  final DateTime createdAt;
}

/// 발화 한 페이지다.
///
/// [messages] 는 서버가 **오래된 것부터** 담아 준다.
///
/// [nextBeforeSeq] 는 이 페이지보다 더 오래된 것을 받을 때 그대로 넘길 커서다. [hasMore] 가
/// false 면 더 없다.
class ConversationMessagePage {
  const ConversationMessagePage({
    required this.messages,
    this.conversationId,
    this.nextBeforeSeq,
    this.hasMore = false,
  });

  final List<ConversationMessage> messages;

  /// 아직 대화가 없으면 null 이다. 오류가 아니라 첫 대화 전이다.
  final String? conversationId;
  final int? nextBeforeSeq;
  final bool hasMore;
}
