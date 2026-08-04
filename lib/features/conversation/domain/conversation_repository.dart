import 'package:potner_app/features/conversation/domain/conversation_models.dart';

abstract class ConversationRepository {
  /// 로봇과 나눈 대화를 오래된 것부터 한 페이지 돌려준다.
  ///
  /// [beforeSeq] 를 주면 그 seq 보다 오래된 것만 본다 — 위로 스크롤해 이전 대화를 받을 때
  /// 응답의 `nextBeforeSeq` 를 그대로 넘긴다. 페이지 번호가 아니라 커서인 이유는 대화가 뒤에
  /// 계속 붙기 때문이다. 번호로 넘기면 스크롤하는 동안 새 발화가 들어와 같은 발화가 두 번
  /// 보이거나 빠진다.
  ///
  /// 대화가 없으면 빈 페이지다. 오류가 아니라 첫 대화 전이다.
  Future<ConversationMessagePage> getMessages({
    required String plantId,
    int? beforeSeq,
    int? size,
  });
}
