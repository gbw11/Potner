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
}
