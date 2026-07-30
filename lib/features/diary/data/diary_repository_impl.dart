import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/diary/data/diary_api.dart';
import 'package:potner_app/features/diary/domain/diary_models.dart';
import 'package:potner_app/features/diary/domain/diary_repository.dart';

final diaryRepositoryProvider = Provider<DiaryRepository>((ref) {
  return DiaryRepositoryImpl(DiaryApi(ref.watch(apiClientProvider).dio));
});

class DiaryRepositoryImpl implements DiaryRepository {
  DiaryRepositoryImpl(this._api);

  final DiaryApi _api;

  @override
  Future<List<DiarySummary>> getDiaries({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) {
    return _api.getDiaries(plantId: plantId, from: from, to: to);
  }

  @override
  Future<DiaryDetail> getDiary({
    required String plantId,
    required String diaryId,
  }) {
    return _api.getDiary(plantId: plantId, diaryId: diaryId);
  }

  @override
  Future<DailyStatusReport> getStatusReport({
    required String plantId,
    required DateTime date,
  }) {
    return _api.getStatusReport(plantId: plantId, date: date);
  }
}
