/// 내 식물 목록의 한 항목이다.
class MyPlant {
  const MyPlant({
    required this.plantId,
    required this.name,
    required this.speciesName,
    required this.categoryName,
    required this.lifeStageName,
    this.adoptedDate,
    this.thumbnailUrl,
  });

  final String plantId;
  final String name;
  final String speciesName;
  final String categoryName;
  final String lifeStageName;

  /// 데려온 날짜를 모르면 null 이다. 등록할 때 필수가 아니다.
  final DateTime? adoptedDate;

  /// 대표 사진 썸네일. 지정하지 않았으면 null 이라 앱이 기본 이미지를 고른다.
  final String? thumbnailUrl;
}
