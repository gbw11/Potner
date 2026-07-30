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
