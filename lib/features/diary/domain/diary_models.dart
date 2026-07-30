/// 일기 목록의 한 줄이다. 본문은 상세 조회에서만 내려온다.
class DiarySummary {
  const DiarySummary({
    required this.diaryId,
    required this.diaryDate,
    required this.title,
    this.thumbnailUrl,
  });

  final String diaryId;
  final DateTime diaryDate;
  final String title;

  /// 그날 장치가 찍은 사진의 썸네일이다. 촬영이 없던 날은 null 이다.
  final String? thumbnailUrl;
}

/// 하루를 돌아보는 상태 리포트다. 일기 상세 화면 하단 영역에 쓴다.
class DailyStatusReport {
  const DailyStatusReport({
    required this.date,
    required this.adjustments,
    this.happinessScore,
    this.lightHours,
    this.wateredMl,
  });

  final DateTime date;

  /// 0~100. 그날 측정값이 아예 없어 판정하지 못했으면 null 이다(0점과 다르다).
  final int? happinessScore;

  /// 100점에서 깎이거나 더해진 이유들이다. 점수가 100이면 비어 있다.
  final List<ScoreAdjustment> adjustments;

  /// 최저 조도 이상을 받은 실측 시간. 광량 집계 전이면 null 이다.
  final double? lightHours;

  /// 그날 급수량. 급수 기록 기능이 붙기 전까지 항상 null 이다.
  final double? wateredMl;
}

class ScoreAdjustment {
  const ScoreAdjustment({required this.reason, required this.points});

  final String reason;
  final int points;
}

class DiaryDetail {
  const DiaryDetail({
    required this.diaryId,
    required this.diaryDate,
    required this.title,
    required this.content,
    this.photoUrl,
  });

  final String diaryId;
  final DateTime diaryDate;
  final String title;
  final String content;
  final String? photoUrl;
}
