import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/conversation/data/conversation_api.dart';
import 'package:potner_app/features/conversation/domain/conversation_models.dart';
import 'package:potner_app/features/conversation/domain/conversation_repository.dart';

final conversationRepositoryProvider = Provider<ConversationRepository>((ref) {
  return ConversationRepositoryImpl(
    ConversationApi(ref.watch(apiClientProvider).dio),
  );
});

class ConversationRepositoryImpl implements ConversationRepository {
  ConversationRepositoryImpl(this._api);

  final ConversationApi _api;

  @override
  Future<ConversationMessagePage> getMessages({
    required String plantId,
    int? beforeSeq,
    int? size,
  }) {
    return _api.getMessages(
      plantId: plantId,
      beforeSeq: beforeSeq,
      size: size,
    );
  }
}
