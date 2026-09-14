/// 성장 사진 한 장이다. URL 세 개는 용도가 다르다:
/// thumbnailUrl 은 그리드, playbackUrl 은 타임랩스, originalUrl 은 상세·비교용이다.
class PlantPhoto {
  const PlantPhoto({
    required this.photoId,
    required this.photoDate,
    required this.thumbnailUrl,
    required this.originalUrl,
    this.playbackUrl,
    this.capturedAt,
  });

  final String photoId;
  final DateTime photoDate;
  final String thumbnailUrl;
  final String originalUrl;
  final String? playbackUrl;
  final DateTime? capturedAt;
}
