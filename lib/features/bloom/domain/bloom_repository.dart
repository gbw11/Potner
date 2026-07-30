import 'package:potner_app/features/bloom/domain/bloom_models.dart';

abstract class BloomRepository {
  /// 내 개화 기록을 최신순으로 돌려준다.
  Future<List<BloomRecord>> getBlooms();

  /// 개화를 기록한다. [bloomDate] 를 비우면 서버가 오늘로 채운다.
  Future<BloomRecord> recordBloom({
    required String plantId,
    DateTime? bloomDate,
    String? note,
  });

  Future<void> deleteBloom({required String plantId, required String bloomId});

  /// 개화 알림을 읽음 처리한다. 이미 읽은 기록은 처음 확인 시각이 유지된다.
  Future<void> markRead(String bloomId);
}
