import 'package:potner_app/features/photo/domain/photo_models.dart';
import 'package:potner_app/features/photo/domain/photo_repository.dart';

class FakePhotoRepository implements PhotoRepository {
  FakePhotoRepository({List<PlantPhoto>? photos})
    : photos = photos ?? _defaultPhotos();

  final List<PlantPhoto> photos;
  final List<({String plantId, String photoId})> representativeCalls = [];
  final List<({String plantId, String filePath, String fileName})> uploadCalls =
      [];
  final List<String> clearCalls = [];
  final List<({String plantId, String photoId})> deleteCalls = [];

  static List<PlantPhoto> _defaultPhotos() {
    final today = DateTime.now();
    return [
      PlantPhoto(
        photoId: 'photo-old',
        photoDate: DateTime(
          today.year,
          today.month,
          today.day,
        ).subtract(const Duration(days: 3)),
        thumbnailUrl: 'https://images.test/old-thumb.jpg',
        originalUrl: 'https://images.test/old.jpg',
        // 타임랩스는 재생용 축소본을 쓴다. 원본은 한 장에 수 MB 라 연속 재생에 못 쓴다.
        playbackUrl: 'https://images.test/old-play.jpg',
      ),
      PlantPhoto(
        photoId: 'photo-new',
        photoDate: DateTime(today.year, today.month, today.day),
        thumbnailUrl: 'https://images.test/new-thumb.jpg',
        originalUrl: 'https://images.test/new.jpg',
        playbackUrl: 'https://images.test/new-play.jpg',
      ),
    ];
  }

  @override
  Future<List<PlantPhoto>> getPhotos({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) async {
    return photos
        .where(
          (photo) =>
              !photo.photoDate.isBefore(from) && !photo.photoDate.isAfter(to),
        )
        .toList(growable: false);
  }

  @override
  Future<void> uploadRepresentativePhoto({
    required String plantId,
    required String filePath,
    required String fileName,
  }) async {
    uploadCalls.add((plantId: plantId, filePath: filePath, fileName: fileName));
  }

  @override
  Future<void> selectRepresentativePhoto({
    required String plantId,
    required String photoId,
  }) async {
    representativeCalls.add((plantId: plantId, photoId: photoId));
  }

  @override
  Future<void> clearRepresentativePhoto({required String plantId}) async {
    clearCalls.add(plantId);
  }

  @override
  Future<void> deletePhoto({
    required String plantId,
    required String photoId,
  }) async {
    deleteCalls.add((plantId: plantId, photoId: photoId));
    // 서버가 지운 뒤 목록을 다시 부르면 빠져 있어야 한다. 화면이 재조회로 갱신되므로
    // 여기서도 실제로 빼 준다.
    photos.removeWhere((photo) => photo.photoId == photoId);
  }
}
