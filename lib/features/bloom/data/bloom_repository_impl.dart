import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/bloom/data/bloom_api.dart';
import 'package:potner_app/features/bloom/domain/bloom_models.dart';
import 'package:potner_app/features/bloom/domain/bloom_repository.dart';

final bloomRepositoryProvider = Provider<BloomRepository>((ref) {
  return BloomRepositoryImpl(BloomApi(ref.watch(apiClientProvider).dio));
});

class BloomRepositoryImpl implements BloomRepository {
  BloomRepositoryImpl(this._api);

  final BloomApi _api;

  @override
  Future<List<BloomRecord>> getBlooms() {
    return _api.getBlooms();
  }

  @override
  Future<BloomRecord> recordBloom({
    required String plantId,
    DateTime? bloomDate,
    String? note,
  }) {
    return _api.recordBloom(plantId: plantId, bloomDate: bloomDate, note: note);
  }

  @override
  Future<void> deleteBloom({
    required String plantId,
    required String bloomId,
  }) {
    return _api.deleteBloom(plantId: plantId, bloomId: bloomId);
  }

  @override
  Future<void> markRead(String bloomId) {
    return _api.markRead(bloomId);
  }
}
