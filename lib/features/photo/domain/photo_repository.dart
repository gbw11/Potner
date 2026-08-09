import 'package:potner_app/features/photo/domain/photo_models.dart';

abstract class PhotoRepository {
  /// 앨범이나 카메라에서 선택한 파일을 식물의 대표 사진으로 올린다.
  Future<void> uploadRepresentativePhoto({
    required String plantId,
    required String filePath,
    required String fileName,
  });

  /// 기간 안의 성장 사진을 오래된 순으로 돌려준다. 양쪽 날짜 모두 포함이다.
  Future<List<PlantPhoto>> getPhotos({
    required String plantId,
    required DateTime from,
    required DateTime to,
  });

  /// 포토 로그의 저장된 사진을 대표 사진으로 지정한다.
  Future<void> selectRepresentativePhoto({
    required String plantId,
    required String photoId,
  });

  /// 대표 사진을 내린다. 장치가 찍은 사진이면 포토 로그에는 그대로 남는다.
  Future<void> clearRepresentativePhoto({required String plantId});

  /// 포토 로그에서 사진 한 장을 지운다.
  ///
  /// 되돌릴 수 없다. 파일과 기록이 함께 사라지고 타임랩스에서도 그 날이 빠진다.
  /// 대표 사진으로 걸려 있었다면 지정도 함께 해제된다.
  Future<void> deletePhoto({required String plantId, required String photoId});
}
