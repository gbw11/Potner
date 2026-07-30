import 'package:potner_app/features/diary/domain/diary_models.dart';

/// 성장 일기는 서버가 매일 자동으로 쓰므로 조회만 있다.
abstract class DiaryRepository {
  Future<List<DiarySummary>> getDiaries({
    required String plantId,
    required DateTime from,
    required DateTime to,
  });

  Future<DiaryDetail> getDiary({
    required String plantId,
    required String diaryId,
  });
}
