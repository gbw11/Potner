import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_client.dart';
import 'package:potner_app/features/photo/data/photo_api.dart';
import 'package:potner_app/features/photo/domain/photo_models.dart';
import 'package:potner_app/features/photo/domain/photo_repository.dart';

final photoRepositoryProvider = Provider<PhotoRepository>((ref) {
  return PhotoRepositoryImpl(PhotoApi(ref.watch(apiClientProvider).dio));
});

class PhotoRepositoryImpl implements PhotoRepository {
  PhotoRepositoryImpl(this._api);

  final PhotoApi _api;

  @override
  Future<void> uploadRepresentativePhoto({
    required String plantId,
    required String filePath,
    required String fileName,
  }) {
    return _api.uploadRepresentativePhoto(
      plantId: plantId,
      filePath: filePath,
      fileName: fileName,
    );
  }

  @override
  Future<List<PlantPhoto>> getPhotos({
    required String plantId,
    required DateTime from,
    required DateTime to,
  }) {
    return _api.getPhotos(plantId: plantId, from: from, to: to);
  }

  @override
  Future<void> selectRepresentativePhoto({
    required String plantId,
    required String photoId,
  }) {
    return _api.selectRepresentativePhoto(plantId: plantId, photoId: photoId);
  }

  @override
  Future<void> clearRepresentativePhoto({required String plantId}) {
    return _api.clearRepresentativePhoto(plantId: plantId);
  }

  @override
  Future<void> deletePhoto({
    required String plantId,
    required String photoId,
  }) {
    return _api.deletePhoto(plantId: plantId, photoId: photoId);
  }
}
