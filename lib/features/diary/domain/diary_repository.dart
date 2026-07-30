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

  /// 그날 하루의 행복 점수·일조·급수량이다. 일기와 별개로 항상 존재한다.
  Future<DailyStatusReport> getStatusReport({
    required String plantId,
    required DateTime date,
  });
}
