import 'package:potner_app/features/photo/domain/photo_models.dart';
import 'package:potner_app/features/photo/domain/photo_repository.dart';

class FakePhotoRepository implements PhotoRepository {
  FakePhotoRepository({List<PlantPhoto>? photos})
    : photos = photos ?? _defaultPhotos();

  final List<PlantPhoto> photos;
  final List<({String plantId, String photoId})> representativeCalls = [];
  final List<({String plantId, String filePath, String fileName})> uploadCalls =
      [];

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
      ),
      PlantPhoto(
        photoId: 'photo-new',
        photoDate: DateTime(today.year, today.month, today.day),
        thumbnailUrl: 'https://images.test/new-thumb.jpg',
        originalUrl: 'https://images.test/new.jpg',
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
}
