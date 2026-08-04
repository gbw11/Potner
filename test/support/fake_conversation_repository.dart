import 'package:potner_app/features/conversation/domain/conversation_models.dart';
import 'package:potner_app/features/conversation/domain/conversation_repository.dart';

class FakeConversationRepository implements ConversationRepository {
  final List<({String plantId, int? beforeSeq, int? size})> calls = [];

  /// 커서별 응답을 미리 심는다. 키가 null 이면 첫 페이지다.
  final Map<int?, ConversationMessagePage> pages = {};

  Object? error;

  /// 응답을 늦춘다. 이전 대화를 받는 중 화면 상태를 보려면 겹치는 순간이 필요하다.
  Duration? delay;

  @override
  Future<ConversationMessagePage> getMessages({
    required String plantId,
    int? beforeSeq,
    int? size,
  }) async {
    calls.add((plantId: plantId, beforeSeq: beforeSeq, size: size));
    final wait = delay;
    if (wait != null) {
      await Future<void>.delayed(wait);
    }
    final failure = error;
    if (failure != null) {
      throw failure;
    }
    return pages[beforeSeq] ?? const ConversationMessagePage(messages: []);
  }
}

/// 시간순(오래된 것부터) 발화를 만든다. 서버가 주는 순서와 같다.
List<ConversationMessage> conversationMessages(
  List<({int seq, bool mine, String content, DateTime utc})> rows,
) {
  return rows
      .map(
        (row) => ConversationMessage(
          seq: row.seq,
          role: row.mine ? ConversationRole.user : ConversationRole.assistant,
          content: row.content,
          createdAt: row.utc,
        ),
      )
      .toList(growable: false);
}
