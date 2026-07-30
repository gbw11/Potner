import 'package:potner_app/features/diary/domain/diary_models.dart';
import 'package:potner_app/features/diary/domain/diary_repository.dart';

class FakeDiaryRepository implements DiaryRepository {
  FakeDiaryRepository({List<DiarySummary>? diaries, Map<String, DiaryDetail>? details})
    : diaries = diaries ?? _defaultDiaries(),
      details = details ?? _defaultDetails();

  final List<DiarySummary> diaries;
  final Map<String, DiaryDetail> details;

  static List<DiarySummary> _defaultDiaries() {
    final today = DateTime.now();
    return [
      DiarySummary(
        diaryId: 'diary-today',
        diaryDate: DateTime(today.year, today.month, today.day),
        title: '나의 첫 번째 꽃망울',
      ),
    ];
  }

  static Map<String, DiaryDetail> _defaultDetails() {
    final today = DateTime.now();
    return {
      'diary-today': DiaryDetail(
        diaryId: 'diary-today',
        diaryDate: DateTime(today.year, today.month, today.day),
        title: '나의 첫 번째 꽃망울',
        content: '오늘은 정말 특별한 날이다. 줄기 끝에 작은 꽃망울이 맺혔다.',
      ),
    };
  }

  @override
  Future<List<DiarySummary>> getDiaries({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) async {
    return diaries
        .where(
          (diary) =>
              !diary.diaryDate.isBefore(from) && !diary.diaryDate.isAfter(to),
        )
        .toList(growable: false);
  }

  @override
  Future<DiaryDetail> getDiary({
    required String plantId,
    required String diaryId,
  }) async {
    final detail = details[diaryId];
    if (detail == null) {
      throw StateError('Missing fake diary $diaryId');
    }
    return detail;
  }

  @override
  Future<DailyStatusReport> getStatusReport({
    required String plantId,
    required DateTime date,
  }) async {
    return DailyStatusReport(
      date: date,
      happinessScore: 95,
      lightHours: 6.5,
      adjustments: const [
        ScoreAdjustment(reason: 'BLOOMED', points: 5),
        ScoreAdjustment(reason: 'HUMIDITY_ALERT', points: -10),
      ],
    );
  }
}
